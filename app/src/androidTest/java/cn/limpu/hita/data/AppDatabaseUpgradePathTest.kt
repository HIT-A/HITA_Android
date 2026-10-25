package cn.limpu.hita.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.timetable.EventItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 已发布版本的数据库升级到当前版本：Room 打开时按当前 schema 校验列/索引/外键，表集合与导出 JSON 一致，
 * 且个人数据逐字段保留。
 * 发版若升了数据库版本，把新的 [AppDatabase.VERSION] 加入 [SHIPPED_VERSIONS]。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseUpgradePathTest {
    companion object {
        /**
         * cn.limpu.hita 包名发布过的数据库版本（v2.4.1 起；更早的包名是另一个应用，不会升级到本包）：
         * 9 = v2.4.1–v2.5.3，11 = v2.5.4–2.8.0，14 = v3.0.0–v3.0.3，17 = v3.1.0 起。
         */
        private val SHIPPED_VERSIONS = listOf(9, 11, 14, 17)
        private const val DB_NAME = "upgrade-path-test"
        private const val START = 1788105600000L
        private const val STRUCTURE = """[{"from":{"hour":8,"minute":30},"to":{"hour":10,"minute":15}}]"""
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After fun tearDown() {
        instrumentation.targetContext.deleteDatabase(DB_NAME)
    }

    @Test fun shippedVersionsUpgradeToCurrentAndKeepPersonalData() {
        val olderVersions = SHIPPED_VERSIONS.filter { it < AppDatabase.VERSION }
        assertTrue(olderVersions.isNotEmpty())
        for (version in olderVersions) {
            seed(version)
            assertMigratedDatabase(version)
        }
    }

    /** 9/11/14 中 timetable、subject、events、chat_* 列完全一致，同一份种子数据适用。 */
    private fun seed(version: Int) {
        val legacy = createLegacyAppDatabase(DB_NAME, version)
        try {
            val db = legacy.writableDatabase
            db.execSQL(
                "INSERT INTO timetable (id, name, code, startTime, endTime, createdAt, scheduleStructure) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("table", "秋季课表", "BENBU:2026-2027-1", START, START + 120 * 86400000L, 1L, STRUCTURE)
            )
            db.execSQL(
                "INSERT INTO subject (id, name, timetableId, type, field, selectCategory, nature, credit, school, countInSPA, code, `key`, createdAt, color) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("subject", "操作系统", "table", "COM_A", "专业", "必修", "核心", 3.5, "计算机学院", 1, "CS101", "k1", 2L, 0xFF3366)
            )
            event(db, "imported", "CLASS", EventItem.SOURCE_EAS_IMPORT, "A101")
            event(db, "manual", "CLASS", EventItem.SOURCE_MANUAL, "图书馆")
            event(db, "exam", "EXAM", EventItem.SOURCE_EAS_IMPORT, "考场 1")
            db.execSQL(
                "INSERT INTO chat_session (id, title, createdAt, updatedAt) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>("session", "选课咨询", 10L, 20L)
            )
            db.execSQL(
                "INSERT INTO chat_message (id, sessionId, role, text, resourceCardsJson, timestampMs) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("message", "session", "USER", "明天有什么课？", "[]", 15L)
            )
        } finally {
            legacy.close()
        }
    }

    private fun event(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String, type: String, source: String, place: String) {
        db.execSQL(
            "INSERT INTO events (id, type, source, name, place, teacher, subjectId, timetableId, `from`, `to`, fromNumber, lastNumber, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, type, source, "操作系统-$id", place, "教师", "subject", "table",
                START + 8 * 3600000L, START + 10 * 3600000L, 1, 2, 3L)
        )
    }

    /** 用应用实际的构建方式打开：首次访问执行迁移，并由 Room 按当前 schema 校验。 */
    private fun assertMigratedDatabase(version: Int) {
        val db = Room.databaseBuilder(instrumentation.targetContext, AppDatabase::class.java, DB_NAME)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val message = "from v$version"
            val tables = db.openHelper.writableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                    generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
                }.filterNot { it == "android_metadata" || it == "room_master_table" || it.startsWith("sqlite_") }
            assertEquals("$message：迁移后不应残留或缺少表", legacySchemaTables(AppDatabase.VERSION), tables.toSet())

            val table = requireNotNull(db.timetableDao().getTimetableByIdSync("table")) { message }
            assertEquals(message, "秋季课表", table.name)
            assertEquals(message, "BENBU:2026-2027-1", table.code)
            assertEquals(message, START, table.startTime.time)
            assertEquals(message, START + 120 * 86400000L, table.endTime.time)
            assertNull(message, table.groupId)
            val period = table.scheduleStructure.single()
            assertEquals(message, listOf(8, 30, 10, 15), listOf(period.from.hour, period.from.minute, period.to.hour, period.to.minute))

            val subject = db.subjectDao().getSubjectsSync("table").single()
            assertEquals(message, listOf("subject", "操作系统", "CS101", "专业", "必修", "核心"),
                listOf(subject.id, subject.name, subject.code, subject.field, subject.selectCategory, subject.nature))
            assertEquals(message, 3.5f, subject.credit, 0f)
            assertEquals(message, 0xFF3366, subject.color)

            val events = db.eventItemDao().getEventsOfTimetableSync("table").associateBy { it.id }
            assertEquals(message, setOf("imported", "manual", "exam"), events.keys)
            for ((id, type, source, place) in listOf(
                listOf("imported", EventItem.TYPE.CLASS, EventItem.SOURCE_EAS_IMPORT, "A101"),
                listOf("manual", EventItem.TYPE.CLASS, EventItem.SOURCE_MANUAL, "图书馆"),
                listOf("exam", EventItem.TYPE.EXAM, EventItem.SOURCE_EAS_IMPORT, "考场 1"),
            )) {
                val event = events.getValue(id as String)
                assertEquals("$message $id", listOf(type, source, "操作系统-$id", place, "教师", "subject"),
                    listOf(event.type, event.source, event.name, event.place, event.teacher, event.subjectId))
                assertEquals("$message $id", listOf(START + 8 * 3600000L, START + 10 * 3600000L),
                    listOf(event.from.time, event.to.time))
                assertEquals("$message $id", listOf(1, 2), listOf(event.fromNumber, event.lastNumber))
            }

            val session = requireNotNull(db.chatSessionDao().getLatest()) { message }
            assertEquals(message, listOf("session", "选课咨询"), listOf(session.id, session.title))
            assertEquals(message, listOf(10L, 20L), listOf(session.createdAt, session.updatedAt))
            val chat = db.chatMessageDao().getBySessionSync("session").single()
            assertEquals(message, listOf("message", "USER", "明天有什么课？", "[]"),
                listOf(chat.id, chat.role, chat.text, chat.resourceCardsJson))
            assertEquals(message, 15L, chat.timestampMs)
        } finally {
            db.close()
        }
    }
}
