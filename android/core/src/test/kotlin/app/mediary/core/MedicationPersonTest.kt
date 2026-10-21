package app.mediary.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MedicationPersonTest {
    private val father = MedicationPerson("11111111-1111-4111-8111-111111111111", "爸爸")
    private val plan = MedicationPlan(id = "original-plan", name = "测试药", dose = "1片", start = LocalDate.of(2026, 10, 21), times = listOf(LocalTime.of(8, 0)))
    private val time = Instant.parse("2026-10-21T00:00:00Z")

    @Test fun defaultPersonKeepsLegacyOccurrenceKeysAndReassignmentSeparatesState() {
        val original = Occurrence(plan, time)
        val moved = Occurrence(plan.copy(personId = father.id, personName = father.name), time)
        assertEquals("original-plan|${time.toEpochMilli()}", original.key)
        assertNotEquals(original.key, moved.key)
        assertEquals(moved.key, Occurrence(moved.plan.copy(personName = "老爸"), time).key)
    }

    @Test fun editingRetainsPersonUnlessAnotherPersonIsExplicitlyChosen() {
        val original = plan.copy(personId = father.id, personName = father.name, enabled = false)
        val form = PlanInput(name = plan.name, dose = "2片", times = "08:00", start = plan.start.toString())
        val updated = form.toPlan(ZoneId.of("Asia/Shanghai"), original)
        assertEquals(father.id, updated.personId); assertEquals(father.name, updated.personName)
        val reassigned = form.toPlan(ZoneId.of("Asia/Shanghai"), original, MedicationPerson.self())
        assertEquals(original.id, reassigned.id); assertFalse(reassigned.enabled)
        assertEquals(MedicationPerson.SELF_ID, reassigned.personId)
    }

    @Test fun namesAndIdentifiersCannotBeEmptyOrContainControlCharacters() {
        father.validate(); MedicationPerson.self().validate()
        listOf("", " ", "爸爸\n", "a".repeat(25)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { father.copy(name = name).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { father.copy(id = "unsafe|identifier").validate() }
    }
}
