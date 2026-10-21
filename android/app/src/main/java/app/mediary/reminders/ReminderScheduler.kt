package app.mediary.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import app.mediary.MainActivity
import app.mediary.MediaryApplication
import app.mediary.R
import app.mediary.core.*
import app.mediary.data.MediaryRepository
import app.mediary.data.SnoozedReminder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReminderTestResult(val expectedAt: Long, val triggeredAt: Long? = null, val notificationSubmitted: Boolean = false)

class ReminderScheduler(private val context: Context, private val repository: MediaryRepository) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val channelId = "medication-reminders"
    private val testPreferences = context.getSharedPreferences("reminder-test", Context.MODE_PRIVATE)
    private val mutableTestResult = MutableStateFlow(testPreferences.getLong("expectedAt", 0).takeIf { it > 0 }?.let {
        ReminderTestResult(it, testPreferences.getLong("triggeredAt", 0).takeIf { time -> time > 0 }, testPreferences.getBoolean("submitted", false))
    })
    val testResult = mutableTestResult.asStateFlow()

    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
    fun notificationsAllowed(): Boolean {
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (notificationManager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    /** 每个计划只保留下一次系统闹钟；触发、重启、时区变化及计划变更后重新安排。 */
    @Synchronized fun reschedule() {
        ensureChannel()
        val now = Instant.now()
        repository.snapshot.value.plans.forEach { plan ->
            alarmManager.cancel(alarmIntent(plan.id, 0))
            if (!plan.enabled) {
                notificationManager.activeNotifications.filter { it.tag?.startsWith("${plan.id}|") == true }.forEach { notificationManager.cancel(it.tag, it.id) }
            }
            ScheduleCalculator.next(plan, now, ZoneId.systemDefault())?.let { occurrence ->
                scheduleAt(occurrence.plannedAt.toEpochMilli(), alarmIntent(plan.id, occurrence.plannedAt.toEpochMilli()))
            }
        }
        repository.snapshot.value.snoozes.forEach { snooze ->
            val plan = repository.snapshot.value.plans.find { it.id == snooze.planId }
            if (plan != null) {
                val occurrence = Occurrence(plan, snooze.plannedAt)
                alarmManager.cancel(snoozeIntent(occurrence))
                if (plan.enabled && snooze.remindAt > now && repository.snapshot.value.state(snooze.key) == null) {
                    scheduleAt(snooze.remindAt.toEpochMilli(), snoozeIntent(occurrence))
                } else repository.clearSnooze(snooze.key)
            } else repository.clearSnooze(snooze.key)
        }
    }

    private fun alarmIntent(planId: String, plannedAt: Long): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND; data = "mediary://alarm/$planId".toUri()
            putExtra("planId", planId); putExtra("plannedAt", plannedAt)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** 编辑事务落盘后取消旧主闹钟、稍后闹钟和已显示通知，再安排新计划。 */
    fun cancelForEdit(plan: MedicationPlan, previousSnoozes: List<SnoozedReminder>) {
        alarmManager.cancel(alarmIntent(plan.id, 0))
        previousSnoozes.forEach { alarmManager.cancel(snoozeIntent(Occurrence(plan, it.plannedAt))) }
        notificationManager.activeNotifications.filter { it.tag?.startsWith("${plan.id}|") == true }.forEach { notificationManager.cancel(it.tag, it.id) }
    }

    private fun scheduleAt(timestamp: Long, pendingIntent: PendingIntent) {
        // 权限随时可能被撤回；降级提醒必须在设置页明确显示为“可能延迟”。
        try {
            if (exactAllowed()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
        }
    }

    fun snooze(occurrence: Occurrence) {
        val remindAt = Instant.now().plusSeconds(600)
        repository.snooze(occurrence, remindAt)
        scheduleAt(remindAt.toEpochMilli(), snoozeIntent(occurrence))
        notificationManager.cancel(occurrence.key, 0)
    }

    private fun snoozeIntent(occurrence: Occurrence): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND; data = "mediary://snooze/${Uri.encode(occurrence.key)}".toUri()
            putExtra("planId", occurrence.plan.id); putExtra("plannedAt", occurrence.plannedAt.toEpochMilli())
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun dismiss(occurrence: Occurrence) {
        notificationManager.cancel(occurrence.key, 0)
        alarmManager.cancel(snoozeIntent(occurrence))
        repository.clearSnooze(occurrence.key)
    }

    fun testReminder() {
        val expectedAt = Instant.now().plusSeconds(15).toEpochMilli()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TEST; data = "mediary://test".toUri(); putExtra("expectedAt", expectedAt)
        }
        saveTestResult(ReminderTestResult(expectedAt))
        scheduleAt(expectedAt, PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    fun showTestNotification(expectedAt: Long) {
        // 测试仅持久化时间和提交状态，不保存药物信息；提交通知不等于用户已经看到或听到。
        if (mutableTestResult.value?.expectedAt != expectedAt) return
        val triggeredAt = System.currentTimeMillis()
        val submitted = show("test", NotificationCompat.Builder(context, channelId).setContentTitle("Mediary 提醒测试")
            .setContentText("测试提醒已送达。可以锁屏后再次测试。"))
        saveTestResult(ReminderTestResult(expectedAt, triggeredAt, submitted))
    }

    private fun saveTestResult(result: ReminderTestResult) {
        testPreferences.edit {
            putLong("expectedAt", result.expectedAt)
            putLong("triggeredAt", result.triggeredAt ?: 0)
            putBoolean("submitted", result.notificationSubmitted)
        }
        mutableTestResult.value = result
    }

    fun notify(occurrence: Occurrence) {
        val plan = occurrence.plan
        val plannedTime = occurrence.plannedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        val content = "计划 $plannedTime · ${plan.dose}\n${plan.instructions}".trim()
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(plan.name).setContentText("计划 $plannedTime · ${plan.dose}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .addAction(0, "已服用", actionIntent(occurrence, ACTION_TAKEN))
            .addAction(0, "稍后提醒", actionIntent(occurrence, ACTION_SNOOZE))
        show(occurrence.key, builder)
    }

    private fun actionIntent(occurrence: Occurrence, actionName: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).apply {
            action = actionName; data = "mediary://action/${Uri.encode(occurrence.key)}/$actionName".toUri()
            putExtra("planId", occurrence.plan.id); putExtra("plannedAt", occurrence.plannedAt.toEpochMilli())
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun show(tag: String, builder: NotificationCompat.Builder): Boolean {
        ensureChannel()
        if (!notificationsAllowed()) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicVersion = NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mediary").setContentText("有一条计划提醒，解锁后查看").build()
        val notification = builder.setSmallIcon(R.drawable.ic_notification).setAutoCancel(true).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).build()
        return try { notificationManager.notify(tag, 0, notification); true }
        catch (_: SecurityException) { false /* 用户可能刚撤回通知权限，保留计划供下次打开检查。 */ }
    }

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(NotificationChannel(channelId, "用药提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "按已确认的用药计划提醒"; lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        })
    }

    companion object {
        const val ACTION_REMIND = "app.mediary.REMIND"
        const val ACTION_TAKEN = "app.mediary.TAKEN"
        const val ACTION_SNOOZE = "app.mediary.SNOOZE"
        const val ACTION_TEST = "app.mediary.TEST"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as MediaryApplication
        app.applicationScope.launch {
            try {
                app.repository.refresh()
                if (intent.action == ReminderScheduler.ACTION_TEST) { app.scheduler.showTestNotification(intent.getLongExtra("expectedAt", 0)); return@launch }
                val plan = app.repository.snapshot.value.plans.find { it.id == intent.getStringExtra("planId") && it.enabled } ?: return@launch
                val occurrence = Occurrence(plan, Instant.ofEpochMilli(intent.getLongExtra("plannedAt", 0)))
                val date = occurrence.plannedAt.atZone(ZoneId.systemDefault()).toLocalDate()
                // 接收器不接受任意剂次；只处理排程中存在、尚未登记的原始计划。
                if (ScheduleCalculator.onDate(plan, date, ZoneId.systemDefault()).none { it.key == occurrence.key }) return@launch
                if (app.repository.snapshot.value.state(occurrence.key) != null) { app.scheduler.dismiss(occurrence); return@launch }
                when (intent.action) {
                    ReminderScheduler.ACTION_REMIND -> {
                        if (intent.data?.host == "snooze") {
                            // 编辑已移除旧稍后提醒；即使进程在取消系统闹钟前退出，也不能发送孤立通知。
                            if (app.repository.snapshot.value.snoozes.none { it.key == occurrence.key }) return@launch
                            app.repository.clearSnooze(occurrence.key)
                        }
                        app.scheduler.notify(occurrence)
                    }
                    ReminderScheduler.ACTION_SNOOZE -> app.scheduler.snooze(occurrence)
                    ReminderScheduler.ACTION_TAKEN -> {
                        app.repository.record(DoseEvent(key = occurrence.key, planId = plan.id, name = plan.name, dose = plan.dose,
                            plannedAt = occurrence.plannedAt, actualAt = Instant.now(), action = DoseAction.TAKEN))
                        app.scheduler.dismiss(occurrence)
                    }
                }
            } finally {
                try { app.scheduler.reschedule() } finally { pending.finish() }
            }
        }
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        val app = context.applicationContext as MediaryApplication
        app.applicationScope.launch {
            try { app.repository.refresh(); app.scheduler.reschedule() } finally { pending.finish() }
        }
    }
}
