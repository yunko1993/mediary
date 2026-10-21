package app.mediary.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import app.mediary.MediaryViewModel
import app.mediary.R
import app.mediary.core.*
import app.mediary.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

val Pine = Color(0xFF326650)
val Ink = Color(0xFF22362D)
val Muted = Color(0xFF78847B)
val Paper = Color(0xFFF6F8F4)
val Line = Color(0xFFE5EBE1)
val Mint = Color(0xFFE7EFE2)
val Amber = Color(0xFF986E39)
val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
fun MediaryApp(model: MediaryViewModel) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val saveError by model.saveError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleSnapshot = snapshot.forPerson(selectedPersonId)
    var editor by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<ImportDraft?>(null) }
    var editingPlan by remember { mutableStateOf<MedicationPlan?>(null) }
    var inboxKey by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var recordTarget by remember { mutableStateOf<Occurrence?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    var reminderRevision by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(30_000) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reminderRevision++; now = Instant.now() }
    val reminderReady = remember(reminderRevision, snapshot.loaded) { model.app.scheduler.notificationsAllowed() && model.app.scheduler.exactAllowed() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    MaterialTheme(colorScheme = lightColorScheme(primary = Pine, onPrimary = Color.White, background = Paper, surface = Color.White,
        onSurface = Ink, onSurfaceVariant = Muted, secondaryContainer = Mint, outlineVariant = Line)) {
        Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar(containerColor = Paper, tonalElevation = 0.dp, modifier = Modifier.border(1.dp, Line)) {
                listOf("今日" to Icons.Outlined.WbSunny, "药箱" to Icons.Outlined.Medication, "记录" to Icons.Outlined.History, "设置" to Icons.Outlined.Tune).forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Pine, indicatorColor = Mint))
                }
            }
        }, floatingActionButton = {
            if (tab <= 1) ExtendedFloatingActionButton(onClick = { draft = null; editingPlan = null; inboxKey = null; model.resetSaveError(); editor = true }, containerColor = Pine, contentColor = Color.White,
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("添加计划") })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Pine), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_mediary), contentDescription = null, modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Mediary", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Ink)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { importing = true }) { Icon(Icons.Outlined.AutoAwesome, "ChatGPT 草稿收件箱", tint = Pine) }
                }
                if (!reminderReady && tab != 3) TextButton(onClick = { tab = 3 }, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(16.dp), tint = Amber)
                    Spacer(Modifier.width(8.dp)); Text("提醒权限尚未就绪，点此检查", color = Amber, fontSize = 12.sp)
                }
                if (snapshot.loaded && tab <= 2) PersonPicker(snapshot.people, selectedPersonId, { selectedPersonId = it }, allowAll = true,
                    modifier = Modifier.padding(horizontal = 24.dp))
                if (!snapshot.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else when (tab) {
                    0 -> TodayScreen(visibleSnapshot, now, { recordTarget = it }, { model.snooze(it) }, { tab = 3 }, { importing = true })
                    1 -> PlansScreen(visibleSnapshot, model::toggle, model::recordAsNeeded, { draft = null; editingPlan = null; inboxKey = null; model.resetSaveError(); editor = true }, { selected -> draft = null; editingPlan = selected; inboxKey = null; model.resetSaveError(); editor = true })
                    2 -> HistoryScreen(visibleSnapshot, model::undo) { export.launch("mediary-${LocalDate.now()}.json") }
                    3 -> SettingsScreen(model) { export.launch("mediary-${LocalDate.now()}.json") }
                }
            }
        }
        if (importing && !editor) McpInboxSheet(model, onDismiss = { importing = false }, onReview = { selected, key -> draft = selected; editingPlan = null; inboxKey = key; model.resetSaveError(); editor = true }, onSettings = { importing = false; tab = 3 })
        if (editor) PlanEditor(draft = draft, existing = editingPlan, people = snapshot.people, initialPersonId = selectedPersonId, saving = saving, saveError = saveError, onDismiss = { if (!saving) editor = false }, onSave = { plan -> model.save(plan, inboxKey, editingPlan) {
            editor = false
        } })
        recordTarget?.let { target -> RecordDialog(target, onDismiss = { recordTarget = null }, onRecord = { action, actual -> model.record(target, action, actual); recordTarget = null }) }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        subtitle?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp)) }
    }
}

