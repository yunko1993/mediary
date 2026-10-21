package app.mediary.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

enum class PlanInputField { NAME, DOSE, TIMES, INTERVAL_HOURS, FIRST_TIME, START, END }

class PlanInputException(val field: PlanInputField, message: String) : IllegalArgumentException(message)

/** 表单校验必须指出缺失字段；空钟点不能被误报成日期错误，也不能从频次推算。 */
data class PlanInput(
    val name: String,
    val dose: String,
    val instructions: String = "",
    val source: String = "",
    val kind: ScheduleKind = ScheduleKind.DAILY,
    val times: String = "",
    val hours: String = "",
    val firstTime: String = "",
    val start: String,
    val end: String = "",
) {
    fun toPlan(zone: ZoneId, original: MedicationPlan? = null): MedicationPlan {
        if (name.isBlank() || name.trim().length > 120) invalid(PlanInputField.NAME, "请填写药名（最多 120 字）")
        if (dose.isBlank() || dose.trim().length > 120) invalid(PlanInputField.DOSE, "请按医嘱填写每次剂量及单位（最多 120 字）")
        val startDate = date(start, PlanInputField.START, "开始日期")
        val endDate = end.trim().takeIf { it.isNotEmpty() }?.let { date(it, PlanInputField.END, "结束日期") }
        if (endDate != null && endDate < startDate) invalid(PlanInputField.END, "结束日期不能早于开始日期")

        // 只校验当前提醒方式的输入，切换为按需后不能被隐藏的钟点字段阻挡。
        val reminderTimes = if (kind == ScheduleKind.DAILY) {
            if (times.isBlank()) invalid(PlanInputField.TIMES, "请添加至少一个提醒钟点，可点击「选择钟点」")
            times.replace('，', ',').split(',').map { time(it, PlanInputField.TIMES, "提醒钟点") }.sorted().also {
                if (it.size > 12 || it.distinct().size != it.size) invalid(PlanInputField.TIMES, "请设置 1–12 个不重复的提醒钟点")
            }
        } else emptyList()
        val interval = if (kind == ScheduleKind.INTERVAL) hours.trim().toIntOrNull()?.takeIf { it in 1..168 }
            ?: invalid(PlanInputField.INTERVAL_HOURS, "请填写医嘱明确要求的间隔小时数（1–168）") else null
        val anchor = if (kind == ScheduleKind.INTERVAL) {
            val first = time(firstTime, PlanInputField.FIRST_TIME, "首次时间")
            // 只改剂量或备注时保留原 Instant，避免跨时区编辑把间隔锚点平移一天。
            val oldAnchor = original?.anchor
            if (original?.kind == ScheduleKind.INTERVAL && startDate == original.start && oldAnchor?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0) == first) oldAnchor
            else startDate.atTime(first).atZone(zone).toInstant()
        } else null
        val plan = MedicationPlan(name = name.trim(), dose = dose.trim(), instructions = instructions.trim(), source = source.trim(),
            kind = kind, times = reminderTimes, intervalHours = interval, anchor = anchor, start = startDate, end = endDate)
        return (if (original == null) plan else plan.copy(id = original.id, enabled = original.enabled)).validate()
    }

    private fun date(value: String, field: PlanInputField, title: String): LocalDate {
        if (value.isBlank()) invalid(field, "请填写$title，格式为 YYYY-MM-DD")
        return try { LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }
        catch (_: DateTimeParseException) { invalid(field, "请检查$title，格式为 YYYY-MM-DD，例如 2026-10-21") }
    }

    private fun time(value: String, field: PlanInputField, title: String): LocalTime {
        if (value.isBlank()) invalid(field, "请填写$title，使用 24 小时制，例如 08:00")
        // 接受单数字小时和中文冒号，不接受 24:00 自动进位或从自然语言猜测钟点。
        val formatter = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
        return try { LocalTime.parse(value.trim().replace('：', ':'), formatter) }
        catch (_: DateTimeParseException) { invalid(field, "请检查$title，使用 24 小时制，例如 08:00；多个钟点用逗号分隔") }
    }

    private fun invalid(field: PlanInputField, message: String): Nothing = throw PlanInputException(field, message)
}
