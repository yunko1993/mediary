package app.mediary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mediary.core.InstructionDraft
import app.mediary.core.MedicationPlan
import app.mediary.core.PlanInput
import app.mediary.core.ScheduleKind
import app.mediary.core.DoseEvent
import app.mediary.core.DoseAction
import app.mediary.core.Occurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxRepositoryTest {
    private lateinit var context: Context
    private lateinit var name: String
    private var repository: MediaryRepository? = null
    private val account = "https://mediary.example.invalid/mediary"
    private val source = "测试药甲，每次1片，按需记录。"
    private val item = InboxDraft("11111111-1111-4111-8111-111111111111", account, source,
        InstructionDraft("测试药甲", "每次1片", "按需记录", "", "", source, emptyList()), "2026-10-20T00:00:00Z")
    private fun plan() = MedicationPlan(name = "测试药甲", dose = "每次1片", kind = ScheduleKind.AS_NEEDED, start = LocalDate.now())

    @Before fun setUp() { context = InstrumentationRegistry.getInstrumentation().targetContext; name = "inbox-test-${UUID.randomUUID()}.db" }
    @After fun tearDown() { repository?.close(); context.deleteDatabase(name) }
    private fun open() = MediaryRepository(context, name).also { repository = it; it.refresh() }

    @Test fun receivingCreatesNoPlanAndOfflineSaveSurvivesRestartWithOneReceipt() {
        var repo = open()
        repo.mergeInbox(listOf(item)); repo.mergeInbox(listOf(item))
        assertTrue(repo.snapshot.value.plans.isEmpty()); assertEquals(1, repo.snapshot.value.inbox.size)
        val saved = plan(); repo.save(saved, item.key)
        assertThrows(IllegalStateException::class.java) { repo.save(saved, item.key) }
        assertEquals(1, repo.snapshot.value.plans.size)
        repo.close(); repo = open()
        assertEquals(saved.id, repo.snapshot.value.inbox.single().localPlanId)
        val pending = repo.pendingReceipts(account)
        assertEquals(1, pending.size); assertTrue(repo.pendingReceipts("https://other.example.invalid").isEmpty())
        repo.acknowledgeReceipts(pending); assertTrue(repo.pendingReceipts(account).isEmpty())
    }

    @Test fun confirmedDailyClockPersistsWithDraftAcrossRestart() {
        var repo = open()
        val source = "测试药甲，每次1片，每天08:00和18:00。"
        val daily = item.copy(sourceText = source, draft = item.draft.copy(frequency = "每天08:00和18:00", source = source))
        repo.mergeInbox(listOf(daily))
        val saved = PlanInput(name = daily.draft.name, dose = daily.draft.dose, times = "8：00，18:00", start = "2026-10-21").toPlan(ZoneId.of("Asia/Shanghai"))
        repo.save(saved, daily.key)
        repo.close(); repo = open()
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)), repo.snapshot.value.plans.single().times)
        assertEquals(saved.id, repo.snapshot.value.inbox.single().localPlanId)
        assertEquals(saved.id, repo.pendingReceipts(account).single().localPlanId)
    }

    @Test fun versionOneMigrationPreservesExistingPlanAndDoesNotCreateReminders() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile!!.mkdirs() }, null).use { db ->
            db.execSQL("CREATE TABLE plans (id TEXT PRIMARY KEY, content TEXT NOT NULL)")
            db.execSQL("CREATE TABLE dose_events (id INTEGER PRIMARY KEY AUTOINCREMENT, occurrence_key TEXT NOT NULL, plan_id TEXT NOT NULL, name TEXT NOT NULL, dose TEXT NOT NULL, planned_at INTEGER, actual_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL, action TEXT NOT NULL)")
            db.execSQL("CREATE TABLE snoozes (occurrence_key TEXT PRIMARY KEY, plan_id TEXT NOT NULL, planned_at INTEGER NOT NULL, remind_at INTEGER NOT NULL)")
            val saved = plan()
            db.execSQL("INSERT INTO plans VALUES (?,?)", arrayOf(saved.id, MediaryRepository.encodePlan(saved).toString()))
            db.version = 1
        }
        val repo = open()
        assertEquals(1, repo.snapshot.value.plans.size); assertTrue(repo.snapshot.value.inbox.isEmpty())
        repo.mergeInbox(listOf(item)); assertEquals(1, repo.snapshot.value.plans.size)
    }

    @Test fun editRetainsHistoryAndInboxLinkButInvalidatesOldSnoozes() {
        var repo = open()
        repo.mergeInbox(listOf(item))
        val original = plan().copy(kind = ScheduleKind.DAILY, times = listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)))
        repo.save(original, item.key)
        val planned = Instant.now().minusSeconds(3600)
        repo.record(DoseEvent(key = "${original.id}|$planned", planId = original.id, name = original.name,
            dose = original.dose, plannedAt = planned, actualAt = planned, action = DoseAction.TAKEN))
        val history = repo.snapshot.value.events
        repo.snooze(Occurrence(original, planned.plusSeconds(1800)), Instant.now().plusSeconds(600))
        val updated = original.copy(name = "测试药乙", dose = "每次2片", times = listOf(LocalTime.of(9, 0)))
        assertEquals(1, repo.edit(original, updated).size)
        repo.close(); repo = open()
        assertEquals(listOf(updated), repo.snapshot.value.plans)
        assertEquals(history, repo.snapshot.value.events)
        assertEquals(original.id, repo.snapshot.value.inbox.single().localPlanId)
        assertEquals(original.id, repo.pendingReceipts(account).single().localPlanId)
        assertTrue(repo.snapshot.value.snoozes.isEmpty())
    }

    @Test fun staleEditsAndDuplicateNamesCannotOverwriteNewerPlans() {
        val repo = open()
        val original = plan(); repo.save(original)
        val changed = original.copy(dose = "每次2片"); repo.edit(original, changed)
        assertThrows(IllegalStateException::class.java) { repo.edit(original, original.copy(dose = "每次3片")) }
        val other = plan().copy(name = "测试药乙"); repo.save(other)
        assertThrows(IllegalArgumentException::class.java) { repo.edit(changed, changed.copy(name = other.name)) }
        assertEquals(changed, repo.snapshot.value.plans.first { it.id == original.id })
    }
}
