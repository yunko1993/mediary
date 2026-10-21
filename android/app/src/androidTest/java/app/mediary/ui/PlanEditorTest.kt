package app.mediary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import app.mediary.MainActivity
import app.mediary.core.InstructionDraft
import app.mediary.core.MedicationPlan
import app.mediary.core.ScheduleKind
import app.mediary.core.MedicationPerson
import app.mediary.data.Snapshot
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlanEditorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    // 使用应用自带 Activity，正式签名包也能验证；页面仅注入虚拟计划，不保存到日常数据库。
    private fun show(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        compose.waitForIdle()
    }

    @Test fun missingClockShowsSpecificErrorAndConfirmedClockCanBeSaved() {
        var saved: MedicationPlan? = null
        val draft = InstructionDraft("测试药", "1片", "", "", "", "测试药，每次1片。", emptyList())
        show { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("person-selector").performScrollTo().performClick()
        compose.onNodeWithTag("person-choice-self").performClick()

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
        show { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("person-selector").performScrollTo().performClick()
        compose.onNodeWithTag("person-choice-self").performClick()
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
        show { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("person-selector").performScrollTo().performClick()
        compose.onNodeWithTag("person-choice-self").performClick()
        compose.onNodeWithText("确认并保存计划").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("已确认的提醒钟点")).performScrollTo().assertTextContains("08:00, 18:00")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)), saved!!.times) }
    }

    @Test fun threeTimesPresetNeedsNoClockTyping() {
        var saved: MedicationPlan? = null
        val draft = InstructionDraft("测试药", "1片", "每日三次", "", "", "测试药，每次1片，每日三次。", emptyList())
        show { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("person-selector").performScrollTo().performClick()
        compose.onNodeWithTag("person-choice-self").performClick()
        compose.onNodeWithText("每天3次").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalTime.of(8, 0), LocalTime.NOON, LocalTime.of(18, 0)), saved!!.times) }
    }

    @Test fun medicineCabinetOpensPrefilledEditorAndSavesSamePausedPlan() {
        var saved: MedicationPlan? = null
        val original = MedicationPlan(name = "测试药", dose = "1片", kind = ScheduleKind.DAILY,
            times = listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)), start = LocalDate.now(), enabled = false)
        show {
            var editing by remember { mutableStateOf(false) }
            MaterialTheme {
                PlansScreen(Snapshot(plans = listOf(original), loaded = true), {}, {}, {}, { editing = true })
                if (editing) PlanEditor(null, false, null, { editing = false }, { saved = it }, existing = original)
            }
        }
        compose.onNodeWithText("编辑计划").performScrollTo().performClick()
        compose.onNodeWithText("编辑用药计划").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("药名")).assertTextContains("测试药")
        compose.onNodeWithText("确认并保存计划").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("已确认的提醒钟点")).performScrollTo().performTextReplacement("09:00,19:00")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.runOnIdle {
            assertEquals(original.id, saved!!.id)
            assertFalse(saved!!.enabled)
            assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(19, 0)), saved!!.times)
        }
    }
    @Test fun mcpDraftRequiresExplicitPersonEvenWhenCabinetIsFiltered() {
        var saved: MedicationPlan? = null
        val father = MedicationPerson(name = "爸爸")
        val draft = InstructionDraft("测试药", "1片", "每天2次", "", "", "测试药，每次1片，每天2次。", emptyList())
        show { MaterialTheme { PlanEditor(draft, false, null, {}, { saved = it },
            people = listOf(MedicationPerson.self(), father), initialPersonId = father.id) } }
        compose.onNodeWithTag("person-selector").assertTextContains("请选择用药人")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.onNodeWithText("请先选择这份计划的用药人").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("person-selector").performScrollTo().performClick()
        compose.onNodeWithTag("person-choice-${father.id}").performClick()
        compose.onNodeWithText("确认并保存计划").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("确认并保存计划").performClick()
        compose.runOnIdle { assertEquals(father.id, saved!!.personId); assertEquals("爸爸", saved!!.personName) }
    }

}
