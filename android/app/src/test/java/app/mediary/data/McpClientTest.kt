package app.mediary.data

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpClientTest {
    private val account = "https://mediary.example.invalid/mediary"
    private val profile = GptConnection(ImportMode.RELAY, account, "fake-test-service-token-1234567890")
    private val source = "测试药甲，每次1片，每日三次。"
    private fun entry() = JSONObject().put("id", "11111111-1111-4111-8111-111111111111").put("sourceText", source)
        .put("createdAt", "2026-10-20T00:00:00Z").put("reviewedAt", JSONObject.NULL).put("medication", JSONObject()
            .put("name", "测试药甲").put("dose", "每次1片").put("frequency", "每日三次").put("instructions", "").put("duration", "")
            .put("sourceQuote", source).put("missing", JSONArray()))
    private fun page(item: JSONObject = entry(), cursor: Any = JSONObject.NULL) = JSONObject().put("drafts", JSONArray().put(item)).put("nextCursor", cursor).toString()

    @Test fun downloadedDraftsRemainUnscheduledAndKeepTheirAccount() {
        val item = McpClient.decodePage(page(), account, 0).drafts.single()
        assertFalse(item.reviewed)
        assertNull(item.localPlanId)
        assertEquals("每日三次", item.draft.frequency)
        assertTrue(item.draft.missing.contains("具体提醒时间需人工核对"))
        assertNotEquals(item.key, item.copy(account = "https://other.example.invalid").key)
        assertEquals(item, McpClient.decode(item.json(), account))
    }

    @Test fun fabricatedTimesAndStuckPaginationAreRejected() {
        val item = entry().apply { getJSONObject("medication").put("frequency", "每8小时") }
        assertThrows(IllegalArgumentException::class.java) { McpClient.decodePage(page(item), account, 0) }
        assertThrows(IllegalArgumentException::class.java) { McpClient.decodePage(page(cursor = 5), account, 5) }
    }

    @Test fun credentialsStayOnConfiguredServiceAndRedirectsCannotForwardThem() {
        lateinit var connection: Fake
        McpClient.request(profile, "/v1/drafts") { url -> Fake(url, 200, "{}").also { connection = it } }
        assertEquals("mediary.example.invalid", connection.url.host)
        assertEquals("/mediary/v1/drafts", connection.url.path)
        assertEquals("Bearer ${profile.credential}", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        val failure = assertThrows(IllegalArgumentException::class.java) { McpClient.request(profile, "/v1/drafts") { url -> Fake(url, 302, profile.credential + source) } }
        assertFalse(failure.message!!.contains(profile.credential)); assertFalse(failure.message!!.contains(source))
    }

    private class Fake(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }
}
