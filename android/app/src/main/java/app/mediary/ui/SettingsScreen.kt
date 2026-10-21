package app.mediary.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import app.mediary.MediaryViewModel
import app.mediary.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(model: MediaryViewModel, export: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var mcpEndpoint by remember { mutableStateOf(model.connection.mcpEndpoint) }
    var mcpToken by remember { mutableStateOf(model.connection.mcpToken) }
    var mcpError by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++; model.refresh() }
    val notifications = remember(revision) { model.app.scheduler.notificationsAllowed() }
    val exact = remember(revision) { model.app.scheduler.exactAllowed() }
    val testResult by model.app.scheduler.testResult.collectAsStateWithLifecycle()
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { model.report("未找到设置入口，请在系统设置中打开 Mediary 的应用详情") } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("安心设置", fontSize = 27.sp, fontWeight = FontWeight.SemiBold) }
        item { Text("把提醒检查好，把记录留在身边。", fontSize = 13.sp, color = Muted) }
        item { PeopleSettings(model) }
        item { SoftCard {
            Text("提醒状态", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Badge(if (notifications) "通知已开启" else "通知未开启", color = if (notifications) Pine else Amber)
            Text(if (exact) "精确定时权限已就绪" else "精确定时权限未开启，提醒可能延迟", fontSize = 13.sp, color = if (exact) Muted else Amber)
            if (!notifications) Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("开启通知") }
            if (!exact && Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) }) { Text("允许闹钟和提醒") }
            OutlinedButton(enabled = notifications, onClick = { model.app.scheduler.testReminder(); model.report(if (exact) "15 秒后发送测试提醒，可以先锁屏" else "已安排测试提醒，当前可能延迟") }) { Text("15 秒后测试提醒") }
            testResult?.let { result ->
                val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                val triggeredAt = result.triggeredAt
                Text("最近测试：计划 ${format.format(Instant.ofEpochMilli(result.expectedAt))}", color = Muted, fontSize = 12.sp)
                Text(when {
                    triggeredAt == null -> "等待系统触发。若长时间未更新，请检查后台耗电限制。"
                    result.notificationSubmitted -> "系统已触发 ${format.format(Instant.ofEpochMilli(triggeredAt))}，通知已提交。"
                    else -> "系统已触发，但通知未能提交，请检查通知权限。"
                }, color = if (triggeredAt != null && !result.notificationSubmitted) Amber else Muted, fontSize = 12.sp)
                if (result.notificationSubmitted) Text("通知显示和声音仍受系统通知设置及勿扰模式影响，请同时核对通知栏。", color = Muted, fontSize = 11.sp)
            }
            Text("OPPO / ColorOS：在应用详情中检查通知、后台活动和电池使用设置。不同系统版本入口可能不同，请锁屏、断网和重启后实测。", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("打开系统应用设置") }
            // 调试包通过系统接口直达联调设置，避免依赖厂商菜单的位置或搜索索引。
            if (BuildConfig.DEBUG) TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("打开开发者选项") }
            Text("手机关机、应用被强行停止或权限关闭时，提醒可能无法送达。", color = Muted, fontSize = 11.sp)
        } }
        item { SoftCard {
            Text("ChatGPT 草稿连接", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Badge("MCP · 无需模型 API Key")
            Text("ChatGPT 连接你的 Mediary MCP 服务；手机从同一服务领取草稿，核对后才启用提醒。只同步草稿与保存回执，不上传服药历史。", color = Muted, fontSize = 12.sp)
            OutlinedTextField(mcpEndpoint, { if (it != mcpEndpoint) mcpToken = ""; mcpEndpoint = it; mcpError = null }, label = { Text("Mediary 服务地址") }, placeholder = { Text("https://你的服务/mediary") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(mcpToken, { mcpToken = it; mcpError = null }, label = { Text("服务访问口令") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            Text("这是你自己的服务口令，用于手机同步和网页授权；无需填写模型 API Key。口令仅加密保存在本机，不要发送到聊天中。", color = Muted, fontSize = 11.sp)
            mcpError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Button(onClick = { runCatching { model.connection.saveMcp(mcpEndpoint, mcpToken); model.report("草稿连接已保存，可打开收件箱同步") }.onFailure { mcpError = it.message } }) { Text("保存草稿连接") }
            Text("在 ChatGPT 网页添加自定义 MCP 服务，地址为上方服务地址后加 /mcp，授权方式选 OAuth。具体入口以你的账号为准。", color = Muted, fontSize = 12.sp)
        } }
        item { SoftCard {
            Text("我的数据", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("计划和历史保存在本机，不自动上传。卸载会清除本机数据，请按需导出。", color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = export) { Text("导出 JSON 备份") }
        } }
        item { Text("Mediary ${BuildConfig.VERSION_NAME} · 用药手记\n你记录的每一次，都有迹可循。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp)) }
    }
}