@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun Badge(text: String, color: Color = Pine, background: Color = Mint) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(background).padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
private fun TodayScreen(snapshot: Snapshot, now: Instant, record: (Occurrence) -> Unit, snooze: (Occurrence) -> Unit, settings: () -> Unit, import: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    var selectedDay by remember { mutableStateOf(today) }
    val occurrences = snapshot.plans.flatMap { ScheduleCalculator.onDate(it, selectedDay, zone) }.sortedBy { it.plannedAt }
    val missingClocks = snapshot.plans.mapNotNull { plan ->
        ScheduleCalculator.missingDailyTimes(plan, selectedDay, zone).takeIf { it.isNotEmpty() }?.let { plan to it }
    }
    val taken = occurrences.count { snapshot.state(it.key)?.action == DoseAction.TAKEN }
    val next = occurrences.firstOrNull { snapshot.state(it.key) == null && it.plannedAt >= now }
    LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionTitle("照顾好今天的自己", today.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA))) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (-3L..3L).forEach { offset ->
                    val date = today.plusDays(offset)
                    val selected = selectedDay == date
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (selected) Pine else Color.Transparent)
                        .clickable { selectedDay = date }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (offset == 0L) "今" else date.format(DateTimeFormatter.ofPattern("EE", Locale.CHINA)).removePrefix("周"), color = if (selected) Color.White.copy(alpha = .7f) else Muted, fontSize = 11.sp, maxLines = 1)
                        Spacer(Modifier.height(7.dp))
                        Text(date.dayOfMonth.toString(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else Ink, maxLines = 1)
                    }
                }
            }
        }
        item {
            Surface(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), shape = RoundedCornerShape(25.dp), color = Pine) {
                Row(Modifier.padding(23.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(if (selectedDay == today) "TODAY, AT YOUR PACE" else "YOUR MEDICATION DIARY", color = Color(0xFFB9D0B6), fontSize = 10.sp, letterSpacing = 1.4.sp)
                        Text(if (occurrences.isEmpty()) "从一条计划开始" else "已记录 $taken / ${occurrences.size} 次服用", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Medium)
                        Text(when { occurrences.isEmpty() -> "把医嘱整理好，日常更从容。"; next != null -> "下一次计划  ${next.plannedAt.atZone(zone).format(clockFormat)} · ${next.plan.personName} · ${next.plan.name}"; else -> "每次记录，都是对自己的照顾。" }, color = Color(0xFFD5E2D3), fontSize = 12.sp)
                    }
                    if (occurrences.isNotEmpty()) {
                        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { taken.toFloat() / occurrences.size }, modifier = Modifier.fillMaxSize(), color = Color(0xFFE8C78F), trackColor = Color.White.copy(alpha = .15f), strokeWidth = 4.dp)
                            Icon(Icons.Outlined.FavoriteBorder, null, tint = Color(0xFFE8C78F), modifier = Modifier.size(23.dp))
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (selectedDay == today) "今日" else selectedDay.format(DateTimeFormatter.ofPattern("M/d"))}用药", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.weight(1f)); TextButton(onClick = settings) { Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("检查提醒", fontSize = 12.sp) }
            }
        }
        items(missingClocks, key = { "clock-issue-${it.first.id}" }) { (plan, clocks) ->
            SoftCard(Modifier.padding(horizontal = 24.dp)) {
                Badge("提醒时间需要核对", color = Amber)
                Text("${plan.personName} · ${plan.name}", fontWeight = FontWeight.Medium)
                Text("${selectedDay} 的 ${clocks.joinToString("、") { it.format(clockFormat) }} 在当前时区因夏令时跳时不存在，请到药箱核对并调整提醒时间。", color = Amber, fontSize = 13.sp)
            }
        }
        if (occurrences.isEmpty() && missingClocks.isEmpty()) item {
            SoftCard(Modifier.padding(horizontal = 24.dp)) {
                Icon(Icons.Outlined.Spa, null, tint = Pine, modifier = Modifier.size(36.dp))
                Text("这里会放好你的用药安排", fontWeight = FontWeight.Medium)
                Text("添加已确认的计划，或让 AI 帮你整理医嘱。", color = Muted, fontSize = 13.sp)
                TextButton(onClick = import) { Text("从医嘱开始"); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp)) }
            }
        }
        items(occurrences, key = { it.key }) { occurrence ->
            val state = snapshot.state(occurrence.key)
            SoftCard(Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(occurrence.plannedAt.atZone(zone).format(clockFormat), color = if (state == null) Pine else Muted, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Badge(when (state?.action) { DoseAction.TAKEN -> "已服用"; DoseAction.SKIPPED -> "已记为跳过"; else -> if (occurrence.plannedAt < now) "尚未记录" else "待记录" },
                        color = if (state == null && occurrence.plannedAt < now) Amber else Pine, background = if (state == null && occurrence.plannedAt < now) Color(0xFFFBF0E0) else Mint)
                }
                Badge(occurrence.plan.personName)
                Text(occurrence.plan.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(occurrence.plan.dose, color = Ink, fontSize = 14.sp)
                if (occurrence.plan.instructions.isNotBlank()) Text(occurrence.plan.instructions, color = Muted, fontSize = 12.sp)
                if (state != null) Text("实际记录 ${state.actualAt.atZone(zone).format(dateTimeFormat)}", color = Muted, fontSize = 12.sp)
                else if (selectedDay <= today) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { record(occurrence) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("记录服药") }
                    if (occurrence.plannedAt <= now && selectedDay == today) OutlinedButton(onClick = { snooze(occurrence) }) { Text("稍后提醒") }
                }
            }
        }
        item { Text("未记录不代表未服用，请按实际情况登记。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 27.dp, vertical = 4.dp)) }
    }
}

