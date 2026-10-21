package app.mediary

import android.app.Application
import app.mediary.data.MediaryRepository
import app.mediary.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaryApplication : Application() {
    val repository by lazy { MediaryRepository(this) }
    val scheduler by lazy { ReminderScheduler(this, repository) }
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            repository.refresh()
            // 普通进程启动保留正在交付的到期稍后提醒；重启清理由 RecoveryReceiver 明确发起。
            scheduler.reschedule()
        }
    }
}
