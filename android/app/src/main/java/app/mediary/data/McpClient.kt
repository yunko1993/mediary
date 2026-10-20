package app.mediary.data

import app.mediary.core.InstructionDraft
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant

/** account 隔离不同服务的草稿与回执；本地保存标记不能随网络重试丢失。 */
data class InboxDraft(val id: String, val account: String, val sourceText: String, val draft: ImportDraft,
    val createdAt: String, val reviewedOnServer: Boolean = false, val localPlanId: String? = null) {
    val key: String get() = "$account|$id"
    val reviewed: Boolean get() = reviewedOnServer || localPlanId != null
    fun json(): JSONObject = JSONObject().put("id", id).put("sourceText", sourceText).put("createdAt", createdAt)
        .put("reviewedAt", if (reviewedOnServer) createdAt else JSONObject.NULL)
        .put("medication", JSONObject().put("name", draft.name).put("dose", draft.dose).put("frequency", draft.frequency)
            .put("instructions", draft.instructions).put("duration", draft.duration).put("sourceQuote", draft.source).put("missing", JSONArray(draft.missing)))
}

data class InboxPage(val drafts: List<InboxDraft>, val nextCursor: Long?)

object McpClient {
    fun decode(item: JSONObject, account: String): InboxDraft {
        val id = item.getString("id")
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "草稿标识无效，请检查服务版本" }
        val source = item.getString("sourceText")
        require(source.isNotBlank() && source.length <= 12_000) { "草稿原文格式异常" }
        val draft = ExtractionProtocol.readDrafts(JSONObject().put("medications", JSONArray().put(item.getJSONObject("medication"))).toString(), source).single()
        val created = item.getString("createdAt")
        Instant.parse(created)
        return InboxDraft(id, account, source, draft, created, !item.isNull("reviewedAt"))
    }

    internal fun decodePage(text: String, account: String, after: Long): InboxPage {
        val page = JSONObject(text)
        val array = page.getJSONArray("drafts")
        require(array.length() <= 20) { "收件箱返回内容过多，请检查服务版本" }
        val drafts = (0 until array.length()).map { decode(array.getJSONObject(it), account) }
        val cursor = if (page.isNull("nextCursor")) null else page.getLong("nextCursor")
        require(cursor == null || (cursor > after && array.length() > 0)) { "收件箱分页异常，请稍后重试" }
        require(drafts.map { it.id }.distinct().size == drafts.size) { "收件箱返回重复草稿" }
        return InboxPage(drafts, cursor)
    }

    fun page(profile: GptConnection, after: Long): InboxPage = decodePage(
        request(profile, "/v1/drafts?after=$after&limit=20"), profile.endpoint, after)

    fun receipt(profile: GptConnection, drafts: List<InboxDraft>) {
        if (drafts.isEmpty()) return
        require(drafts.size <= 50 && drafts.all { it.account == profile.endpoint }) { "草稿所属服务不匹配，请核对连接设置" }
        val items = JSONArray(drafts.map { draft -> JSONObject().put("id", draft.id).apply { draft.localPlanId?.let { put("planId", it) } } })
        request(profile, "/v1/drafts/receipts", JSONObject().put("receipts", items))
    }

    /** 口令只发到已保存的服务地址，拒绝重定向；错误消息不显示服务器回传的医嘱或凭据。 */
    internal fun request(profile: GptConnection, path: String, body: JSONObject? = null,
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): String {
        profile.validate()
        require(profile.mode == ImportMode.RELAY) { "请选择 Mediary 服务连接" }
        val connection = open(URI(profile.endpoint.trimEnd('/') + path).toURL())
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer ${profile.credential}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            require(status in 200..299) { when (status) {
                401, 403 -> "服务访问口令无效，请在设置中检查 ChatGPT 草稿连接"
                409 -> "此草稿已关联另一条手机计划，请核对，未覆盖已有计划"
                404 -> "未找到草稿接口，请检查服务地址及服务版本"
                429 -> "同步请求较多，请稍后重试"
                in 300..399 -> "服务发生重定向，请填写最终 HTTPS 地址"
                else -> "草稿同步暂不可用（$status），本地提醒仍可使用"
            } }
            return connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(bytes.size() + count <= 2_097_152) { "草稿返回内容过大，请检查服务" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        } catch (_: IOException) { throw IllegalArgumentException("无法连接草稿收件箱，请检查网络后重试；本地提醒仍可使用") }
        finally { connection.disconnect() }
    }
}
