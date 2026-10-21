package app.mediary.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PlanInputTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun input() = PlanInput(name = "测试药", dose = "1片", start = "2026-10-21")
    private fun error(input: PlanInput, field: PlanInputField): PlanInputException {
        val error = assertThrows(PlanInputException::class.java) { input.toPlan(zone) }
        assertEquals(field, error.field)
        return error
    }

    @Test fun missingDailyClockPointsToTimeInsteadOfDate() {
        assertTrue(error(input(), PlanInputField.TIMES).message!!.contains("请添加至少一个提醒钟点"))
    }

    @Test fun explicitClockVariantsNormalizeWithoutGuessing() {
        val plan = input().copy(times = "18:30，8：00").toPlan(zone)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)), plan.times)
        assertEquals(LocalDate.of(2026, 10, 21), plan.start)
    }

    @Test fun invalidClockAndDuplicateClockCannotCreatePlan() {
        listOf("24:00", "08:60", "08:00,", "每日三次", "饭后", "08:00,8：00").forEach {
            error(input().copy(times = it), PlanInputField.TIMES)
        }
    }

    @Test fun invalidDatesPointToTheirOwnFieldWithoutRollover() {
        error(input().copy(start = ""), PlanInputField.START)
        error(input().copy(start = "2026-02-30"), PlanInputField.START)
        error(input().copy(end = "10-22"), PlanInputField.END)
        error(input().copy(end = "2026-10-20"), PlanInputField.END)
    }

    @Test fun intervalNeedsBothExplicitHoursAndFirstTime() {
        val interval = input().copy(kind = ScheduleKind.INTERVAL)
        error(interval, PlanInputField.INTERVAL_HOURS)
        error(interval.copy(hours = "8"), PlanInputField.FIRST_TIME)
        val plan = interval.copy(hours = "8", firstTime = "22:00", times = "不是每日定时").toPlan(zone)
        assertTrue(plan.times.isEmpty())
        assertEquals(listOf(6, 14, 22), ScheduleCalculator.onDate(plan, plan.start.plusDays(1), zone).map { it.plannedAt.atZone(zone).hour })
    }

    @Test fun asNeededDoesNotRequireHiddenReminderFields() {
        val plan = input().copy(kind = ScheduleKind.AS_NEEDED, times = "无效", hours = "无效", firstTime = "无效").toPlan(zone)
        assertTrue(plan.times.isEmpty()); assertNull(plan.intervalHours); assertNull(plan.anchor)
    }

    @Test fun editingPreservesIdentityAndPausedStateAcrossScheduleChanges() {
        val old = input().copy(times = "08:00,18:00").toPlan(zone).copy(enabled = false)
        val updated = input().copy(name = "测试药乙", dose = "2片", kind = ScheduleKind.AS_NEEDED).toPlan(zone, old)
        assertEquals(old.id, updated.id)
        assertFalse(updated.enabled)
        assertEquals(ScheduleKind.AS_NEEDED, updated.kind)
        assertTrue(updated.times.isEmpty())
    }

    @Test fun unchangedIntervalClockRetainsOriginalInstantAfterTimeZoneChange() {
        val old = input().copy(kind = ScheduleKind.INTERVAL, hours = "8", firstTime = "22:00").toPlan(zone)
        val changedZone = ZoneId.of("Pacific/Auckland")
        val clock = old.anchor!!.atZone(changedZone).toLocalTime().toString()
        val form = input().copy(kind = ScheduleKind.INTERVAL, hours = "8", firstTime = clock, instructions = "核对后的备注")
        assertEquals(old.anchor, form.toPlan(changedZone, old).anchor)
        assertEquals(Instant.parse("2026-10-20T19:30:00Z"), form.copy(firstTime = "08:30").toPlan(changedZone, old).anchor)
    }
}
