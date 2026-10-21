package app.mediary.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import app.mediary.BuildConfig
import app.mediary.core.McpConnectionRules
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConnectionSettings(context: Context, preferenceName: String = "connection") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    init {
        // 升级只清理废弃模型设置；MCP 口令及其 Keystore 密钥必须保留。
        preferences.edit {
            listOf("mode", "apiBaseUrl", "apiModel", "apiKey", "zhipuKey").forEach { remove(it) }
        }
    }
    val mcpEndpoint: String get() = preferences.getString("mcpEndpoint", null) ?: preferences.getString("endpoint", "")!!
    val mcpToken: String get() = if (preferences.contains("mcpToken")) decrypt("mcpToken", mcpEndpoint) else decrypt("token")

    fun mcp(): McpConnection = McpConnection(mcpEndpoint, mcpToken)

    fun saveMcp(endpoint: String, token: String) {
        val base = endpoint.trim().trimEnd('/')
        val secret = token.trim()
        McpConnectionRules.validateEndpoint(base, allowLocalHttp = BuildConfig.DEBUG)
        McpConnectionRules.validateSecret(secret)
        require(secret.length >= 24) { "服务访问口令至少需要 24 位" }
        val encoded = encrypt(secret, base)
        preferences.edit { putString("mcpEndpoint", base); putString("mcpToken", encoded) }
    }

    private fun decrypt(name: String, associatedAddress: String? = null): String = runCatching {
        val encoded = preferences.getString(name, null) ?: return ""
        val parts = encoded.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        associatedAddress?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")

    private fun encrypt(value: String, associatedAddress: String? = null): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        associatedAddress?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey("mediary-connection", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mediary-connection", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