fun scheduleLabel(plan: MedicationPlan): String = when (plan.kind) {
    ScheduleKind.DAILY -> "每天 · " + plan.times.sorted().joinToString(" / ") { it.format(clockFormat) }
    ScheduleKind.INTERVAL -> "每 ${plan.intervalHours} 小时 · 首次 ${plan.anchor?.atZone(ZoneId.systemDefault())?.format(dateTimeFormat)}"
    ScheduleKind.AS_NEEDED -> "按需记录 · 不安排定时提醒"
}

@Composable
internal fun PlansScreen(snapshot: Snapshot, toggle: (MedicationPlan) -> Unit, recordAsNeeded: (MedicationPlan) -> Unit, add: () -> Unit, edit: (MedicationPlan) -> Unit) {
    var detail by remember { mutableStateOf<MedicationPlan?>(null) }
    var confirmAsNeeded by remember { mutableStateOf<MedicationPlan?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionTitle("我的药箱", "${snapshot.plans.count { it.enabled }} 个启用计划 · 每份医嘱都有来处") }
        if (snapshot.plans.isEmpty()) item { SoftCard(Modifier.padding(horizontal = 24.dp)) {
            Icon(Icons.Outlined.Medication, null, Modifier.size(38.dp), tint = Pine)
            Text("药箱还是空的", fontSize = 19.sp)
            Text("按医生或药师给你的要求，添加第一条计划。", color = Muted, fontSize = 13.sp)
            TextButton(onClick = add) { Text("添加计划") }
        } }
        items(snapshot.plans, key = { it.id }) { plan -> SoftCard(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Medication, null, tint = Pine, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(10.dp)); Text(plan.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Badge(if (!plan.enabled) "已暂停" else if (plan.end?.isBefore(LocalDate.now()) == true) "已结束" else "使用中")
            }
            Badge(plan.personName)
            Text(plan.dose, fontSize = 14.sp)
            Text(scheduleLabel(plan), color = Muted, fontSize = 12.sp)
            Text("${plan.start} 至 ${plan.end?.toString() ?: "持续，遵医嘱调整"}", color = Muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { edit(plan) }) { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("编辑计划") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { toggle(plan) }) { Text(if (plan.enabled) "暂停计划" else "恢复计划") }
            }
            Row {
                TextButton(onClick = { detail = plan }) { Text("查看医嘱") }
                if (plan.kind == ScheduleKind.AS_NEEDED && plan.enabled) TextButton(onClick = { confirmAsNeeded = plan }) { Text("记一次") }
            }
        } }
    }
    detail?.let { plan -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(plan.name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("用药人：${plan.personName}")
            Text("每次剂量：${plan.dose}"); Text("${scheduleLabel(plan)}\n${plan.instructions}")
            Text("医嘱原文", fontWeight = FontWeight.SemiBold)
            Text(plan.source.ifBlank { "手动创建的计划" }, color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { detail = null }) { Text("知道了") } }) }
    confirmAsNeeded?.let { plan -> AlertDialog(onDismissRequest = { confirmAsNeeded = null }, title = { Text("记录实际服用") },
        text = { Text("${plan.personName} · ${plan.name} · ${plan.dose}\n确认刚刚已按医嘱服用？按需记录不改变任何定时计划。") },
        confirmButton = { TextButton(onClick = { recordAsNeeded(plan); confirmAsNeeded = null }) { Text("确认记录") } },
        dismissButton = { TextButton(onClick = { confirmAsNeeded = null }) { Text("取消") } }) }
}

