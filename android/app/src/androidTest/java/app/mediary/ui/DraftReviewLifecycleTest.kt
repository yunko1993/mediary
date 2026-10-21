package app.mediary.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.mediary.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 在真实入口反复打开、关闭核对窗口；只读已有草稿，不保存或启用用户计划。 */
class DraftReviewLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun existingDraftCanBeReviewedAndDismissedRepeatedly() {
        try {
            compose.onNodeWithContentDescription("ChatGPT 草稿收件箱").performClick()
            compose.waitForIdle()
            // 没有待核对草稿时不制造真实医疗数据，交由模拟草稿测试覆盖。
            assumeTrue(compose.onAllNodesWithText("核对并设置提醒").fetchSemanticsNodes().isNotEmpty())
            repeat(5) {
                compose.onAllNodesWithText("核对并设置提醒")[0].performScrollTo().performClick()
                compose.onNodeWithText("核对医嘱草稿").assertIsDisplayed()
                compose.onNodeWithContentDescription("关闭").performClick()
                compose.onNodeWithText("医嘱草稿收件箱").assertIsDisplayed()
            }
        } catch (skip: org.junit.AssumptionViolatedException) { throw skip }
        catch (_: Throwable) {
            // Compose 默认失败信息会展开整棵语义树；真实草稿正文不能进入测试日志。
            throw AssertionError("Draft review lifecycle failed; private UI content withheld")
        }
    }
}
