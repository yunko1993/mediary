package app.mediary.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.mediary.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SnoozedReminder(val key: String, val planId: String, val plannedAt: Instant, val remindAt: Instant)

data class Snapshot(val plans: List<MedicationPlan> = emptyList(), val events: List<DoseEvent> = emptyList(), val loaded: Boolean = false, val snoozes: List<SnoozedReminder> = emptyList()) {
    // 在 IO 刷新快照时只汇总一次，避免今日页面逐行反复扫描全部历史。
    val latest: Map<String, DoseEvent> = events.groupBy { it.key }.mapValues { it.value.maxBy { event -> event.id } }
    fun state(key: String): DoseEvent? = latest[key]?.takeUnless { it.action == DoseAction.UNDONE }
}

private class Database(context: Context) : SQLiteOpenHelper(context, "mediary.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE plans (id TEXT PRIMARY KEY, content TEXT NOT NULL)")
        db.execSQL("CREATE TABLE dose_events (id INTEGER PRIMARY KEY AUTOINCREMENT, occurrence_key TEXT NOT NULL, plan_id TEXT NOT NULL, name TEXT NOT NULL, dose TEXT NOT NULL, planned_at INTEGER, actual_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL, action TEXT NOT NULL)")
        db.execSQL("CREATE INDEX event_occurrence ON dose_events(occurrence_key, id)")
        db.execSQL("CREATE TABLE snoozes (occurrence_key TEXT PRIMARY KEY, plan_id TEXT NOT NULL, planned_at INTEGER NOT NULL, remind_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** 数据库只由本仓库访问；调用方在 IO 调度器执行，串行更新避免提醒按钮重复登记。 */
class MediaryRepository(context: Context) {
    private val helper = Database(context.applicationContext)
    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    @Synchronized fun refresh() {
        val db = helper.readableDatabase
        val plans = buildList {
            db.rawQuery("SELECT content FROM plans ORDER BY rowid DESC", null).use { cursor ->
                while (cursor.moveToNext()) add(decodePlan(JSONObject(cursor.getString(0))))
            }
        }
        val events = buildList {
            db.rawQuery("SELECT id,occurrence_key,plan_id,name,dose,planned_at,actual_at,recorded_at,action FROM dose_events ORDER BY id DESC", null).use { cursor ->
                while (cursor.moveToNext()) add(DoseEvent(
                    id = cursor.getLong(0), key = cursor.getString(1), planId = cursor.getString(2), name = cursor.getString(3), dose = cursor.getString(4),
                    plannedAt = if (cursor.isNull(5)) null else Instant.ofEpochMilli(cursor.getLong(5)), actualAt = Instant.ofEpochMilli(cursor.getLong(6)),
                    recordedAt = Instant.ofEpochMilli(cursor.getLong(7)), action = DoseAction.valueOf(cursor.getString(8)),
                ))
            }
        }
        val snoozes = buildList {
            db.rawQuery("SELECT occurrence_key,plan_id,planned_at,remind_at FROM snoozes", null).use { cursor ->
                while (cursor.moveToNext()) add(SnoozedReminder(cursor.getString(0), cursor.getString(1), Instant.ofEpochMilli(cursor.getLong(2)), Instant.ofEpochMilli(cursor.getLong(3))))
            }
        }
        mutableSnapshot.value = Snapshot(plans, events, true, snoozes)
    }

    @Synchronized fun save(plan: MedicationPlan) {
        plan.validate()
        // 同名启用计划先人工核对，避免重复导入医嘱后叠加提醒。
        require(snapshot.value.plans.none { it.id != plan.id && it.enabled && plan.enabled && it.name.trim().equals(plan.name.trim(), true) }) {
            "已有同名启用计划，请先核对或暂停原计划"
        }
        val saved = helper.writableDatabase.insertWithOnConflict("plans", null, ContentValues().apply {
            put("id", plan.id); put("content", encodePlan(plan).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(saved != -1L) { "计划未能保存，请重试" }
        refresh()
    }

    @Synchronized fun snooze(occurrence: Occurrence, remindAt: Instant) {
        check(snapshot.value.state(occurrence.key) == null) { "这次已有记录" }
        val saved = helper.writableDatabase.insertWithOnConflict("snoozes", null, ContentValues().apply {
            put("occurrence_key", occurrence.key); put("plan_id", occurrence.plan.id)
            put("planned_at", occurrence.plannedAt.toEpochMilli()); put("remind_at", remindAt.toEpochMilli())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(saved != -1L) { "稍后提醒未能保存，请重试" }
        refresh()
    }

    @Synchronized fun clearSnooze(key: String) {
        helper.writableDatabase.delete("snoozes", "occurrence_key = ?", arrayOf(key))
        refresh()
    }

    @Synchronized fun record(event: DoseEvent): Boolean {
        val previous = snapshot.value.state(event.key)
        if (event.action == DoseAction.UNDONE && previous == null) return false
        if (event.action != DoseAction.UNDONE && previous != null) return false
        require(!event.actualAt.isAfter(Instant.now().plusSeconds(60))) { "实际服药时间不能在未来" }
        helper.writableDatabase.insertOrThrow("dose_events", null, ContentValues().apply {
            put("occurrence_key", event.key); put("plan_id", event.planId); put("name", event.name); put("dose", event.dose)
            val plannedAt = event.plannedAt
            if (plannedAt == null) putNull("planned_at") else put("planned_at", plannedAt.toEpochMilli())
            put("actual_at", event.actualAt.toEpochMilli()); put("recorded_at", event.recordedAt.toEpochMilli()); put("action", event.action.name)
        })
        refresh()
        return true
    }

    fun exportJson(): String {
        val current = snapshot.value
        return JSONObject().apply {
            put("schemaVersion", 1); put("exportedAt", Instant.now().toString())
            put("plans", JSONArray(current.plans.map(::encodePlan)))
            put("events", JSONArray(current.events.map { event -> JSONObject().apply {
                put("id", event.id); put("occurrenceKey", event.key); put("planId", event.planId); put("name", event.name); put("dose", event.dose)
                put("plannedAt", event.plannedAt?.toString() ?: JSONObject.NULL); put("actualAt", event.actualAt.toString())
                put("recordedAt", event.recordedAt.toString()); put("action", event.action.name)
            } }))
        }.toString(2)
    }

    companion object {
        fun encodePlan(plan: MedicationPlan): JSONObject = JSONObject().apply {
            put("id", plan.id); put("name", plan.name); put("dose", plan.dose); put("instructions", plan.instructions); put("source", plan.source)
            put("kind", plan.kind.name); put("times", JSONArray(plan.times.map { it.toString() }))
            put("intervalHours", plan.intervalHours ?: JSONObject.NULL); put("anchor", plan.anchor?.toString() ?: JSONObject.NULL)
            put("start", plan.start.toString()); put("end", plan.end?.toString() ?: JSONObject.NULL); put("enabled", plan.enabled)
        }
        private fun decodePlan(json: JSONObject) = MedicationPlan(
            id = json.getString("id"), name = json.getString("name"), dose = json.getString("dose"), instructions = json.getString("instructions"), source = json.getString("source"),
            kind = ScheduleKind.valueOf(json.getString("kind")), times = json.getJSONArray("times").let { array -> (0 until array.length()).map { LocalTime.parse(array.getString(it)) } },
            intervalHours = if (json.isNull("intervalHours")) null else json.getInt("intervalHours"), anchor = if (json.isNull("anchor")) null else Instant.parse(json.getString("anchor")),
            start = LocalDate.parse(json.getString("start")), end = if (json.isNull("end")) null else LocalDate.parse(json.getString("end")), enabled = json.getBoolean("enabled"),
        )
    }
}
