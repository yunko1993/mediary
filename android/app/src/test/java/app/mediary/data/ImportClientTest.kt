package app.mediary.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportClientTest {
    private val source = "虚构甲药，每次1测试单位，每日三次，饭后，7天。"
    private val profile = GptConnection(ImportMode.DIRECT, "https://api.example.invalid/v1", "fake-test-key", "test-model")
    private fun item() = JSONObject().put("name", "虚构甲药").put("dose", "1测试单位").put("frequency", "每日三次")
        .put("instructions", "饭后").put("duration", "7天").put("sourceQuote", source).put("missing", JSONArray())
    private fun drafts(item: JSONObject = item()) = JSONObject().put("medications", JSONArray().put(item)).toString()
    private fun response(text: String = drafts(), status: String = "completed") = JSONObject().put("status", status).put("output",
        JSONArray().put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text))))).toString()

    @Test fun directCallUsesResponsesContractAndKeepsUserTextAsData() {
        lateinit var connection: FakeConnection
        val result = ImportClient.parse(profile, source) { url -> FakeConnection(url, 200, response()).also { connection = it } }
        assertEquals("/v1/responses", connection.url.path)
        assertEquals("Bearer fake-test-key", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        val request = JSONObject(connection.written.toString("UTF-8"))
        assertEquals(source, request.getString("input"))
        assertEquals("test-model", request.getString("model"))
        assertFalse(request.getBoolean("store"))
        val format = request.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        assertEquals("每日三次", result.single().frequency)
        assertTrue(result.single().missing.contains("具体提醒时间需人工核对"))
    }

    @Test fun optionalRelayKeepsItsOwnProtocolAndStillChecksEvidenceLocally() {
        val relay = GptConnection(ImportMode.RELAY, "https://relay.example.invalid", "relay-test-token")
        lateinit var connection: FakeConnection
        val result = ImportClient.parse(relay, source) { url -> FakeConnection(url, 200, drafts()).also { connection = it } }
        assertEquals("/v1/instructions/parse", connection.url.path)
        assertEquals(setOf("text"), JSONObject(connection.written.toString("UTF-8")).keys().asSequence().toSet())
        assertEquals("虚构甲药", result.single().name)
    }

    @Test fun redirectIsRejectedWithoutFollowingOrReadingTheBody() {
        lateinit var connection: FakeConnection
        val error = failure { ImportClient.parse(profile, source) { url -> FakeConnection(url, 302, "private-body").also { connection = it } } }
        assertTrue(error.message!!.contains("重定向"))
        assertFalse(connection.bodyRead)
        assertTrue(connection.disconnected)
    }

    @Test fun upstreamErrorsNeverExposeProviderBodyOrCredentials() {
        lateinit var connection: FakeConnection
        val error = failure { ImportClient.parse(profile, source) { url -> FakeConnection(url, 401, "private-body fake-test-key $source").also { connection = it } } }
        assertTrue(error.message!!.contains("密钥"))
        assertFalse(error.message!!.contains("fake-test-key"))
        assertFalse(error.message!!.contains(source))
        assertFalse(connection.bodyRead)
        assertTrue(connection.disconnected)
    }

    @Test fun responseSizeIsBoundedBeforeJsonParsing() {
        lateinit var connection: FakeConnection
        val error = failure { ImportClient.parse(profile, source) { url -> FakeConnection(url, 200, "a".repeat(1_048_577)).also { connection = it } } }
        assertTrue(error.message!!.contains("过大"))
        assertTrue(connection.disconnected)
    }

    @Test fun networkErrorsAreSanitizedAndConnectionIsClosed() {
        lateinit var connection: FakeConnection
        val error = failure { ImportClient.parse(profile, source) { url -> object : FakeConnection(url, 200, response()) {
            override fun getOutputStream(): ByteArrayOutputStream { throw IOException("private-body $source") }
        }.also { connection = it } } }
        assertFalse(error.message!!.contains(source))
        assertTrue(connection.disconnected)
    }

    @Test fun missingKeyFailsBeforeOpeningAnyConnection() {
        var opened = false
        val empty = GptConnection(ImportMode.DIRECT, profile.endpoint, "")
        val error = failure { ImportClient.parse(empty, source) { opened = true; FakeConnection(it, 200, response()) } }
        assertFalse(opened)
        assertTrue(error.message!!.contains("手机即可独立"))
    }

    @Test fun completedResponseStillRejectsInventedDoseTiming() {
        failure { ExtractionProtocol.readResponse(response(drafts(item().put("frequency", "每8小时"))), source) }
    }

    @Test fun nullAndNonStringFieldsAreNotCoercedIntoMedicationText() {
        failure { ExtractionProtocol.readResponse(response(drafts(item().put("dose", 1))), source) }
        failure { ExtractionProtocol.readDrafts(drafts(item().put("dose", JSONObject.NULL)), source) }
    }

    @Test fun unrequestedFieldsAndMissingRequiredFieldsAreRejected() {
        failure { ExtractionProtocol.readDrafts(drafts(item().put("advice", "private-body")), source) }
        val incomplete = item().apply { remove("dose") }
        failure { ExtractionProtocol.readDrafts(drafts(incomplete), source) }
    }

    @Test fun refusalAndIncompleteOutputsNeverBecomeDrafts() {
        failure { ExtractionProtocol.readResponse(response(status = "incomplete"), source) }
        val refusal = JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject().put("content",
            JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "private-body"))))).toString()
        assertTrue(failure { ExtractionProtocol.readResponse(refusal, source) }.message!!.contains("未能处理"))
    }

    @Test fun malformedResponseErrorsDoNotEchoInput() {
        assertFalse(failure { ExtractionProtocol.readResponse("private-body $source", source) }.message!!.contains(source))
    }

    @Test fun noMedicinesMayProduceAnEmptyArray() {
        assertTrue(ExtractionProtocol.readResponse(response("{\"medications\":[]}"), source).isEmpty())
    }

    private fun failure(block: () -> Any): IllegalArgumentException {
        try { block(); throw AssertionError("Expected validation failure") } catch (error: IllegalArgumentException) { return error }
    }

    private open class FakeConnection(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        var bodyRead = false
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getOutputStream(): ByteArrayOutputStream = written
        override fun getInputStream(): ByteArrayInputStream { bodyRead = true; return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) }
        override fun getErrorStream(): ByteArrayInputStream { bodyRead = true; return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) }
    }
}
