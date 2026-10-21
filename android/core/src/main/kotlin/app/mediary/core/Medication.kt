package app.mediary.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class ScheduleKind { DAILY, INTERVAL, AS_NEEDED }
enum class DoseAction { TAKEN, SKIPPED, UNDONE }

data class MedicationPerson(val id: String = UUID.randomUUID().toString(), val name: String) {
    fun validate(): MedicationPerson {
        require(id == SELF_ID || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "用药人标识无效" }
        require(name.isNotBlank() && name == name.trim() && name.length <= 24 && name.none { it.isISOControl() }) { "请填写 1–24 字的用药人称呼" }
        return this
    }
    companion object {
        const val SELF_ID = "self"
        fun self() = MedicationPerson(SELF_ID, "我")
    }
}

data class MedicationPlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dose: String,
    val instructions: String = "",
    val source: String = "",
    val kind: ScheduleKind = ScheduleKind.DAILY,
    val times: List<LocalTime> = emptyList(),
    val intervalHours: Int? = null,
    val anchor: Instant? = null,
    val start: LocalDate,
    val end: LocalDate? = null,
    val enabled: Boolean = true,
    val personId: String = MedicationPerson.SELF_ID,
    val personName: String = "我",
) {
    fun validate(): MedicationPlan {
        MedicationPerson(personId, personName).validate()
        require(name.isNotBlank() && name.length <= 120) { "请填写药名（最多 120 字）" }
        require(dose.isNotBlank() && dose.length <= 120) { "请按医嘱填写每次剂量及单位" }
        require(end == null || !end.isBefore(start)) { "结束日期不能早于开始日期" }
        when (kind) {
            ScheduleKind.DAILY -> require(times.isNotEmpty() && times.size <= 12 && times.distinct().size == times.size) { "请设置 1–12 个不重复的提醒时间" }
            ScheduleKind.INTERVAL -> require(intervalHours in 1..168 && anchor != null) { "请设置间隔小时数（1–168）和首次时间" }
            ScheduleKind.AS_NEEDED -> Unit
        }
        return this
    }
}

data class Occurrence(val plan: MedicationPlan, val plannedAt: Instant) {
    // 默认用药人沿用旧键，保证升级后的已服状态和稍后提醒可读；换人时隔离剂次状态。
    val key: String get() = "${plan.id}|${plannedAt.toEpochMilli()}" +
        if (plan.personId == MedicationPerson.SELF_ID) "" else "|person:${plan.personId}"
}

/** 每次操作保留药物快照；更正通过 UNDONE 追加事件，不覆盖历史事实。 */
data class DoseEvent(
    val id: Long = 0,
    val key: String,
    val planId: String,
    val name: String,
    val dose: String,
    val plannedAt: Instant?,
    val actualAt: Instant,
    val recordedAt: Instant = Instant.now(),
    val action: DoseAction,
    val personId: String = MedicationPerson.SELF_ID,
    val personName: String = "我",
)

object ScheduleCalculator {
    /** 跳时当天不存在的本地钟点必须由用户核对，不能自动平移成另一剂的实际时刻。 */
    fun missingDailyTimes(plan: MedicationPlan, date: LocalDate, zone: ZoneId): List<LocalTime> {
        if (!plan.enabled || plan.kind != ScheduleKind.DAILY || date < plan.start || (plan.end != null && date > plan.end)) return emptyList()
        return plan.times.filter { zone.rules.getValidOffsets(date.atTime(it)).isEmpty() }.sorted()
    }

    fun onDate(plan: MedicationPlan, date: LocalDate, zone: ZoneId): List<Occurrence> {
        if (!plan.enabled || date < plan.start || (plan.end != null && date > plan.end)) return emptyList()
        return when (plan.kind) {
            ScheduleKind.AS_NEEDED -> emptyList()
            ScheduleKind.DAILY -> {
                val missing = missingDailyTimes(plan, date, zone).toSet()
                plan.times.filterNot { it in missing }.map { Occurrence(plan, date.atTime(it).atZone(zone).toInstant()) }.sortedBy { it.plannedAt }
            }
            ScheduleKind.INTERVAL -> {
                val anchor = plan.anchor ?: return emptyList()
                val step = (plan.intervalHours ?: return emptyList()) * 3600L
                val dayStart = date.atStartOfDay(zone).toInstant().epochSecond
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().epochSecond
                // 间隔按真实经过时间计算，不能把“每 8 小时”改成每天三个固定钟点。
                val offset = maxOf(0, ceilDiv(dayStart - anchor.epochSecond, step))
                var timestamp = anchor.epochSecond + offset * step
                buildList {
                    while (timestamp < dayEnd) {
                        add(Occurrence(plan, Instant.ofEpochSecond(timestamp)))
                        timestamp += step
                    }
                }
            }
        }
    }

    /** 只安排未来提醒；重启或时间更改后不会集中补发过去的剂次。 */
    fun next(plan: MedicationPlan, after: Instant, zone: ZoneId): Occurrence? {
        if (!plan.enabled || plan.kind == ScheduleKind.AS_NEEDED) return null
        val firstDate = maxOf(after.atZone(zone).toLocalDate(), plan.start)
        for (offset in 0L..2L) {
            val next = onDate(plan, firstDate.plusDays(offset), zone).firstOrNull { it.plannedAt > after }
            if (next != null) return next
        }
        // 长间隔可能跨过多天，直接从锚点推算下一次，再检查疗程边界。
        if (plan.kind == ScheduleKind.INTERVAL) {
            val anchor = plan.anchor ?: return null
            val step = (plan.intervalHours ?: return null) * 3600L
            val lowerBound = maxOf(after, plan.start.atStartOfDay(zone).toInstant().minusNanos(1))
            val count = if (lowerBound < anchor) 0 else ((lowerBound.epochSecond - anchor.epochSecond) / step + 1)
            val candidate = anchor.plusSeconds(count * step)
            val date = candidate.atZone(zone).toLocalDate()
            if (date >= plan.start && (plan.end == null || date <= plan.end)) return Occurrence(plan, candidate)
        }
        return null
    }

    private fun ceilDiv(numerator: Long, denominator: Long): Long = -Math.floorDiv(-numerator, denominator)
}
