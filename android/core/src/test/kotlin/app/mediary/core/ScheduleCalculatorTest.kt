package app.mediary.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ScheduleCalculatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 19)
    private fun plan() = MedicationPlan(name = "测试药物", dose = "遵医嘱测试单位", start = day,
        times = listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)))

    @Test fun dailyTimesRespectTimezoneAndHaveStableKeys() {
        val doses = ScheduleCalculator.onDate(plan(), day, zone)
        assertEquals(listOf(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-19T10:00:00Z")), doses.map { it.plannedAt })
        assertNotEquals(doses[0].key, doses[1].key)
    }

    @Test fun courseEndIsInclusiveAndNextDayIsEmpty() {
        val plan = plan().copy(end = day)
        assertEquals(2, ScheduleCalculator.onDate(plan, day, zone).size)
        assertTrue(ScheduleCalculator.onDate(plan, day.plusDays(1), zone).isEmpty())
        assertNull(ScheduleCalculator.next(plan, Instant.parse("2026-10-19T11:00:00Z"), zone))
    }

    @Test fun intervalCrossesMidnightWithoutResettingAtBreakfast() {
        val plan = plan().copy(kind = ScheduleKind.INTERVAL, intervalHours = 8,
            anchor = day.atTime(22, 0).atZone(zone).toInstant())
        val nextDay = ScheduleCalculator.onDate(plan, day.plusDays(1), zone)
        assertEquals(listOf(6, 14, 22), nextDay.map { it.plannedAt.atZone(zone).hour })
        assertEquals(1, ScheduleCalculator.onDate(plan, day, zone).size)
    }

    @Test fun longIntervalsCanSkipMultipleDays() {
        val plan = plan().copy(kind = ScheduleKind.INTERVAL, intervalHours = 168,
            anchor = day.atTime(8, 0).atZone(zone).toInstant())
        assertEquals(day.plusDays(7), ScheduleCalculator.next(plan, plan.anchor!!, zone)!!.plannedAt.atZone(zone).toLocalDate())
    }

    @Test fun nextNeverCatchesUpPastDoses() {
        val after = day.atTime(12, 0).atZone(zone).toInstant()
        assertEquals(18, ScheduleCalculator.next(plan(), after, zone)!!.plannedAt.atZone(zone).hour)
    }

    @Test fun futureCourseBeginsOnItsStartDate() {
        val future = plan().copy(start = day.plusMonths(1))
        assertEquals(future.start, ScheduleCalculator.next(future, day.atStartOfDay(zone).toInstant(), zone)!!.plannedAt.atZone(zone).toLocalDate())
    }

    @Test fun pausedAndAsNeededPlansDoNotScheduleReminders() {
        assertTrue(ScheduleCalculator.onDate(plan().copy(enabled = false), day, zone).isEmpty())
        assertNull(ScheduleCalculator.next(plan().copy(kind = ScheduleKind.AS_NEEDED), Instant.now(), zone))
    }

    @Test fun daylightSavingKeepsDailyLocalTimeButIntervalKeepsElapsedHours() {
        val dstZone = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2026, 3, 8)
        val daily = plan().copy(start = dstDay.minusDays(1), times = listOf(LocalTime.of(8, 0)))
        assertEquals(8, ScheduleCalculator.onDate(daily, dstDay, dstZone).single().plannedAt.atZone(dstZone).hour)
        val interval = daily.copy(kind = ScheduleKind.INTERVAL, intervalHours = 24,
            anchor = dstDay.minusDays(1).atTime(8, 0).atZone(dstZone).toInstant())
        assertEquals(9, ScheduleCalculator.onDate(interval, dstDay, dstZone).single().plannedAt.atZone(dstZone).hour)
    }

    @Test(expected = IllegalArgumentException::class) fun doseMustIncludeConfirmedText() { plan().copy(dose = "").validate() }
    @Test(expected = IllegalArgumentException::class) fun duplicateTimesAreRejected() { plan().copy(times = listOf(LocalTime.NOON, LocalTime.NOON)).validate() }
    @Test(expected = IllegalArgumentException::class) fun reversedCourseIsRejected() { plan().copy(end = day.minusDays(1)).validate() }
}
