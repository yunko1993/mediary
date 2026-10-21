package app.mediary.data

import app.mediary.BuildConfig
import app.mediary.core.InstructionDraft
import app.mediary.core.InstructionExtraction
import app.mediary.core.McpConnectionRules
import org.json.JSONObject

typealias ImportDraft = InstructionDraft

// 凭据不使用 data class，避免默认 toString 在诊断时暴露口令。
class McpConnection(val endpoint: String, val credential: String) {
    fun validate() {
        McpConnectionRules.validateEndpoint(endpoint, allowLocalHttp = BuildConfig.DEBUG)
        McpConnectionRules.validateSecret(credential)
        require(credential.length >= 24) { "服务访问口令至少需要 24 位" }
    }
}

/** MCP 只接收草稿；结构、原文证据与人工确认边界仍在手机再次验证。 */
object DraftProtocol {
    private val fields = listOf("name", "dose", "frequency", "instructions", "duration", "sourceQuote")

    fun readDrafts(body: String, source: String): List<ImportDraft> {
        val root = runCatching { JSONObject(body) }.getOrElse { invalidFormat() }
        require(root.keys().asSequence().toSet() == setOf("medications")) { "草稿格式异常，请重新同步" }
        val medications = root.optJSONArray("medications") ?: invalidFormat()
        require(medications.length() <= 30) { "药物草稿过多，请分段处理" }
        val drafts = (0 until medications.length()).map { index ->
            val item = medications.optJSONObject(index) ?: invalidFormat()
            require(item.keys().asSequence().toSet() == (fields + "missing").toSet()) { "药物草稿格式异常，请重新同步" }
            fun field(name: String) = item.opt(name) as? String ?: invalidFormat()
            val missing = item.optJSONArray("missing") ?: invalidFormat()
            require(missing.length() <= 20) { "药物草稿格式异常，请重新同步" }
            ImportDraft(field("name"), field("dose"), field("frequency"), field("instructions"), field("duration"), field("sourceQuote"),
                (0 until missing.length()).map { missing.opt(it) as? String ?: invalidFormat() })
        }
        return InstructionExtraction.validate(drafts, source)
    }

    private fun invalidFormat(): Nothing = throw IllegalArgumentException("草稿内容格式异常，请重新同步或手动添加")
}
