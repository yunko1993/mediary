package app.mediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mediary.core.*
import app.mediary.data.ImportDraft
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Composable
fun PlanEditor(draft: ImportDraft?, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (MedicationPlan) -> Unit) {
    var name by remember { mutableStateOf(draft?.name.orEmpty()) }
    var dose by remember { mutableStateOf(draft?.dose.orEmpty()) }
    var instructions by remember { mutableStateOf(draft?.instructions.orEmpty()) }
    var source by remember { mutableStateOf(draft?.source.orEmpty()) }
    var kind by remember { mutableStateOf(ScheduleKind.DAILY) }
    var times by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var firstTime by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.96f).padding(horizontal = 10.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp), color = Paper) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (draft == null) "添加用药计划" else "核对医嘱草稿", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    draft?.let {
                        SoftCard {
                            Badge("GPT 整理 · 待你核对")
                            Text("频次原文：${it.frequency.ifBlank { "未提供" }}", fontSize = 13.sp)
                            Text("疗程原文：${it.duration.ifBlank { "未提供" }}", fontSize = 13.sp)
                            if (it.missing.isNotEmpty()) Text("需补充确认：${it.missing.joinToString("、")}", color = Amber, fontSize = 12.sp)
                            Text("提醒钟点和日期需要你按已确认医嘱填写，不会由 GPT 自动猜测。", color = Muted, fontSize = 12.sp)
                        }
                    }
                    OutlinedTextField(name, { name = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("药名") }, singleLine = true)
                    OutlinedTextField(dose, { dose = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("每次剂量（包含单位）") }, placeholder = { Text("照医嘱填写，不自动换算") }, singleLine = true)
                    OutlinedTextField(instructions, { instructions = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("饭前后要求 / 注意事项") }, minLines = 2)
                    Text("提醒方式", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(ScheduleKind.DAILY to "每天定时", ScheduleKind.INTERVAL to "按间隔", ScheduleKind.AS_NEEDED to "按需").forEach { (value, title) ->
                            FilterChip(selected = kind == value, onClick = { kind = value; confirmed = false }, label = { Text(title) })
                        }
                    }
                    when (kind) {
                        ScheduleKind.DAILY -> {
                            OutlinedTextField(times, { times = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("已确认的提醒钟点") }, placeholder = { Text("08:00, 18:00") }, supportingText = { Text("多个钟点用逗号分隔；每日几次不自动等同每隔几小时。") })
                        }
                        ScheduleKind.INTERVAL -> {
                            OutlinedTextField(hours, { hours = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("医嘱明确要求的间隔小时数") }, singleLine = true)
                            OutlinedTextField(firstTime, { firstTime = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("开始日期的首次时间") }, placeholder = { Text("08:00") }, singleLine = true)
                            Text("间隔将跨午夜连续计算，不会每天重置。", color = Muted, fontSize = 12.sp)
                        }
                        ScheduleKind.AS_NEEDED -> Text("按需计划只提供手动记录，不主动提醒服用。", color = Muted, fontSize = 12.sp)
                    }
                    OutlinedTextField(start, { start = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("开始日期 YYYY-MM-DD") }, singleLine = true)
                    OutlinedTextField(end, { end = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("结束日期（可留空）") }, supportingText = { Text("包含结束当天；未明确疗程时请先核实。") }, singleLine = true)
                    OutlinedTextField(source, { source = it; confirmed = false }, modifier = Modifier.fillMaxWidth(), label = { Text("医嘱原文 / 来源备注") }, minLines = 3)
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(confirmed, { confirmed = it })
                        Text("我已核对药名、每次剂量、频次、时间和疗程，内容符合医生或药师的指示。", fontSize = 12.sp, color = Ink, modifier = Modifier.padding(top = 12.dp))
                    }
                    (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
                Button(enabled = confirmed && !saving, onClick = {
                    error = null
                    runCatching {
                        val startDate = LocalDate.parse(start.trim(), dateFormatter)
                        val endDate = end.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it, dateFormatter) }
                        val reminderTimes = if (kind == ScheduleKind.DAILY) times.replace('，', ',').split(',').map { LocalTime.parse(it.trim(), timeFormatter) }.sorted() else emptyList()
                        val anchor = if (kind == ScheduleKind.INTERVAL) startDate.atTime(LocalTime.parse(firstTime.trim(), timeFormatter)).atZone(ZoneId.systemDefault()).toInstant() else null
                        onSave(MedicationPlan(name = name.trim(), dose = dose.trim(), instructions = instructions.trim(), source = source.trim(), kind = kind,
                            times = reminderTimes, intervalHours = hours.toIntOrNull(), anchor = anchor, start = startDate, end = endDate).validate())
                    }.onFailure { error = if (it is java.time.DateTimeException) "请检查日期和时间格式，时间使用 24 小时制 HH:mm" else it.message ?: "请检查填写内容" }
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 15.dp).height(50.dp)) { Text(if (saving) "正在保存…" else "确认并保存计划") }
            }
        }
    }
}
