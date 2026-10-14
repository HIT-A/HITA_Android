package cn.limpu.hita.data.timetableshare

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.AppDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableShareMigrationTest {
    private fun createLegacyDatabase(name: String, version: Int = 14): SupportSQLiteOpenHelper {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(name)
        val schema = instrumentation.context.assets.open("cn.limpu.hita.data.AppDatabase/$version.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) {
                            db.execSQL(indices.getJSONObject(j).getString("createSql")
                                .replace("$" + "{TABLE_NAME}", table))
                        }
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Unexpected legacy database upgrade")
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        )
    }

    @Test fun migrates14To17PreservingEveryPersonalColumn() {
        val name = "timetable-share-migration-test-v14"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacy = createLegacyDatabase(name)
        val before = try {
            val old = legacy.writableDatabase
            old.execSQL("INSERT INTO timetable(id,name,code,startTime,endTime,createdAt,scheduleStructure) VALUES('t','本人','2025-2026-2',1767225600000,1777593600000,123,'[]')")
            old.execSQL("INSERT INTO subject(id,name,timetableId,type,field,selectCategory,nature,credit,school,countInSPA,code,`key`,createdAt,color) VALUES('s','数学','t','COM_A','理工','必修','理论',3.5,'学院',1,'MATH','k',456,123)")
            old.execSQL("INSERT INTO events(id,type,source,name,place,teacher,subjectId,timetableId,`from`,`to`,fromNumber,lastNumber,created_at) VALUES('e','CLASS','EAS_IMPORT','数学','A101','老师','s','t',1767254400000,1767258000000,1,1,789)")
            personalRows(old)
        } finally { legacy.close() }
        val expected = before.mapValues { (table, rows) ->
            if (table == "timetable") rows.map { it + listOf<String?>(null) } else rows
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17).build()
        try {
            val db = migrated.openHelper.writableDatabase
            assertEquals(expected, personalRows(db))
            db.execSQL("INSERT INTO timetable_share_identity(timetableId,termKey,shareId) VALUES('t','2025-2026-2','share')")
            db.execSQL("INSERT INTO friend_timetable(shareId,nickname,remark,termName,startMillis,endMillis,mode,snapshotJson,contentDigest,importedAt,updatedAt) VALUES('friend','好友',NULL,'春季',1767225600000,1777593600000,'FULL','{}','digest',1,2)")
            db.query("SELECT shareId FROM timetable_share_identity").use { assertTrue(it.moveToFirst()); assertEquals("share", it.getString(0)) }
            db.query("SELECT nickname FROM friend_timetable").use { assertTrue(it.moveToFirst()); assertEquals("好友", it.getString(0)) }
            assertEquals(expected, personalRows(db))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun migrates15To17PreservingSharesAndAllowingGroups() {
        val name = "timetable-group-migration-test-v15"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacy = createLegacyDatabase(name, 15)
        try {
            legacy.writableDatabase.execSQL("INSERT INTO timetable_share_identity(timetableId,termKey,shareId) VALUES('t','term','share')")
            legacy.writableDatabase.execSQL("INSERT INTO friend_timetable(shareId,nickname,remark,termName,startMillis,endMillis,mode,snapshotJson,contentDigest,importedAt,updatedAt) VALUES('friend','好友','备注','春季',1,2,'FULL','{}','digest',3,4)")
        } finally { legacy.close() }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17).build()
        try {
            val db = migrated.openHelper.writableDatabase
            db.query("SELECT shareId FROM timetable_share_identity").use {
                assertTrue(it.moveToFirst()); assertEquals("share", it.getString(0))
            }
            db.query("SELECT remark FROM friend_timetable").use {
                assertTrue(it.moveToFirst()); assertEquals("备注", it.getString(0))
            }
            db.execSQL("INSERT INTO timetable_group(id,name,createdAt) VALUES('g','组合',1)")
            db.execSQL("INSERT INTO timetable(id,name,code,startTime,endTime,createdAt,scheduleStructure,groupId) VALUES('t','我的',NULL,1,2,3,'[]','g')")
            assertEquals("g", migrated.timetableDao().getTimetableByIdSync("t")?.groupId)
            db.query("PRAGMA index_list(timetable)").use { cursor ->
                val names = buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                assertTrue("index_timetable_groupId" in names)
            }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun migratesLegacyManualBatchIntoOneSubject() {
        val name = "manual-course-group-migration-test-v16"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacy = createLegacyDatabase(name, 16)
        try {
            val db = legacy.writableDatabase
            db.execSQL("INSERT INTO timetable(id,name,code,startTime,endTime,createdAt,groupId,scheduleStructure) VALUES('t','我的',NULL,1,2,3,NULL,'[]')")
            db.execSQL("INSERT INTO events(id,type,source,name,place,teacher,subjectId,timetableId,`from`,`to`,fromNumber,lastNumber,created_at) VALUES('e1','OTHER','MANUAL','自习','',NULL,'','t',1000,1100,1,2,100)")
            db.execSQL("INSERT INTO events(id,type,source,name,place,teacher,subjectId,timetableId,`from`,`to`,fromNumber,lastNumber,created_at) VALUES('e2','OTHER','MANUAL','自习','',NULL,'','t',604801000,604801100,1,2,101)")
            db.execSQL("INSERT INTO events(id,type,source,name,place,teacher,subjectId,timetableId,`from`,`to`,fromNumber,lastNumber,created_at) VALUES('other','OTHER','MANUAL','自习','',NULL,'','t',1209601000,1209601100,1,2,3001)")
        } finally { legacy.close() }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_16_17).build()
        try {
            val db = migrated.openHelper.writableDatabase
            db.query("SELECT id, type, subjectId FROM events ORDER BY id").use { cursor ->
                val rows = mutableMapOf<String, Pair<String, String>>()
                while (cursor.moveToNext()) {
                    rows[cursor.getString(0)] = cursor.getString(1) to cursor.getString(2)
                }
                val groupedId = rows.getValue("e1").second
                assertTrue(groupedId.isNotBlank())
                assertEquals("CLASS" to groupedId, rows["e1"])
                assertEquals("CLASS" to groupedId, rows["e2"])
                assertEquals("OTHER" to "", rows["other"])
                db.query("SELECT name FROM subject WHERE id = ?", arrayOf(groupedId)).use { subject ->
                    assertTrue(subject.moveToFirst())
                    assertEquals("自习", subject.getString(0))
                }
            }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
