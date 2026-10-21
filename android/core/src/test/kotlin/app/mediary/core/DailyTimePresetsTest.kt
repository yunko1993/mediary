package app.mediary.core

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class DailyTimePresetsTest {
    @Test fun explicitDailyCountsUseUserClockPreferences() {
        assertEquals(DailyTimePreset.TWICE, DailyTimePresets.forDraft("每天 2 次", "测试药，每日2次。"))
        assertEquals(DailyTimePreset.THREE_TIMES, DailyTimePresets.forDraft("一日三次", "测试药，一日三次。"))
        assertEquals(DailyTimePreset.TWICE, DailyTimePresets.forDraft("每天2次", "测试药，每天2次，饭后服用。"))
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)), DailyTimePreset.TWICE.times)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.NOON, LocalTime.of(18, 0)), DailyTimePreset.THREE_TIMES.times)
    }

    @Test fun intervalAsNeededRangesAndLimitsNeverBecomeDailyPresets() {
        listOf("每8小时", "按需", "每日最多3次", "每日2-3次", "必要时每天3次", "每天3次，饭后", "").forEach {
            assertNull(DailyTimePresets.forDraft(it, "测试药"))
        }
    }

    @Test fun explicitTimesAndConditionsAreNotReplacedByPreferences() {
        listOf("每天2次，09:00、19:30", "每天2次，九点服用", "每天2次，睡前", "每天2次，早晚", "每天2次，每8小时", "每天最多2次").forEach {
            assertNull(DailyTimePresets.forDraft("每天2次", it))
        }
    }
}
