package cn.limpu.hita.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.TermItem
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the synchronous production cache boundary so injected SQL failures stay on the test thread. */
@RunWith(AndroidJUnit4::class)
class ClassroomQueryCacheInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var cache: ClassroomQueryCache
    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")
    private val building = BuildingItem().apply { id = "building"; name = "正心楼" }
    private val week = 6

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        cache = ClassroomQueryCache(db.classroomCacheDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savedRowsRoundTripFieldsAndRestoreRoomIdFromName() {
        val classroom = room("正心 101").apply {
            id = "remote-only-id"
            capacity = 120
            specialClassroom = "阶梯"
            scheduleList += JSONObject().put("day", 2).put("period", "3-4")
        }
        val before = System.currentTimeMillis()

        cache.save(term, building, week, listOf(classroom))

        val row = cache.readRows(term, building, week).single()
        assertEquals(building.id, row.buildingId)
        assertEquals(building.name, row.buildingName)
        assertEquals(term.yearCode, row.termYearCode)
        assertEquals(term.termCode, row.termTermCode)
        assertEquals(week, row.week)
        assertTrue(row.cachedAt in before..System.currentTimeMillis())
        val restored = ClassroomQueryCache.decode(listOf(row)).single()
        assertEquals("正心 101", restored.id)
        assertEquals(classroom.name, restored.name)
        assertEquals(classroom.capacity, restored.capacity)
        assertEquals(classroom.specialClassroom, restored.specialClassroom)
        assertEquals(2, restored.scheduleList.single().getInt("day"))
        assertEquals("3-4", restored.scheduleList.single().getString("period"))
    }

    @Test
    fun saveReplacesOnlyTheMatchingBuildingTermAndWeek() {
        val rows = listOf(
            row("old"),
            row("other-building").copy(buildingId = "other"),
            row("other-year").copy(termYearCode = "2025-2026"),
            row("other-term").copy(termTermCode = "2"),
            row("other-week").copy(week = 7),
        )
        db.classroomCacheDao().saveAllSync(rows)

        cache.save(term, building, week, listOf(room("new-one"), room("new-two")))

        assertEquals(setOf("new-one", "new-two"), cache.readRows(term, building, week).map { it.name }.toSet())
        assertEquals(rows.drop(1).toSet(), db.classroomCacheDao().getAllSync().filter { it.name.startsWith("other-") }.toSet())
    }

    @Test
    fun emptySuccessClearsOnlyItsQueryAndDoesNotRunRetentionCleanup() {
        val oldOther = row("expired-other").copy(buildingId = "other", cachedAt = 1L)
        db.classroomCacheDao().saveAllSync(listOf(row("old"), oldOther))

        cache.save(term, building, week, emptyList())

        assertTrue(cache.readRows(term, building, week).isEmpty())
        assertEquals(listOf(oldOther), db.classroomCacheDao().getAllSync())
    }

    @Test
    fun nonemptySavePrunesRowsOlderThan180DaysAcrossQueries() {
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        val expired = row("expired").copy(buildingId = "expired", cachedAt = now - 181 * day)
        val retained = row("retained").copy(buildingId = "retained", cachedAt = now - 179 * day)
        db.classroomCacheDao().saveAllSync(listOf(expired, retained))

        cache.save(term, building, week, listOf(room("fresh")))

        assertEquals(setOf("fresh", "retained"), db.classroomCacheDao().getAllSync().map { it.name }.toSet())
        assertEquals(retained, db.classroomCacheDao().getAllSync().single { it.name == "retained" })
    }

    @Test
    fun nullBuildingNameAndNullableSpecialClassroomKeepTheirStoredRepresentation() {
        building.name = null
        val classroom = room("101").apply { specialClassroom = null }

        cache.save(term, building, week, listOf(classroom))

        val row = cache.readRows(term, building, week).single()
        assertEquals("", row.buildingName)
        assertEquals(null, row.specialClassroom)
        assertEquals(null, ClassroomQueryCache.decode(listOf(row)).single().specialClassroom)
    }

    @Test
    fun nonObjectScheduleEntriesRestoreAsEmptyObjects() {
        val row = row("101").copy(scheduleJson = "[{\"day\":1},null,2,\"text\"]")
        db.classroomCacheDao().saveAllSync(listOf(row))

        val restored = ClassroomQueryCache.decode(cache.readRows(term, building, week)).single()

        assertEquals(4, restored.scheduleList.size)
        assertEquals(1, restored.scheduleList.first().getInt("day"))
        assertTrue(restored.scheduleList.drop(1).all { it.length() == 0 })
    }

    @Test
    fun invalidScheduleJsonStillThrowsAtTheSynchronousDecodeBoundary() {
        db.classroomCacheDao().saveAllSync(listOf(row("broken").copy(scheduleJson = "not-json")))

        val failure = runCatching { ClassroomQueryCache.decode(cache.readRows(term, building, week)) }.exceptionOrNull()

        assertTrue(failure is JSONException)
        assertEquals("not-json", cache.readRows(term, building, week).single().scheduleJson)
    }

    @Test
    fun failedReplacementRetainsTheCompletePreviousBatch() {
        val original = listOf(row("old-one"), row("old-two"))
        db.classroomCacheDao().saveAllSync(original)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_classroom_insert BEFORE INSERT ON classroom_cache " +
                "WHEN NEW.name = 'reject-new' BEGIN SELECT RAISE(ABORT, 'injected classroom failure'); END"
        )

        val failure = runCatching {
            cache.save(term, building, week, listOf(room("new-one"), room("reject-new")))
        }.exceptionOrNull()

        assertTrue("injected SQL failure must reach the caller", failure?.message.orEmpty().contains("injected classroom failure"))
        assertEquals("failed replacement must retain every old room", original.toSet(), cache.readRows(term, building, week).toSet())
    }

    private fun room(name: String) = ClassroomItem().apply {
        this.name = name
        id = name
        capacity = 60
    }

    private fun row(name: String) = ClassroomCacheEntity(
        building.id, building.name.orEmpty(), term.yearCode, term.termCode, week,
        name, 60, null, "[]", System.currentTimeMillis(),
    )
}
