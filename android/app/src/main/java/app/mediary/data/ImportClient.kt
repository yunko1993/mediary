package app.mediary.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import app.mediary.BuildConfig
import app.mediary.core.GptConnectionRules
import app.mediary.core.InstructionDraft
import app.mediary.core.InstructionExtraction
import org.json.JSONArray
import org.json.JSONObject

typealias ImportDraft = InstructionDraft

enum class ImportMode { ZHIPU, DIRECT, RELAY }

object ZhipuFree {
    const val endpoint = "https://open.bigmodel.cn/api/paas/v4"
    const val model = "glm-4.7-flash"
}

// 凭据不使用 data class，避免默认 toString 在诊断时暴露密钥。
class GptConnection(val mode: ImportMode, val endpoint: String, val credential: String, val model: String = "gpt-4.1-mini") {
    fun validate() {
        GptConnectionRules.validateEndpoint(endpoint, allowLocalHttp = mode == ImportMode.RELAY && BuildConfig.DEBUG)
        GptConnectionRules.validateSecret(credential)
        if (mode == ImportMode.DIRECT) GptConnectionRules.validateModel(model)
        // 内置入口锁定官方地址和具体 Flash 型号，任何配置偏差都先阻止请求，不做模型回退。
        if (mode == ImportMode.ZHIPU) require(endpoint == ZhipuFree.endpoint && model == ZhipuFree.model) { "智谱入口仅允许调用固定的 GLM-4.7-Flash 型号" }
    }
}

object ExtractionProtocol {
    private val fields = listOf("name", "dose", "frequency", "instructions", "duration", "sourceQuote")

    private fun schema(): JSONObject {
        val properties = JSONObject()
        fields.forEach { properties.put(it, JSONObject().put("type", "string")) }
        properties.put("missing", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        val item = JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties)
            .put("required", JSONArray(fields + "missing"))
        return JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject().put("medications", JSONObject().put("type", "array").put("items", item)))
            .put("required", JSONArray(listOf("medications")))
    }

    fun request(model: String, source: String): JSONObject {
        return JSONObject().put("model", model).put("store", false).put("instructions", InstructionExtraction.instructions)
            .put("input", source).put("max_output_tokens", 5000).put("text", JSONObject().put("format",
                JSONObject().put("type", "json_schema").put("name", "medication_drafts").put("strict", true).put("schema", schema())))
    }

    fun zhipuRequest(source: String): JSONObject = JSONObject().put("model", ZhipuFree.model).put("stream", false).put("max_tokens", 5000)
        .put("response_format", JSONObject().put("type", "json_object"))
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", InstructionExtraction.instructions + "\n仅输出 JSON 对象，结构必须符合以下 JSON Schema：\n" + schema()))
            .put(JSONObject().put("role", "user").put("content", source)))

    /** 模型名称、完整结束标识和原文证据均核对后，才把聊天补全结果作为草稿。 */
    fun readZhipuResponse(body: String, source: String): List<ImportDraft> {
        val response = json(body)
        require(response.opt("model") == ZhipuFree.model) { "接口返回的模型标识与 GLM-4.7-Flash 不一致，已停止导入，请核对平台调用记录" }
        val choices = response.optJSONArray("choices") ?: invalidFormat()
        require(choices.length() == 1) { "模型返回了多个或空的结果，请重试或手动添加" }
        val choice = choices.optJSONObject(0) ?: invalidFormat()
        require(choice.optString("finish_reason") == "stop") { "模型未完整完成解析，请重试或手动添加" }
        val message = choice.optJSONObject("message") ?: invalidFormat()
        require(!message.has("refusal") || message.isNull("refusal") || message.opt("refusal") == "") { "模型未能处理这段内容，可以按医嘱手动添加" }
        require(message.optJSONArray("tool_calls")?.length().let { it == null || it == 0 }) { "模型返回了工具请求，已停止导入" }
        // 不读取推理文本，仅核对最终 JSON；复用两种旧接口相同的原文证据和人工确认边界。
        return readDrafts(message.opt("content") as? String ?: invalidFormat(), source)
    }

    fun readResponse(body: String, source: String): List<ImportDraft> {
        val response = json(body)
        require(response.optString("status") == "completed") { "模型未完成解析，请重试或手动添加" }
        val output = response.optJSONArray("output") ?: invalidFormat()
        val text = StringBuilder()
        for (index in 0 until output.length()) {
            val content = output.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (part in 0 until content.length()) {
                val item = content.optJSONObject(part) ?: invalidFormat()
                require(item.optString("type") != "refusal") { "模型未能处理这段内容，可以按医嘱手动添加" }
                if (item.optString("type") == "output_text") text.append(item.opt("text") as? String ?: invalidFormat())
            }
        }
        return readDrafts(text.toString(), source)
    }

    fun readDrafts(body: String, source: String): List<ImportDraft> {
        val root = json(body)
        require(root.keys().asSequence().toSet() == setOf("medications")) { "解析结果格式异常，请重试或手动添加" }
        val medications = root.optJSONArray("medications") ?: invalidFormat()
        require(medications.length() <= 30) { "药物草稿过多，请分段导入" }
        val drafts = (0 until medications.length()).map { index ->
            val item = medications.optJSONObject(index) ?: invalidFormat()
            require(item.keys().asSequence().toSet() == (fields + "missing").toSet()) { "药物草稿格式异常，请重试或手动添加" }
            fun field(name: String) = item.opt(name) as? String ?: invalidFormat()
            val missing = item.optJSONArray("missing") ?: invalidFormat()
            require(missing.length() <= 20) { "药物草稿格式异常，请重试或手动添加" }
            ImportDraft(field("name"), field("dose"), field("frequency"), field("instructions"), field("duration"), field("sourceQuote"),
                (0 until missing.length()).map { missing.opt(it) as? String ?: invalidFormat() })
        }
        return InstructionExtraction.validate(drafts, source)
    }

    private fun json(text: String): JSONObject = runCatching { JSONObject(text) }.getOrElse { invalidFormat() }
    private fun invalidFormat(): Nothing = throw IllegalArgumentException("AI 返回内容格式异常，请重试或手动添加")
}

