package app.mediary.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZhipuImportTest {
    private val source = "虚构甲药，每次1测试单位，每日三次，饭后，7天。"
    private val profile = GptConnection(ImportMode.ZHIPU, ZhipuFree.endpoint, "fake-zhipu-key", ZhipuFree.model)
    private fun drafts() = JSONObject().put("medications", JSONArray().put(JSONObject().put("name", "虚构甲药")
        .put("dose", "1测试单位").put("frequency", "每日三次").put("instructions", "饭后").put("duration", "7天")
        .put("sourceQuote", source).put("missing", JSONArray()))).toString()
    private fun response(model: String = ZhipuFree.model, finish: String = "stop", text: String = drafts()) = JSONObject()
        .put("model", model).put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("role", "assistant").put("content", text)))).toString()

    @Test fun requestPinsOfficialOriginAndExactFlashModel() {
        lateinit var connection: FakeConnection
        val result = ImportClient.parse(profile, source) { url -> FakeConnection(url, 200, response()).also { connection = it } }
        assertEquals("open.bigmodel.cn", connection.url.host)
        assertEquals("/api/paas/v4/chat/completions", connection.url.path)
        val request = JSONObject(connection.written.toString("UTF-8"))
        assertEquals("glm-4.7-flash", request.getString("model"))
        assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
        assertFalse(request.getBoolean("stream"))
        assertEquals(source, request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("每日三次", result.single().frequency)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun changedModelIsRejectedBeforeAnyNetworkRequest() {
        var opened = false
        val changed = GptConnection(ImportMode.ZHIPU, ZhipuFree.endpoint, "fake-key", "glm-4.7")
        failure { ImportClient.parse(changed, source) { opened = true; FakeConnection(it, 200, response()) } }
        assertFalse(opened)
    }

    @Test fun changedProviderOriginIsRejectedBeforeSendingCredentials() {
        var opened = false
        val changed = GptConnection(ImportMode.ZHIPU, "https://other.example.invalid", "fake-key", ZhipuFree.model)
        failure { ImportClient.parse(changed, source) { opened = true; FakeConnection(it, 200, response()) } }
        assertFalse(opened)
    }

    @Test fun anotherModelInResponseCannotSilentlyBecomeAnAcceptedDraft() {
        listOf("glm-4.7", "glm-4.7-flashx", "").forEach { model ->
            val error = failure { ExtractionProtocol.readZhipuResponse(response(model = model), source) }
            assertTrue(error.message!!.contains("模型标识"))
        }
    }

    @Test fun modelMismatchDoesNotRetryWithAnotherModel() {
        var attempts = 0
        failure { ImportClient.parse(profile, source) { url -> attempts++; FakeConnection(url, 200, response(model = "glm-4.7")) } }
        assertEquals(1, attempts)
    }

    @Test fun httpFailureDoesNotFallBackOrExposeErrorBody() {
        var attempts = 0
        val error = failure { ImportClient.parse(profile, source) { url -> attempts++; FakeConnection(url, 500, "private-body fake-zhipu-key") } }
        assertEquals(1, attempts)
        assertFalse(error.message!!.contains("fake-zhipu-key"))
    }

    @Test fun truncatedResponseIsRejectedEvenWhenTextLooksLikeCompleteJson() {
        failure { ExtractionProtocol.readZhipuResponse(response(finish = "length"), source) }
    }

    @Test fun reasoningContentIsNeverUsedAsTheFinalDraft() {
        val body = JSONObject(response(text = "not-json"))
        body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("reasoning_content", drafts())
        failure { ExtractionProtocol.readZhipuResponse(body.toString(), source) }
    }

    @Test fun sourceEvidenceStillRejectsInventedDoseTiming() {
        val content = JSONObject(drafts())
        content.getJSONArray("medications").getJSONObject(0).put("frequency", "每8小时")
        failure { ExtractionProtocol.readZhipuResponse(response(text = content.toString()), source) }
    }

    @Test fun refusalCannotBecomeAConfirmedDraft() {
        val body = JSONObject(response())
        body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal", "private-body")
        failure { ExtractionProtocol.readZhipuResponse(body.toString(), source) }
    }

    @Test fun missingKeyFailsBeforeOpeningAConnection() {
        var opened = false
        val empty = GptConnection(ImportMode.ZHIPU, ZhipuFree.endpoint, "", ZhipuFree.model)
        val error = failure { ImportClient.parse(empty, source) { opened = true; FakeConnection(it, 200, response()) } }
        assertFalse(opened)
        assertTrue(error.message!!.contains("智谱 API Key"))
    }

    @Test fun multipleChoicesAreNotMergedIntoOneMedicalPlan() {
        val body = JSONObject(response())
        val choices = body.getJSONArray("choices")
        choices.put(choices.getJSONObject(0))
        failure { ExtractionProtocol.readZhipuResponse(body.toString(), source) }
    }

    private fun failure(block: () -> Any): IllegalArgumentException {
        try { block(); throw AssertionError("Expected validation failure") } catch (error: IllegalArgumentException) { return error }
    }

    private class FakeConnection(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getOutputStream(): ByteArrayOutputStream = written
        override fun getInputStream(): ByteArrayInputStream = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
