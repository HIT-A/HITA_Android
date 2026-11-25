package cn.limpu.hita.benchmark

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.chat.ChatMessageRecord
import cn.limpu.hita.data.model.chat.ChatSession
import cn.limpu.hita.data.model.homework.HomeworkItem
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.chat.RoomChatSessionStore
import cn.limpu.hita.data.source.preference.OptionalTabStore
import cn.limpu.hita.ui.main.MainActivity
import com.google.gson.Gson
import com.limpu.style.ThemeTools
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only the separately signed benchmark APK has this synthetic-data entry point. */
class BenchmarkSeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = when (intent.getStringExtra("style")) {
            "glass" -> ThemeTools.STYLE.APPLE_GLASS
            "space" -> ThemeTools.STYLE.DEEP_SPACE
            "cyber" -> ThemeTools.STYLE.CYBER
            else -> ThemeTools.STYLE.CLASSIC
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { seed() }
            ThemeTools.setThemeStyle(this@BenchmarkSeedActivity, theme)
            ThemeTools.setThemeMode(this@BenchmarkSeedActivity, ThemeTools.MODE.LIGHT)
            startActivity(Intent(this@BenchmarkSeedActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun seed() {
        val now = System.currentTimeMillis()
        val date = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val fixture = getSharedPreferences("ux_benchmark_fixture", Context.MODE_PRIVATE)
        val database = AppDatabase.getDatabase(this)
        if (fixture.getString("day", null) != date.toString()) {
            database.runInTransaction {
                database.timetableDao().saveTimetableSync(Timetable().apply {
                    id = TABLE_ID
                    name = "UX benchmark timetable"
                    startTime = Timestamp(monday.atStartOfDay(zone).toInstant().toEpochMilli())
                    endTime = Timestamp(monday.plusWeeks(16).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
                })
                database.eventItemDao().saveEvents(List(160) { i ->
                    val day = monday.plusDays((i / 10).toLong())
                    val eventStart = day.atTime(8 + i % 10, 0).atZone(zone).toInstant().toEpochMilli()
                    EventItem().apply {
                        id = "ux-benchmark-event-$i"
                        type = EventItem.TYPE.OTHER
                        source = EventItem.SOURCE_MANUAL
                        timetableId = TABLE_ID
                        name = "UX benchmark event $i"
                        from = Timestamp(eventStart)
                        to = Timestamp(eventStart + 45 * 60_000)
                    }
                })
            }
            val chat = RoomChatSessionStore(database)
            val session = ChatSession(id = "ux-benchmark-chat", createdAt = now, updatedAt = now)
            chat.create(session)
            if (chat.restore(session.id).isEmpty()) {
                repeat(100) { index ->
                    chat.append(session.id, ChatMessageRecord(
                        role = if (index % 2 == 0) ChatMessageRecord.Role.USER else ChatMessageRecord.Role.ASSISTANT,
                        text = if (index % 2 == 0) "UX benchmark question $index" else
                            "## UX benchmark reply $index\n\n" + "- **Sample agenda** with locations and time ranges.\n".repeat(10),
                        timestampMs = now + index,
                        id = "ux-benchmark-message-$index",
                    ))
                }
            }
            check(fixture.edit().putString("day", date.toString()).commit())
        }
        val tasks = List(220) { i -> HomeworkItem(
            id = "ux-benchmark-todo-$i",
            title = "UX benchmark task $i",
            createdAt = now - i,
            plannedDate = date.toString(),
        ) }
        check(getSharedPreferences("timeline_homework_v1", Context.MODE_PRIVATE).edit()
            .putString("items", Gson().toJson(tasks)).commit())
        OptionalTabStore(this).apply { setHomework(true); setAgent(true); setInfo(false) }
        DisplayScopeResolver(application).persistSelection(DisplayScope.Single(TABLE_ID))
    }

    private companion object { const val TABLE_ID = "ux-benchmark-table" }
}
