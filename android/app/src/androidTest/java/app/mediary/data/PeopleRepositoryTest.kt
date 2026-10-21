package app.mediary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.mediary.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class PeopleRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var repository: MediaryRepository? = null
    private val father = MedicationPerson(name = "爸爸")
    private fun open() = MediaryRepository(context, databaseName).also { repository = it; it.refresh() }
    private fun plan(person: MedicationPerson = MedicationPerson.self()) = MedicationPlan(name = "测试药", dose = "1片",
        times = listOf(LocalTime.of(8, 0)), start = LocalDate.of(2026, 10, 21), personId = person.id, personName = person.name)
    private val time = Instant.parse("2026-10-20T00:00:00Z")
    private fun event(plan: MedicationPlan) = DoseEvent(key = Occurrence(plan, time).key, planId = plan.id, name = plan.name, dose = plan.dose,
        plannedAt = time, actualAt = time, action = DoseAction.TAKEN, personId = plan.personId, personName = plan.personName)

    @Before fun setUp() { context = InstrumentationRegistry.getInstrumentation().targetContext; databaseName = "people-test-${UUID.randomUUID()}.db" }
    @After fun tearDown() { repository?.close(); context.deleteDatabase(databaseName) }

    @Test fun sameMedicineCanBelongToDifferentPeopleButNotDuplicateForOnePerson() {
        val repo = open(); repo.savePerson(father)
        repo.save(plan()); repo.save(plan(father))
        assertEquals(2, repo.snapshot.value.plans.size)
        assertThrows(IllegalArgumentException::class.java) { repo.save(plan(father)) }
        assertThrows(IllegalArgumentException::class.java) { repo.savePerson(MedicationPerson(name = father.name)) }
        assertEquals(1, repo.snapshot.value.forPerson(father.id).plans.size)
    }

    @Test fun renameUpdatesPlansButKeepsHistoricalPersonSnapshotAndFilterIdentity() {
        var repo = open(); repo.savePerson(father)
        val original = plan(father); repo.save(original); repo.record(event(original))
        val history = repo.snapshot.value.events
        repo.savePerson(father.copy(name = "老爸"))
        repo.close(); repo = open()
        assertEquals("老爸", repo.snapshot.value.plans.single().personName)
        assertEquals(history, repo.snapshot.value.events)
        assertEquals("爸爸", repo.snapshot.value.forPerson(father.id).events.single().personName)
        val exported = JSONObject(repo.exportJson())
        assertEquals(3, exported.getInt("schemaVersion"))
        assertEquals(2, exported.getJSONArray("people").length())
        assertEquals(father.id, exported.getJSONArray("events").getJSONObject(0).getString("personId"))
    }

    @Test fun reassignmentDoesNotCarryTakenStateAndOldActionsCannotRecordForNewPerson() {
        val repo = open(); repo.savePerson(father)
        val original = plan(); repo.save(original); repo.record(event(original))
        val history = repo.snapshot.value.events.single()
        val updated = original.copy(personId = father.id, personName = father.name)
        repo.edit(original, updated)
        assertNull(repo.snapshot.value.state(Occurrence(updated, time).key))
        assertEquals(history, repo.snapshot.value.forPerson(MedicationPerson.SELF_ID).events.single())
        assertTrue(repo.snapshot.value.forPerson(father.id).events.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repo.record(event(original).copy(key = "new-stale-key")) }
        assertThrows(IllegalArgumentException::class.java) { repo.snooze(Occurrence(original, time), Instant.now().plusSeconds(600)) }
        assertTrue(repo.record(event(updated)))
        assertTrue(repo.record(history.copy(id = 0, action = DoseAction.UNDONE)))
        assertEquals(DoseAction.TAKEN, repo.snapshot.value.state(Occurrence(updated, time).key)!!.action)
    }

    @Test fun versionTwoMigrationPreservesLegacyHistorySnoozesAndPlanIdentity() {
        val original = plan()
        val key = Occurrence(original, time).key
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName).apply { parentFile!!.mkdirs() }, null).use { db ->
            db.execSQL("CREATE TABLE plans (id TEXT PRIMARY KEY, content TEXT NOT NULL)")
            db.execSQL("CREATE TABLE dose_events (id INTEGER PRIMARY KEY AUTOINCREMENT, occurrence_key TEXT NOT NULL, plan_id TEXT NOT NULL, name TEXT NOT NULL, dose TEXT NOT NULL, planned_at INTEGER, actual_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL, action TEXT NOT NULL)")
            db.execSQL("CREATE TABLE snoozes (occurrence_key TEXT PRIMARY KEY, plan_id TEXT NOT NULL, planned_at INTEGER NOT NULL, remind_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE inbox_drafts (id TEXT PRIMARY KEY, account TEXT NOT NULL, content TEXT NOT NULL, plan_id TEXT, receipt_pending INTEGER NOT NULL DEFAULT 0)")
            val oldJson = MediaryRepository.encodePlan(original).apply { remove("personId"); remove("personName") }
            db.execSQL("INSERT INTO plans VALUES (?,?)", arrayOf(original.id, oldJson.toString()))
            db.execSQL("INSERT INTO dose_events VALUES (1,?,?,?,?,?,?,?,?)", arrayOf<Any>(key, original.id, original.name, original.dose, time.toEpochMilli(), time.toEpochMilli(), time.toEpochMilli(), "TAKEN"))
            db.execSQL("INSERT INTO snoozes VALUES (?,?,?,?)", arrayOf<Any>(key, original.id, time.toEpochMilli(), time.plusSeconds(600).toEpochMilli()))
            db.version = 2
        }
        val repo = open()
        assertEquals(listOf(MedicationPerson.self()), repo.snapshot.value.people)
        assertEquals(original, repo.snapshot.value.plans.single())
        assertEquals("我", repo.snapshot.value.events.single().personName)
        assertEquals(MedicationPerson.SELF_ID, repo.snapshot.value.events.single().personId)
        assertEquals(DoseAction.TAKEN, repo.snapshot.value.state(key)!!.action)
        assertEquals(key, repo.snapshot.value.snoozes.single().key)
        repo.savePerson(MedicationPerson.self().copy(name = "本人"))
        assertEquals("本人", repo.snapshot.value.plans.single().personName)
        assertEquals("我", repo.snapshot.value.events.single().personName)
    }
}
