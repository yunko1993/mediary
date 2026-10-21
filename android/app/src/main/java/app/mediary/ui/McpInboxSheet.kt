package app.mediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mediary.MediaryViewModel
import app.mediary.data.ImportDraft
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpInboxSheet(model: MediaryViewModel, onDismiss: () -> Unit, onReview: (ImportDraft, String?) -> Unit, onSettings: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sync by model.inboxSync.collectAsStateWithLifecycle()
    val account = model.connection.mcpEndpoint
    val drafts = snapshot.inbox.filter { it.account == account }
    val configured = model.connection.mcpToken.isNotBlank()
    LaunchedEffect(Unit) { if (configured) model.syncInbox() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Badge("ChatGPT → Mediary")
            Text("医嘱草稿收件箱", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("在 ChatGPT 里把医嘱交给 Mediary，回到这里同步，再逐项核对并保存。无需模型 API Key。", color = Muted, fontSize = 13.sp)
            if (!configured) OutlinedButton(onClick = onSettings) { Text("配置草稿连接") }
            Button(onClick = model::syncInbox, enabled = configured && !sync.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (sync.busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
                Text(if (sync.busy) "正在同步…" else "同步 ChatGPT 草稿")
            }
            sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            sync.syncedAt?.let { Text("本次同步 ${DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(it)}", color = Muted, fontSize = 12.sp) }
            if (drafts.isEmpty() && !sync.busy) Text("还没有草稿。在 ChatGPT 中选择 Mediary，发送医嘱原文并说明“保存为待核对草稿”，然后同步。", color = Muted, fontSize = 13.sp)
            drafts.forEach { item ->
                SoftCard {
                    Badge(if (item.localPlanId != null) "本机已保存计划" else if (item.reviewedOnServer) "已在手机核对" else "待你核对")
                    Text(item.draft.name.ifBlank { "药名待确认" }, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("${item.draft.dose.ifBlank { "剂量待确认" }} · ${item.draft.frequency.ifBlank { "频次待确认" }}", fontSize = 13.sp)
                    Text("原文：${item.sourceText}", color = Muted, fontSize = 12.sp)
                    if (item.draft.missing.isNotEmpty()) Text("待确认：${item.draft.missing.joinToString("、")}", color = Amber, fontSize = 12.sp)
                    OutlinedButton(onClick = { onReview(item.draft, item.key) }, enabled = !item.reviewed) { Text(if (item.reviewed) "已核对，查看药箱" else "核对并设置提醒") }
                }
            }
            Text("草稿不会自动变成提醒。已保存的本地计划在断网时仍可提醒；手机服药历史不会上传。", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(30.dp))
        }
    }
}
