package app.mediary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.mediary.core.InstructionDraft
import app.mediary.core.MedicationPlan
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlanEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingClockShowsSpecificErrorAndConfirmedClockCanBeSaved() {
        var saved: MedicationPlan? = null
        val draft = InstructionDraft("测试药", "1片", "", "", "", "测试药，每次1片。", emptyList())
        compose.setContent { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it }) } }

        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.onNodeWithText("请添加至少一个提醒钟点，可点击「选择钟点」").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }

        compose.onNode(hasSetTextAction() and hasText("已确认的提醒钟点")).performScrollTo().performTextInput("8：00，18:00")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)), saved!!.times) }
    }

    @Test fun choosingClockAddsOnlyTheConfirmedPickerValue() {
        var saved: MedicationPlan? = null
        val draft = InstructionDraft("测试药", "1片", "每天08:00", "", "", "测试药，每次1片，每天08:00。", emptyList())
        compose.setContent { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it }) } }
        compose.onNodeWithText("选择钟点").performScrollTo().performClick()
        val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("clock-picker")))
        fields[0].performTextReplacement("08")
        fields[1].performTextReplacement("00")
        compose.onNodeWithText("添加钟点").performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalTime.of(8, 0)), saved!!.times) }
    }

    @Test fun dailyFrequencyPrefillsEditableClockAndStillRequiresConfirmation() {
        var saved: MedicationPlan? = null
        val draft = InstructionDraft("测试药", "1片", "每天2次", "", "", "测试药，每次1片，每天2次。", emptyList())
        compose.setContent { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it }) } }
        compose.onNodeWithText("确认并保存计划").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("已确认的提醒钟点")).performScrollTo().assertTextContains("08:00, 18:00")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)), saved!!.times) }
    }

    @Test fun threeTimesPresetNeedsNoClockTyping() {
        var saved: MedicationPlan? = null
        val draft = InstructionDraft("测试药", "1片", "每日三次", "", "", "测试药，每次1片，每日三次。", emptyList())
        compose.setContent { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it }) } }
        compose.onNodeWithText("每天3次").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalTime.of(8, 0), LocalTime.NOON, LocalTime.of(18, 0)), saved!!.times) }
    }
}