@Composable
private fun HistoryScreen(snapshot: Snapshot, undo: (DoseEvent) -> Unit, export: () -> Unit) {
    var undoTarget by remember { mutableStateOf<DoseEvent?>(null) }
    val latest = snapshot.latest
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { SectionTitle("用药手记", "计划时间、实际时间，以及每一次更正") }
        item { OutlinedButton(onClick = export, modifier = Modifier.padding(horizontal = 24.dp)) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导出计划与历史") } }
        if (snapshot.events.isEmpty()) item { SoftCard(Modifier.padding(horizontal = 24.dp)) {
            Text("第一笔记录，还在路上", fontSize = 19.sp)
            Text("服药后在今日页面登记，历史会保存在这部手机上。", color = Muted, fontSize = 13.sp)
        } }
        items(snapshot.events, key = { it.id }) { event -> SoftCard(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Badge(when(event.action) { DoseAction.TAKEN -> "已服用"; DoseAction.SKIPPED -> "跳过记录"; DoseAction.UNDONE -> "撤销操作" })
            }
            Badge(event.personName)
            Text(event.dose, color = Muted, fontSize = 13.sp)
            Text("计划  ${event.plannedAt?.atZone(ZoneId.systemDefault())?.format(dateTimeFormat) ?: "按需记录"}", fontSize = 12.sp)
            Text("实际  ${event.actualAt.atZone(ZoneId.systemDefault()).format(dateTimeFormat)}", fontSize = 12.sp)
            if (latest[event.key]?.id != event.id) Text("后续已有更正，原记录保留", color = Amber, fontSize = 11.sp)
            else if (event.action != DoseAction.UNDONE) TextButton(onClick = { undoTarget = event }, contentPadding = PaddingValues(0.dp)) { Text("更正：撤销这次登记", fontSize = 12.sp) }
        } }
    }
    undoTarget?.let { event -> AlertDialog(onDismissRequest = { undoTarget = null }, title = { Text("撤销这次登记？") },
        text = { Text("${event.personName} · ${event.name}\n原操作会保留在历史中。定时剂次会回到待记录状态，过去的提醒不会自动补发。") },
        confirmButton = { TextButton(onClick = { undo(event); undoTarget = null }) { Text("撤销登记") } },
        dismissButton = { TextButton(onClick = { undoTarget = null }) { Text("保留") } }) }
}

@Composable
private fun RecordDialog(occurrence: Occurrence, onDismiss: () -> Unit, onRecord: (DoseAction, Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    var actual by remember { mutableStateOf(LocalDateTime.now().format(formatter)) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit(action: DoseAction) {
        runCatching {
            val instant = LocalDateTime.parse(actual, formatter).atZone(zone).toInstant()
            require(!instant.isAfter(Instant.now().plusSeconds(60))) { "实际时间不能在未来" }
            onRecord(action, instant)
        }.onFailure { error = it.message?.takeIf { text -> !text.contains("could not") } ?: "请按 年-月-日 时:分 填写" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("记录实际服药") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("用药人：${occurrence.plan.personName}")
            Text("${occurrence.plan.name} · ${occurrence.plan.dose}", fontWeight = FontWeight.Medium)
            Text("计划 ${occurrence.plannedAt.atZone(zone).format(dateTimeFormat)}", color = Muted)
            OutlinedTextField(value = actual, onValueChange = { actual = it; error = null }, label = { Text("实际时间，可补录") }, placeholder = { Text("2026-10-19 08:00") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Text("选择跳过只记录事实。漏服后的处理请按医生或药师的指示。", color = Muted, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { submit(DoseAction.TAKEN) }) { Text("已服用，保存") } },
        dismissButton = { Row { TextButton(onClick = onDismiss) { Text("取消") }; TextButton(onClick = { submit(DoseAction.SKIPPED) }) { Text("记录跳过") } } })
}
