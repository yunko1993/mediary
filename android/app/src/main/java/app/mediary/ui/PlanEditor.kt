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
import androidx.compose.ui.platform.testTag
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditor(draft: ImportDraft?, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (MedicationPlan) -> Unit) {
    val suggested = remember(draft) { DailyTimePresets.forDraft(draft?.frequency.orEmpty(), draft?.source.orEmpty()) }
    var name by remember { mutableStateOf(draft?.name.orEmpty()) }
    var dose by remember { mutableStateOf(draft?.dose.orEmpty()) }
    var instructions by remember { mutableStateOf(draft?.instructions.orEmpty()) }
    var source by remember { mutableStateOf(draft?.source.orEmpty()) }
    var kind by remember { mutableStateOf(ScheduleKind.DAILY) }
    var times by remember { mutableStateOf(suggested?.clockText.orEmpty()) }
    var hours by remember { mutableStateOf("") }
    var firstTime by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<PlanInputException?>(null) }
    var selectingTime by remember { mutableStateOf(false) }
    fun edited() { confirmed = false; inputError = null }
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
                            Text("常用钟点是你的提醒偏好，保存前仍需核对；原文有具体时间或服用条件时请按原文调整。", color = Muted, fontSize = 12.sp)
                        }
                    }
                    OutlinedTextField(name, { name = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("药名") }, isError = inputError?.field == PlanInputField.NAME, singleLine = true)
                    OutlinedTextField(dose, { dose = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("每次剂量（包含单位）") }, placeholder = { Text("照医嘱填写，不自动换算") }, isError = inputError?.field == PlanInputField.DOSE, singleLine = true)
                    OutlinedTextField(instructions, { instructions = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("饭前后要求 / 注意事项") }, minLines = 2)
                    Text("提醒方式", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(ScheduleKind.DAILY to "每天定时", ScheduleKind.INTERVAL to "按间隔", ScheduleKind.AS_NEEDED to "按需").forEach { (value, title) ->
                            FilterChip(selected = kind == value, onClick = { kind = value; edited() }, label = { Text(title) })
                        }
                    }
                    when (kind) {
                        ScheduleKind.DAILY -> {
                            Text("常用钟点", fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DailyTimePreset.entries.forEach { preset ->
                                    FilterChip(selected = times == preset.clockText, onClick = { times = preset.clockText; edited() }, label = { Text("每天${preset.count}次") })
                                }
                            }
                            Text("2次：08:00、18:00\n3次：08:00、12:00、18:00", color = Muted, fontSize = 12.sp)
                            OutlinedTextField(times, { times = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("已确认的提醒钟点") }, placeholder = { Text("08:00, 18:00") }, isError = inputError?.field == PlanInputField.TIMES,
                                supportingText = { Text("必填，可选择或手动输入；多个钟点用逗号分隔。每日几次不自动等同每隔几小时。") })
                            OutlinedButton(onClick = { selectingTime = true }) { Text("选择钟点") }
                        }
                        ScheduleKind.INTERVAL -> {
                            OutlinedTextField(hours, { hours = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("医嘱明确要求的间隔小时数") }, isError = inputError?.field == PlanInputField.INTERVAL_HOURS, singleLine = true)
                            OutlinedTextField(firstTime, { firstTime = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("开始日期的首次时间") }, placeholder = { Text("08:00") }, isError = inputError?.field == PlanInputField.FIRST_TIME, singleLine = true)
                            Text("间隔将跨午夜连续计算，不会每天重置。", color = Muted, fontSize = 12.sp)
                        }
                        ScheduleKind.AS_NEEDED -> Text("按需计划只提供手动记录，不主动提醒服用。", color = Muted, fontSize = 12.sp)
                    }
                    OutlinedTextField(start, { start = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("开始日期 YYYY-MM-DD") }, isError = inputError?.field == PlanInputField.START, singleLine = true)
                    OutlinedTextField(end, { end = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("结束日期（可留空）") }, isError = inputError?.field == PlanInputField.END, supportingText = { Text("包含结束当天；未明确疗程时请先核实。") }, singleLine = true)
                    OutlinedTextField(source, { source = it; edited() }, modifier = Modifier.fillMaxWidth(), label = { Text("医嘱原文 / 来源备注") }, minLines = 3)
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(confirmed, { confirmed = it })
                        Text("我已核对药名、每次剂量、频次、时间和疗程，内容符合医生或药师的指示。", fontSize = 12.sp, color = Ink, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                // 保存失败原因固定在按钮上方，避免被长医嘱或滚动位置遮住。
                (inputError?.message ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
                Button(enabled = confirmed && !saving, onClick = {
                    inputError = null
                    try {
                        onSave(PlanInput(name, dose, instructions, source, kind, times, hours, firstTime, start, end).toPlan(ZoneId.systemDefault()))
                    } catch (error: PlanInputException) { inputError = error }
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 15.dp).height(50.dp)) { Text(if (saving) "正在保存…" else "确认并保存计划") }
            }
        }
    }
    if (selectingTime) {
        val now = LocalTime.now()
        val picker = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { selectingTime = false }, title = { Text("选择提醒钟点") },
            text = { TimeInput(picker, modifier = Modifier.testTag("clock-picker")) },
            confirmButton = { TextButton(onClick = {
                val selected = LocalTime.of(picker.hour, picker.minute).format(DateTimeFormatter.ofPattern("HH:mm"))
                times = if (times.isBlank()) selected else "${times.trim()}, $selected"
                selectingTime = false
                edited()
            }) { Text("添加钟点") } },
            dismissButton = { TextButton(onClick = { selectingTime = false }) { Text("取消") } })
    }
}
