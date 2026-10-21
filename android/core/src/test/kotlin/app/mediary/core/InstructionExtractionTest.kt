package app.mediary.core

import org.junit.Assert.*
import org.junit.Test

class InstructionExtractionTest {
    private val source = "虚构甲药，每次1测试单位，每日三次，饭后，7天。"
    private fun draft() = InstructionDraft("虚构甲药", "1测试单位", "每日三次", "饭后", "7天", source, emptyList())

    @Test fun preservesQuotedFrequencyWithoutInventingClockTimes() {
        val result = InstructionExtraction.validate(listOf(draft()), source).single()
        assertEquals("每日三次", result.frequency)
        assertEquals("1测试单位", result.dose)
        assertTrue(result.missing.contains("具体提醒时间需人工核对"))
    }

    @Test fun unknownFieldsStayEmptyAndAreMarkedForConfirmation() {
        val result = InstructionExtraction.validate(listOf(draft().copy(dose = "", frequency = "", duration = "")), source).single()
        assertEquals("", result.dose)
        assertTrue(result.missing.containsAll(listOf("每次剂量", "频次", "疗程")))
    }

    @Test(expected = IllegalArgumentException::class) fun dailyThreeTimesCannotBecomeEveryEightHours() {
        InstructionExtraction.validate(listOf(draft().copy(frequency = "每8小时")), source)
    }

    @Test(expected = IllegalArgumentException::class) fun afterMealCannotGainExtraMinutes() {
        InstructionExtraction.validate(listOf(draft().copy(instructions = "饭后30分钟")), source)
    }

    @Test(expected = IllegalArgumentException::class) fun quoteMustExistInOriginalInput() {
        InstructionExtraction.validate(listOf(draft().copy(source = source + "自行停药")), source)
    }

    @Test(expected = IllegalArgumentException::class) fun fieldFromAnotherQuoteIsRejected() {
        InstructionExtraction.validate(listOf(draft().copy(dose = "2测试单位")), source + "虚构乙药，每次2测试单位。")
    }

    @Test fun lineWrappingDoesNotInvalidateOtherwiseExactEvidence() {
        val input = source.replace("，", "，\n\u3000")
        assertEquals(source, InstructionExtraction.validate(listOf(draft()), input).single().source)
    }

    @Test fun noExplicitMedicineCanRemainAnEmptyResult() { assertTrue(InstructionExtraction.validate(emptyList(), "请保持记录").isEmpty()) }

    @Test fun missingLabelsCannotCarryUnquotedMedicationAdvice() {
        val result = InstructionExtraction.validate(listOf(draft().copy(missing = listOf("开始日期", "自行加倍服用"))), source).single()
        assertTrue(result.missing.contains("开始日期"))
        assertFalse(result.missing.contains("自行加倍服用"))
    }

    @Test(expected = IllegalArgumentException::class) fun tooManyDraftsAreRejected() {
        InstructionExtraction.validate(List(31) { draft() }, source)
    }

    @Test(expected = IllegalArgumentException::class) fun excessivelyLongMissingLabelsAreRejected() {
        InstructionExtraction.validate(listOf(draft().copy(missing = listOf("a".repeat(101)))), source)
    }

    @Test fun mcpEndpointRequiresHttpsAndRejectsCredentialsOrParameters() {
        McpConnectionRules.validateEndpoint("https://mediary.example.invalid:8443")
        listOf("http://127.0.0.1:8787", "https://secret@example.invalid/v1", "https://example.invalid/v1?key=secret",
            "https://example.invalid/v1#key", "https://example.invalid:70000/v1", "not a url").forEach { endpoint ->
            expectInvalid { McpConnectionRules.validateEndpoint(endpoint) }
        }
    }

    @Test fun onlyExplicitLocalMcpMayUseHttp() {
        McpConnectionRules.validateEndpoint("http://127.0.0.1:8787", allowLocalHttp = true)
        expectInvalid { McpConnectionRules.validateEndpoint("http://example.invalid", allowLocalHttp = true) }
    }

    @Test fun authorizationCannotContainWhitespaceOrHeaderInjection() {
        McpConnectionRules.validateSecret("fake-test-service-token")
        listOf("", "a b", "a\rb", "a\nb", "a\tb").forEach { secret -> expectInvalid { McpConnectionRules.validateSecret(secret) } }
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }
}
