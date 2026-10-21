package app.mediary.reminders

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.mediary.core.*
import app.mediary.data.MediaryRepository
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SnoozeRecoveryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repo: MediaryRepository
    private lateinit var scheduler: ReminderScheduler
    private lateinit var occurrence: Occurrence

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "snooze-test-${UUID.randomUUID()}.db"
        repo = MediaryRepository(context, databaseName); repo.refresh()
        val time = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MINUTES)
        val local = time.atZone(ZoneId.systemDefault())
        val plan = MedicationPlan(name = "临时提醒测试", dose = "测试单位", times = listOf(local.toLocalTime()), start = local.toLocalDate())
        repo.save(plan); occurrence = Occurrence(plan, time)
        scheduler = ReminderScheduler(context, repo)
    }

    @After fun tearDown() {
        scheduler.cancelForEdit(occurrence.plan, repo.snapshot.value.snoozes)
        repo.close(); context.deleteDatabase(databaseName)
    }

    @Test fun coldStartReschedulingKeepsAnAlreadyDueSnoozeUntilTheReceiverConsumesItOnce() {
        repo.snooze(occurrence, Instant.now().minusSeconds(1))
        val due = repo.snapshot.value.snoozes.single().remindAt
        // 重现 Application 初始化先执行、接收器随后执行的次序，只使用临时数据库。
        scheduler.reschedule(); scheduler.reschedule()
        assertEquals(due, repo.snapshot.value.snoozes.single().remindAt)
        assertTrue(repo.consumeSnooze(occurrence, Instant.now(), due))
        assertFalse(repo.consumeSnooze(occurrence, Instant.now(), due))
    }

    @Test fun rebootRecoveryDropsExpiredSnoozesInsteadOfCatchingUp() {
        repo.snooze(occurrence, Instant.now().minusSeconds(1))
        scheduler.reschedule(discardExpiredSnoozes = true)
        assertTrue(repo.snapshot.value.snoozes.isEmpty())
        assertFalse(repo.consumeSnooze(occurrence, Instant.now()))
    }

    @Test fun anOldBroadcastCannotConsumeAReplacementSnooze() {
        repo.snooze(occurrence, Instant.now().minusSeconds(3))
        val old = repo.snapshot.value.snoozes.single().remindAt
        repo.snooze(occurrence, Instant.now().minusSeconds(1))
        val latest = repo.snapshot.value.snoozes.single().remindAt
        assertFalse(repo.consumeSnooze(occurrence, Instant.now(), old))
        assertEquals(latest, repo.snapshot.value.snoozes.single().remindAt)
        assertTrue(repo.consumeSnooze(occurrence, Instant.now(), latest))
    }

    @Test fun aFutureSnoozeCannotBeConsumedEarly() {
        repo.snooze(occurrence, Instant.now().plusSeconds(600))
        val due = repo.snapshot.value.snoozes.single().remindAt
        assertFalse(repo.consumeSnooze(occurrence, Instant.now(), due))
        scheduler.reschedule(discardExpiredSnoozes = true)
        assertEquals(due, repo.snapshot.value.snoozes.single().remindAt)
    }

    @Test fun anInvalidOriginalClockIsClearedInsteadOfKeptAsAnInFlightSnooze() {
        val invalid = occurrence.copy(plannedAt = occurrence.plannedAt.plusSeconds(1))
        repo.snooze(invalid, Instant.now().minusSeconds(1))
        scheduler.reschedule()
        assertTrue(repo.snapshot.value.snoozes.isEmpty())
    }
}
