package app.mediary.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionSettingsTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var settings: ConnectionSettings
    private val address = "https://mediary.example.invalid"
    private val secret = "fake-key-for-device-storage-test"

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // 真机验证只用随机测试偏好文件，不接触用户的连接配置、药物数据库或现有密钥。
        name = "connection-test-${UUID.randomUUID()}"
        settings = ConnectionSettings(context, name)
    }

    @After fun tearDown() { context.deleteSharedPreferences(name) }

    @Test fun freshInstallHasOnlyAnUnconfiguredMcpConnection() {
        assertEquals("", settings.mcp().endpoint)
        assertEquals("", settings.mcp().credential)
    }

    @Test fun mcpTokenIsEncryptedAndBoundToItsServiceAddress() {
        settings.saveMcp(address, secret)
        assertEquals(secret, ConnectionSettings(context, name).mcpToken)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertTrue(preferences.all.values.none { it.toString().contains(secret) })
        preferences.edit { putString("mcpEndpoint", "https://other.example.invalid") }
        assertEquals("", ConnectionSettings(context, name).mcpToken)
    }

    @Test fun failedValidationDoesNotReplaceSavedConnection() {
        settings.saveMcp(address, secret)
        for ((endpoint, token) in listOf("http://example.invalid" to secret, address to "short")) {
            assertThrows(IllegalArgumentException::class.java) { settings.saveMcp(endpoint, token) }
        }
        assertEquals(address, settings.mcpEndpoint)
        assertEquals(secret, settings.mcpToken)
    }

    @Test fun upgradeRemovesRetiredModelSettingsAndKeepsMcpCredential() {
        settings.saveMcp(address, secret)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val retired = listOf("mode", "apiBaseUrl", "apiModel", "apiKey", "zhipuKey")
        preferences.edit(commit = true) { retired.forEach { putString(it, "obsolete-test-value") } }
        val restored = ConnectionSettings(context, name)
        assertEquals(address, restored.mcpEndpoint)
        assertEquals(secret, restored.mcpToken)
        assertTrue(retired.none { preferences.contains(it) })
    }

    @Test fun legacyServiceAddressRemainsAvailableForMigration() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { putString("endpoint", address) }
        assertEquals(address, ConnectionSettings(context, name).mcpEndpoint)
        assertEquals("", settings.mcpToken)
    }
}
