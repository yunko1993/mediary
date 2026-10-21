package app.mediary

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mediary.core.*
import app.mediary.data.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaryViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as MediaryApplication
    val snapshot = app.repository.snapshot
    val connection = ConnectionSettings(application)
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val mutableSaveError = MutableStateFlow<String?>(null)
    val saveError = mutableSaveError.asStateFlow()
    private val mutableInboxSync = MutableStateFlow(InboxSyncState())
    val inboxSync = mutableInboxSync.asStateFlow()
    private val mutablePersonSaving = MutableStateFlow(false)
    val personSaving = mutablePersonSaving.asStateFlow()
    private val mutablePersonError = MutableStateFlow<String?>(null)
    val personError = mutablePersonError.asStateFlow()

    fun resetPersonError() { mutablePersonError.value = null }

    fun savePerson(original: MedicationPerson?, name: String, success: () -> Unit) {
        if (mutablePersonSaving.value) return
        mutablePersonSaving.value = true
        mutablePersonError.value = null
        viewModelScope.launch {
            try {
                val person = MedicationPerson(original?.id ?: java.util.UUID.randomUUID().toString(), name.trim())
                val reminderFailed = withContext(Dispatchers.IO) {
                    app.repository.savePerson(person)
                    try {
                        // 改称呼后收起原称呼通知，下一次提醒读取新称呼；历史快照不改写。
                        app.repository.snapshot.value.plans.filter { it.personId == person.id }.forEach { app.scheduler.cancelForEdit(it, emptyList()) }
                        app.scheduler.reschedule()
                        false
                    } catch (_: Exception) { true }
                }
                success()
                report(if (reminderFailed) "用药人已保存，请重新打开应用检查提醒" else "用药人已保存")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutablePersonError.value = error.message ?: "用药人未能保存，请重试" }
            finally { mutablePersonSaving.value = false }
        }
    }

    fun clearMessage() { mutableMessage.value = null }
    fun report(text: String) { mutableMessage.value = text }

    fun resetSaveError() { mutableSaveError.value = null }

    fun save(plan: MedicationPlan, inboxKey: String? = null, original: MedicationPlan? = null, success: () -> Unit) {
        if (mutableSaving.value) return
        mutableSaving.value = true
        mutableSaveError.value = null
        viewModelScope.launch {
            try {
                val reminderFailed = withContext(Dispatchers.IO) {
                    val previousSnoozes = if (original != null) app.repository.edit(original, plan) else {
                        app.repository.save(plan, inboxKey); emptyList()
                    }
                    // 本地保存与系统闹钟属于不同事务；闹钟失败不能误报成“计划未保存”。
                    try {
                        if (original != null) app.scheduler.cancelForEdit(plan, previousSnoozes)
                        app.scheduler.reschedule()
                        false
                    } catch (_: Exception) { true }
                }
                success(); report(if (reminderFailed) "计划已保存，但提醒安排未完成，请检查提醒权限后重新打开应用" else if (original != null) "计划已更新，历史记录保留" else "计划已保存")
                if (inboxKey != null) syncInbox()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableSaveError.value = error.message ?: "计划未能保存，请重试" }
            finally { mutableSaving.value = false }
        }
    }

    fun toggle(plan: MedicationPlan) = execute {
        app.repository.save(plan.copy(enabled = !plan.enabled)); app.scheduler.reschedule()
        report(if (plan.enabled) "计划已暂停，历史记录保留" else "计划已恢复")
    }

    fun record(occurrence: Occurrence, action: DoseAction, actualAt: Instant = Instant.now()) = execute {
        val event = DoseEvent(key = occurrence.key, planId = occurrence.plan.id, name = occurrence.plan.name, dose = occurrence.plan.dose,
            plannedAt = occurrence.plannedAt, actualAt = actualAt, action = action,
            personId = occurrence.plan.personId, personName = occurrence.plan.personName)
        val changed = app.repository.record(event)
        app.scheduler.dismiss(occurrence); app.scheduler.reschedule()
        report(if (changed) "记录已保存" else "这次已有记录，可在历史页更正")
    }

    fun recordAsNeeded(plan: MedicationPlan) = execute {
        val today = java.time.LocalDate.now()
        val courseEnd = plan.end
        require(plan.enabled && today >= plan.start && (courseEnd == null || today <= courseEnd)) { "当前不在此计划的有效日期内，请先核对计划" }
        app.repository.record(DoseEvent(key = "${plan.id}|manual|${java.util.UUID.randomUUID()}", planId = plan.id, name = plan.name,
            dose = plan.dose, plannedAt = null, actualAt = Instant.now(), action = DoseAction.TAKEN,
            personId = plan.personId, personName = plan.personName))
        report("按需服药记录已保存")
    }

    fun undo(event: DoseEvent) = execute {
        app.repository.record(event.copy(id = 0, action = DoseAction.UNDONE, actualAt = Instant.now(), recordedAt = Instant.now()))
        app.scheduler.reschedule(); report("已撤销，原操作仍保留在历史中")
    }

    fun snooze(occurrence: Occurrence) = execute {
        require(app.scheduler.notificationsAllowed()) { "请先在设置中开启通知" }
        require(app.repository.snapshot.value.state(occurrence.key) == null) { "这次已有记录" }
        app.scheduler.snooze(occurrence); report("将在 10 分钟后再次提醒，原计划时间不变")
    }

    fun syncInbox() {
        if (mutableInboxSync.value.busy) return
        val profile = connection.mcp()
        mutableInboxSync.value = InboxSyncState(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 先补发上次已保存计划的回执，服务离线不影响本地计划与提醒。
                    app.repository.pendingReceipts(profile.endpoint).chunked(50).forEach { pending ->
                        McpClient.receipt(profile, pending); app.repository.acknowledgeReceipts(pending)
                    }
                    var cursor = 0L
                    var pages = 0
                    do {
                        val page = McpClient.page(profile, cursor)
                        app.repository.mergeInbox(page.drafts)
                        McpClient.receipt(profile, page.drafts)
                        cursor = page.nextCursor ?: 0L
                        check(++pages <= 100) { "收件箱分页过多，请检查服务" }
                    } while (cursor != 0L)
                }
                mutableInboxSync.value = InboxSyncState(syncedAt = Instant.now())
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableInboxSync.value = InboxSyncState(error = error.message ?: "同步失败，已下载草稿仍保留在本机") }
        }
    }

    fun export(uri: Uri) = execute {
        val json = app.repository.exportJson()
        app.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
            ?: error("无法写入所选文件")
        report("计划与完整操作历史已导出")
    }

    fun refresh() = execute { app.repository.refresh(); app.scheduler.reschedule() }

    private fun execute(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error.message ?: "操作失败，请重试") }
        }
    }
}

data class InboxSyncState(val busy: Boolean = false, val syncedAt: Instant? = null, val error: String? = null)
