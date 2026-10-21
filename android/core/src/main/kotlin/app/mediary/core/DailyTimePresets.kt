package app.mediary.core

import java.time.LocalTime

enum class DailyTimePreset(val count: Int, val clockText: String) {
    TWICE(2, "08:00, 18:00"),
    THREE_TIMES(3, "08:00, 12:00, 18:00");

    val times: List<LocalTime> get() = clockText.split(", ").map(LocalTime::parse)
}

/** 用户选择的日常钟点偏好，不代表医嘱要求，也不能用于替换固定小时间隔。 */
object DailyTimePresets {
    fun forDraft(frequency: String, source: String): DailyTimePreset? {
        val text = frequency.filterNot(Char::isWhitespace)
        val match = Regex("(?:每日|每天|一天|一日)([2二两3三])次[。.]?").matchEntire(text) ?: return null
        // 明确钟点、时段、按需或间隔要求不能被预设覆盖；饭前后说明仍原样保留供核对。
        if (Regex("[0-9０-９][:：][0-9０-９]|[0-9０-９零〇一二两三四五六七八九十]+[点時时]|小时|小時|按需|必要时|必要時|最多|至少|睡前|早|晚|午").containsMatchIn(source)) return null
        return when (match.groupValues[1]) {
            "2", "二", "两" -> DailyTimePreset.TWICE
            else -> DailyTimePreset.THREE_TIMES
        }
    }
}
