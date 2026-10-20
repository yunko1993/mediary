package app.mediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mediary.MediaryViewModel
import app.mediary.data.ImportDraft
import app.mediary.data.ImportMode
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(model: MediaryViewModel, onDismiss: () -> Unit, onReview: (ImportDraft, String?) -> Unit, onSettings: () -> Unit) {
    var apiImport by remember { mutableStateOf(false) }
    if (!apiImport) {
        McpInboxSheet(model, onDismiss, onReview, onSettings, onApiImport = { apiImport = true })
        return
    }
    var text by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    val state by model.importState.collectAsStateWithLifecycle()
    val connection = remember { model.connection.active() }
    val destination = remember { runCatching { URI(connection.endpoint).host }.getOrNull().orEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Pine, modifier = Modifier.size(32.dp))
            Text("把医嘱，整理成日常", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("粘贴医生或药师的用药要求，AI 会提取内容供你核对。", color = Muted, fontSize = 13.sp)
            if (connection.mode == ImportMode.ZHIPU) Badge("智谱 · GLM-4.7-Flash")
            OutlinedTextField(text, { if (!state.busy) { text = it; consent = false; model.resetImport() } }, modifier = Modifier.fillMaxWidth(),
                label = { Text("医嘱原文") }, minLines = 6, maxLines = 12, enabled = !state.busy)
            Row {
                Checkbox(consent, { consent = it }, enabled = !state.busy)
                Text(if (connection.mode != ImportMode.RELAY) "同意将上方文字发送至 $destination 的 AI 接口处理。请先移除姓名、证件号等无关信息。"
                    else "同意将上方文字发送至自有整理服务 $destination 及其配置的模型服务处理。请先移除姓名、证件号等无关信息。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Button(onClick = { model.parse(text.trim()) }, enabled = consent && text.isNotBlank() && text.length <= 12_000 && !state.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (state.busy) { CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                Text(if (state.busy) "正在整理医嘱…" else "用 AI 整理")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            if (state.completed && state.drafts.isEmpty()) Text("没有提取到可核对的药物，请补充原文或手动添加。", color = Muted)
            if (state.drafts.isNotEmpty()) Text("解析完成 · 已保存 ${state.savedDrafts.size} / ${state.drafts.size} 条", fontWeight = FontWeight.SemiBold)
            state.drafts.forEach { draft ->
                val saved = draft.key in state.savedDrafts
                SoftCard {
                Badge(if (saved) "已保存计划" else "待核对")
                Text(draft.name.ifBlank { "药名待确认" }, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("${draft.dose.ifBlank { "剂量待确认" }} · ${draft.frequency.ifBlank { "频次待确认" }}", fontSize = 13.sp)
                Text("原文：${draft.source}", color = Muted, fontSize = 12.sp)
                if (draft.missing.isNotEmpty()) Text("待确认：${draft.missing.joinToString("、")}", color = Amber, fontSize = 12.sp)
                OutlinedButton(onClick = { onReview(draft, null) }, enabled = !saved) { Text(if (saved) "已保存，可继续核对下一条" else "逐项核对并设置提醒") }
            } }
            Text("保存计划后才会创建提醒。AI 不会替你增减剂量或处理漏服。", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(30.dp))
        }
    }
}
