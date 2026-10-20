package app.mediary.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import app.mediary.BuildConfig
import app.mediary.core.GptConnectionRules
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConnectionSettings(context: Context, preferenceName: String = "connection") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    // 已配置的旧连接保持原模式；首次使用直接展示锁定型号的智谱入口。
    val mode: ImportMode get() = preferences.getString("mode", null)?.let { runCatching { ImportMode.valueOf(it) }.getOrNull() }
        ?: when { preferences.contains("endpoint") -> ImportMode.RELAY; preferences.contains("apiKey") -> ImportMode.DIRECT; else -> ImportMode.ZHIPU }
    val apiBaseUrl: String get() = preferences.getString("apiBaseUrl", "https://api.openai.com/v1")!!
    val apiModel: String get() = preferences.getString("apiModel", "gpt-4.1-mini")!!
    val apiKey: String get() = decrypt("apiKey", apiBaseUrl)
    val endpoint: String get() = preferences.getString("endpoint", "http://127.0.0.1:8787")!!
    val token: String get() = decrypt("token")
    val zhipuKey: String get() = decrypt("zhipuKey", ZhipuFree.endpoint)

    fun clearDirectKey() { preferences.edit { remove("apiKey") } }
    fun clearZhipuKey() { preferences.edit { remove("zhipuKey") } }

    fun active(): GptConnection = when (mode) {
        ImportMode.ZHIPU -> GptConnection(mode, ZhipuFree.endpoint, zhipuKey, ZhipuFree.model)
        ImportMode.DIRECT -> GptConnection(mode, apiBaseUrl, apiKey, apiModel)
        ImportMode.RELAY -> GptConnection(mode, endpoint, token)
    }

    fun saveZhipu(apiKey: String) {
        val secret = apiKey.trim()
        GptConnectionRules.validateSecret(secret)
        val encoded = encrypt(secret, ZhipuFree.endpoint)
        preferences.edit { putString("mode", ImportMode.ZHIPU.name); putString("zhipuKey", encoded) }
    }

    fun saveDirect(baseUrl: String, model: String, apiKey: String) {
        val base = baseUrl.trim().trimEnd('/')
        val name = model.trim()
        val secret = apiKey.trim()
        GptConnectionRules.validateEndpoint(base)
        GptConnectionRules.validateModel(name)
        GptConnectionRules.validateSecret(secret)
        // 同一次保存原子写入地址、模型和密钥；密钥密文绑定地址，防止误配到其他提供方。
        val encoded = encrypt(secret, base)
        preferences.edit { putString("mode", ImportMode.DIRECT.name); putString("apiBaseUrl", base); putString("apiModel", name); putString("apiKey", encoded) }
    }

    fun saveRelay(endpoint: String, token: String) {
        val base = endpoint.trim().trimEnd('/')
        val secret = token.trim()
        GptConnectionRules.validateEndpoint(base, allowLocalHttp = BuildConfig.DEBUG)
        GptConnectionRules.validateSecret(secret)
        val encoded = encrypt(secret)
        preferences.edit { putString("mode", ImportMode.RELAY.name); putString("endpoint", base); putString("token", encoded) }
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
