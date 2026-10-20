package app.mediary.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import app.mediary.data.ImportMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(model: MediaryViewModel, export: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var endpoint by remember { mutableStateOf(model.connection.endpoint) }
    var token by remember { mutableStateOf(model.connection.token) }
    var mode by remember { mutableStateOf(model.connection.mode) }
    var apiBase by remember { mutableStateOf(model.connection.apiBaseUrl) }
    var apiModel by remember { mutableStateOf(model.connection.apiModel) }
    var apiKey by remember { mutableStateOf(model.connection.apiKey) }
    var hasSavedKey by remember { mutableStateOf(apiKey.isNotBlank()) }
    var zhipuKey by remember { mutableStateOf(model.connection.zhipuKey) }
    var hasZhipuKey by remember { mutableStateOf(zhipuKey.isNotBlank()) }
    var advanced by remember { mutableStateOf(mode == ImportMode.RELAY) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++; model.refresh() }
    val notifications = remember(revision) { model.app.scheduler.notificationsAllowed() }
    val exact = remember(revision) { model.app.scheduler.exactAllowed() }
    val testResult by model.app.scheduler.testResult.collectAsStateWithLifecycle()
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { model.report("未找到设置入口，请在系统设置中打开 Mediary 的应用详情") } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("安心设置", fontSize = 27.sp, fontWeight = FontWeight.SemiBold) }
        item { Text("把提醒检查好，把记录留在身边。", fontSize = 13.sp, color = Muted) }
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
            Text("AI 医嘱整理", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Badge(when (mode) { ImportMode.ZHIPU -> "智谱 · GLM-4.7-Flash"; ImportMode.DIRECT -> "其他 API 直连"; ImportMode.RELAY -> "自有整理服务" })
            Text(if (mode != ImportMode.RELAY) "填写你自己的 API 密钥，手机即可独立整理医嘱，无需电脑在线。只在你主动导入时联网。"
                else "使用你已部署的整理服务；该模式需要服务保持在线。", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起接口设置" else "接口地址、模型与连接方式") }
            if (advanced) {
                listOf(ImportMode.ZHIPU to "智谱 GLM-4.7-Flash", ImportMode.DIRECT to "其他 API 直连", ImportMode.RELAY to "自有服务（可选）").forEach { (choice, label) ->
                    Row(Modifier.fillMaxWidth().clickable { mode = choice; error = null }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = mode == choice, onClick = { mode = choice; error = null })
                        Text(label, fontSize = 13.sp)
                    }
                }
            }
            if (mode == ImportMode.ZHIPU) {
                Text("模型已固定：glm-4.7-flash", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text("仅请求智谱官方 GLM-4.7-Flash，并核对返回的模型标识。失败不会自动切换其他型号。模型价格和限流以智谱平台为准。", color = Muted, fontSize = 11.sp)
                OutlinedTextField(zhipuKey, { zhipuKey = it; error = null }, label = { Text("智谱 API Key") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                Text("智谱密钥单独加密保存在本机，只发送到 open.bigmodel.cn。", color = Muted, fontSize = 11.sp)
            } else if (mode == ImportMode.DIRECT) {
                if (advanced) {
                    OutlinedTextField(apiBase, { if (it != apiBase) apiKey = ""; apiBase = it; error = null }, label = { Text("API 基础地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(apiModel, { apiModel = it; error = null }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("兼容接口需支持 Responses API 和结构化输出。更换地址后，请重新填写对应接口的密钥。", color = Muted, fontSize = 11.sp)
                }
                OutlinedTextField(apiKey, { apiKey = it; error = null }, label = { Text("API 密钥") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                Text("密钥在本机加密保存，只发给你设置的 API 地址。请使用自己的密钥；卸载后需重新填写。", color = Muted, fontSize = 11.sp)
            } else {
                OutlinedTextField(endpoint, { if (it != endpoint) token = ""; endpoint = it; error = null }, label = { Text("医嘱整理服务地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(token, { token = it; error = null }, label = { Text("服务访问口令") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Button(onClick = { runCatching {
                when (mode) {
                    ImportMode.ZHIPU -> { model.connection.saveZhipu(zhipuKey); hasZhipuKey = true }
                    ImportMode.DIRECT -> { model.connection.saveDirect(apiBase, apiModel, apiKey); hasSavedKey = true }
                    ImportMode.RELAY -> model.connection.saveRelay(endpoint, token)
                }
                model.report("AI 设置已保存，可从医嘱导入开始使用")
            }.onFailure { error = it.message } }) { Text("保存 AI 设置") }
            if (mode == ImportMode.ZHIPU && hasZhipuKey) TextButton(onClick = {
                model.connection.clearZhipuKey(); zhipuKey = ""; hasZhipuKey = false; model.report("本机智谱密钥已清除")
            }) { Text("清除已保存的智谱密钥") }
            if (mode == ImportMode.DIRECT && hasSavedKey) TextButton(onClick = {
                model.connection.clearDirectKey(); apiKey = ""; hasSavedKey = false; model.report("本机 API 密钥已清除")
            }) { Text("清除已保存的 API 密钥") }
        } }
        item { SoftCard {
            Text("我的数据", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("计划和历史保存在本机，不自动上传。卸载会清除本机数据，请按需导出。", color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = export) { Text("导出 JSON 备份") }
        } }
        item { Text("Mediary 0.1.0 · 用药手记\n你记录的每一次，都有迹可循。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp)) }
    }
}
