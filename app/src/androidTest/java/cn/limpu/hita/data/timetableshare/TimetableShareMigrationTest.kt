package cn.limpu.hita.data.timetableshare

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableShareMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrates14To15PreservingEveryPersonalColumn() {
        val name = "timetable-share-migration"
        val old = helper.createDatabase(name, 14)
        old.execSQL("INSERT INTO timetable(id,name,code,startTime,endTime,createdAt,scheduleStructure) VALUES('t','本人','2025-2026-2',1767225600000,1777593600000,123,'[]')")
        old.execSQL("INSERT INTO subject(id,name,timetableId,type,field,selectCategory,nature,credit,school,countInSPA,code,`key`,createdAt,color) VALUES('s','数学','t','COM_A','理工','必修','理论',3.5,'学院',1,'MATH','k',456,123)")
        old.execSQL("INSERT INTO events(id,type,source,name,place,teacher,subjectId,timetableId,`from`,`to`,fromNumber,lastNumber,created_at) VALUES('e','CLASS','EAS_IMPORT','数学','A101','老师','s','t',1767254400000,1767258000000,1,1,789)")
        val before = personalRows(old)
        old.close()
        val migrated = helper.runMigrationsAndValidate(name, 15, true, AppDatabase.MIGRATION_14_15)
        try {
            assertEquals(before, personalRows(migrated))
            migrated.execSQL("INSERT INTO timetable_share_identity(timetableId,termKey,shareId) VALUES('t','2025-2026-2','share')")
            migrated.execSQL("INSERT INTO friend_timetable(shareId,nickname,remark,termName,startMillis,endMillis,mode,snapshotJson,contentDigest,importedAt,updatedAt) VALUES('friend','好友',NULL,'春季',1767225600000,1777593600000,'FULL','{}','digest',1,2)")
            migrated.query("SELECT shareId FROM timetable_share_identity").use { assertTrue(it.moveToFirst()); assertEquals("share", it.getString(0)) }
            migrated.query("SELECT nickname FROM friend_timetable").use { assertTrue(it.moveToFirst()); assertEquals("好友", it.getString(0)) }
            assertEquals(before, personalRows(migrated))
        } finally { migrated.close() }
    }
}
