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
    private val mutableImport = MutableStateFlow(ImportState())
    val importState = mutableImport.asStateFlow()
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val mutableSaveError = MutableStateFlow<String?>(null)
    val saveError = mutableSaveError.asStateFlow()

    fun clearMessage() { mutableMessage.value = null }
    fun report(text: String) { mutableMessage.value = text }

    fun resetSaveError() { mutableSaveError.value = null }

    fun save(plan: MedicationPlan, success: () -> Unit) {
        if (mutableSaving.value) return
        mutableSaving.value = true
        mutableSaveError.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { app.repository.save(plan); app.scheduler.reschedule() }
                success(); report("计划已保存")
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
            plannedAt = occurrence.plannedAt, actualAt = actualAt, action = action)
        val changed = app.repository.record(event)
        app.scheduler.dismiss(occurrence); app.scheduler.reschedule()
        report(if (changed) "记录已保存" else "这次已有记录，可在历史页更正")
    }

    fun recordAsNeeded(plan: MedicationPlan) = execute {
        val today = java.time.LocalDate.now()
        val courseEnd = plan.end
        require(plan.enabled && today >= plan.start && (courseEnd == null || today <= courseEnd)) { "当前不在此计划的有效日期内，请先核对计划" }
        app.repository.record(DoseEvent(key = "${plan.id}|manual|${java.util.UUID.randomUUID()}", planId = plan.id, name = plan.name,
            dose = plan.dose, plannedAt = null, actualAt = Instant.now(), action = DoseAction.TAKEN))
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

    fun parse(text: String) {
        if (mutableImport.value.busy) return
        mutableImport.value = ImportState(busy = true)
        viewModelScope.launch {
            try {
                val drafts = withContext(Dispatchers.IO) { ImportClient.parse(connection, text) }
                mutableImport.value = ImportState(drafts = drafts, completed = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableImport.value = ImportState(error = error.message ?: "导入失败，请稍后重试")
            }
        }
    }

    fun resetImport() { mutableImport.value = ImportState() }

    fun markDraftSaved(draft: ImportDraft) {
        mutableImport.value = mutableImport.value.copy(savedDrafts = mutableImport.value.savedDrafts + draft.key)
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

data class ImportState(val busy: Boolean = false, val drafts: List<ImportDraft> = emptyList(), val error: String? = null, val completed: Boolean = false, val savedDrafts: Set<String> = emptySet())