object ImportClient {
    fun parse(settings: ConnectionSettings, text: String): List<ImportDraft> = parse(settings.active(), text)

    fun parse(profile: GptConnection, text: String): List<ImportDraft> = parse(profile, text) { it.openConnection() as HttpURLConnection }

    /** 只返回经原文校验的草稿；不写入计划，网络异常不携带上游原文或凭据。 */
    internal fun parse(profile: GptConnection, text: String, openConnection: (URL) -> HttpURLConnection): List<ImportDraft> {
        require(text.isNotBlank() && text.length <= 12_000) { "请提供 1 至 12000 字的医嘱原文" }
        require(profile.credential.isNotBlank()) {
            when (profile.mode) {
                ImportMode.ZHIPU -> "请先在设置中填写智谱 API Key，手机即可独立整理医嘱"
                ImportMode.DIRECT -> "请先在设置中填写 API 密钥，手机即可独立整理医嘱"
                ImportMode.RELAY -> "请先在设置中填写服务访问口令"
            }
        }
        profile.validate()
        val path = when (profile.mode) { ImportMode.ZHIPU -> "/chat/completions"; ImportMode.DIRECT -> "/responses"; ImportMode.RELAY -> "/v1/instructions/parse" }
        val request = when (profile.mode) {
            ImportMode.ZHIPU -> ExtractionProtocol.zhipuRequest(text)
            ImportMode.DIRECT -> ExtractionProtocol.request(profile.model, text)
            ImportMode.RELAY -> JSONObject().put("text", text)
        }
        val connection = openConnection(URI(profile.endpoint.trimEnd('/') + path).toURL())
        try {
            connection.requestMethod = "POST"
            // 密钥只发送到用户已保存的地址，禁止跟随重定向转发凭据或医嘱正文。
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 65_000
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer ${profile.credential}")
            connection.doOutput = true
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            require(status in 200..299) { errorFor(status, profile.mode) }
            val body = connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(bytes.size() + count <= 1_048_576) { "GPT 返回内容过大，请分段导入" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
            return when (profile.mode) {
                ImportMode.ZHIPU -> ExtractionProtocol.readZhipuResponse(body, text)
                ImportMode.DIRECT -> ExtractionProtocol.readResponse(body, text)
                ImportMode.RELAY -> ExtractionProtocol.readDrafts(body, text)
            }
        } catch (_: SocketTimeoutException) {
            throw IllegalArgumentException("AI 请求超时，请稍后重试；本地计划和提醒仍可使用")
        } catch (_: IOException) {
            throw IllegalArgumentException("无法连接 AI 接口，请检查手机网络和接口地址")
        } finally { connection.disconnect() }
    }

    // 不展示上游错误正文，避免兼容接口回显密钥或医嘱内容。
    internal fun errorFor(status: Int, mode: ImportMode): String = when {
        status == 401 || status == 403 -> if (mode != ImportMode.RELAY) "API 密钥无效或没有调用权限，请检查手机中的 AI 设置" else "服务访问口令无效或没有调用权限"
        status == 429 -> "AI 请求受限或可用额度不足，请检查接口账户后重试"
        status == 400 || status == 404 -> when (mode) {
            ImportMode.ZHIPU -> "智谱接口请求未通过，请核对平台权限与 GLM-4.7-Flash 可用状态；不会改用其他型号"
            ImportMode.DIRECT -> "请检查接口地址和模型；接口需支持 Responses API 及结构化输出"
            ImportMode.RELAY -> "医嘱整理服务地址或请求无效，请检查服务配置"
        }
        status in 300..399 -> "接口发生重定向，请核对并填写最终的 HTTPS 地址"
        else -> "AI 服务暂不可用（$status），请稍后重试"
    }
}
