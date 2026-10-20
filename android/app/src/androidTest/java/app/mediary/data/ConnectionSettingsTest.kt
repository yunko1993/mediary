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
    private val address = "https://api.example.invalid/v1"
    private val secret = "fake-key-for-device-storage-test"

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // 真机验证只用随机测试偏好文件，不接触用户的连接配置、药物数据库或现有密钥。
        name = "connection-test-${UUID.randomUUID()}"
        settings = ConnectionSettings(context, name)
    }

    @After fun tearDown() { context.deleteSharedPreferences(name) }

    @Test fun freshInstallDefaultsToIndependentPhoneConnection() {
        assertEquals(ImportMode.ZHIPU, settings.mode)
        assertEquals(ZhipuFree.endpoint, settings.active().endpoint)
        assertEquals(ZhipuFree.model, settings.active().model)
        assertEquals("", settings.zhipuKey)
    }

    @Test fun keyRoundTripsButPreferencesContainOnlyCiphertext() {
        settings.saveDirect(address, "test-model", secret)
        assertEquals(secret, ConnectionSettings(context, name).active().credential)
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.none { it.toString().contains(secret) })
    }

    @Test fun mcpTokenIsEncryptedAndBoundToItsServiceAddress() {
        settings.saveMcp("https://mediary.example.invalid/mediary", secret)
        assertEquals(secret, ConnectionSettings(context, name).mcpToken)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertTrue(preferences.all.values.none { it.toString().contains(secret) })
        preferences.edit { putString("mcpEndpoint", "https://other.example.invalid") }
        assertEquals("", ConnectionSettings(context, name).mcpToken)
    }

    @Test fun failedValidationDoesNotReplaceSavedConfiguration() {
        settings.saveDirect(address, "test-model", secret)
        try { settings.saveDirect("http://example.invalid", "another-model", "another-fake-key"); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(address, settings.apiBaseUrl)
        assertEquals(secret, settings.apiKey)
        assertEquals("test-model", settings.apiModel)
    }

    @Test fun ciphertextCannotBeReusedForADifferentApiAddress() {
        settings.saveDirect(address, "test-model", secret)
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit { putString("apiBaseUrl", "https://other.example.invalid/v1") }
        assertEquals("", settings.apiKey)
    }

    @Test fun legacyRelayKeepsItsModeAndNeverBecomesAnApiKey() {
        settings.saveRelay("https://relay.example.invalid", "fake-relay-token")
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit { remove("mode") }
        assertEquals(ImportMode.RELAY, settings.mode)
        assertEquals("fake-relay-token", settings.active().credential)
        assertEquals("", settings.apiKey)
    }

    @Test fun clearingApiKeyKeepsOtherSettingsAndRelayCredential() {
        settings.saveRelay("https://relay.example.invalid", "fake-relay-token")
        settings.saveDirect(address, "test-model", secret)
        settings.clearDirectKey()
        assertEquals("", settings.apiKey)
        assertEquals(address, settings.apiBaseUrl)
        assertEquals("fake-relay-token", settings.token)
        assertEquals(ImportMode.DIRECT, settings.mode)
    }

    @Test fun zhipuKeyHasSeparateEncryptedStorageAndFixedModel() {
        settings.saveDirect(address, "test-model", secret)
        settings.saveZhipu("fake-zhipu-key")
        val restored = ConnectionSettings(context, name)
        assertEquals(ImportMode.ZHIPU, restored.mode)
        assertEquals("fake-zhipu-key", restored.active().credential)
        assertEquals(ZhipuFree.model, restored.active().model)
        assertEquals(ZhipuFree.endpoint, restored.active().endpoint)
        assertEquals(secret, restored.apiKey)
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.none { it.toString().contains("fake-zhipu-key") })
    }

    @Test fun clearingZhipuKeyDoesNotRemoveOtherProviders() {
        settings.saveDirect(address, "test-model", secret)
        settings.saveZhipu("fake-zhipu-key")
        settings.clearZhipuKey()
        assertEquals("", settings.zhipuKey)
        assertEquals(secret, settings.apiKey)
    }
}
