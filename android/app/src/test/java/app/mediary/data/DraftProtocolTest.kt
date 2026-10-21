package app.mediary.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DraftProtocolTest {
    private val source = "测试药，每次1片，每日三次。"
    private fun draft() = JSONObject().put("name", "测试药").put("dose", "每次1片").put("frequency", "每日三次")
        .put("instructions", "").put("duration", "").put("sourceQuote", source).put("missing", JSONArray())
    private fun read(item: JSONObject) = DraftProtocol.readDrafts(JSONObject().put("medications", JSONArray().put(item)).toString(), source)

    @Test fun absentFieldsStayUnknownAndStillRequireTimeReview() {
        val saved = read(draft().put("dose", "")).single()
        assertEquals("", saved.dose)
        assertTrue(saved.missing.contains("每次剂量"))
        assertTrue(saved.missing.contains("具体提醒时间需人工核对"))
    }

    @Test fun unsupportedFieldsInventedDoseAndMalformedJsonCannotBecomeDrafts() {
        for (item in listOf(draft().put("dose", "每次2片"), draft().put("confirmPlan", true), draft().put("sourceQuote", "虚构"))) {
            assertThrows(IllegalArgumentException::class.java) { read(item) }
        }
        assertThrows(IllegalArgumentException::class.java) { DraftProtocol.readDrafts("{", source) }
        assertThrows(IllegalArgumentException::class.java) { read(draft().put("missing", "不正确的类型")) }
    }
}
