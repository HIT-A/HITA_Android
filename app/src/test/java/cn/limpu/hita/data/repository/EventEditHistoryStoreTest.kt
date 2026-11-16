package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventEditHistoryEntity
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.source.dao.EventEditHistoryDao
import cn.limpu.hita.data.source.dao.EventItemDao
import java.sql.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学生删错课时，教务导入的课必须还能找回来。
 *
 * 这里只验证 [EventEditHistoryStore] 的语义：软隐藏与物理删除的分流、以及历史行可回放。
 * 真实 Room 事务由 instrumentation 覆盖，JVM 测试用内存 fake 锁住规则。
 */
class EventEditHistoryStoreTest {

    private val timetableId = "tt-1"
    private val clock = { 1_700_000_000_000L }

    @Test
    fun `eas imported course is hidden instead of deleted and can be restored`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val course = course(id = "eas-1", source = EventItem.SOURCE_EAS_IMPORT)
        dao.seed(course)
        val store = store(dao, history)

        assertTrue(store.delete(listOf(course.id)))

        // 软隐藏：行还在表里，只是 hidden = 1，读取方看不到。
        val hidden = dao.hiddenOf(course.id)
        assertEquals(1, hidden)
        val entry = history.historyOfTimetableSync(timetableId).single()
        assertEquals(EventEditHistoryStore.ACTION_DELETE, entry.action)

        val restored = store.restore(entry.id)

        assertNotNull(restored)
        assertEquals(0, dao.hiddenOf(course.id))
    }

    @Test
    fun `manual course is physically deleted but stays recoverable through history`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val course = course(id = "manual-1", source = EventItem.SOURCE_MANUAL)
        dao.seed(course)
        val store = store(dao, history)

        assertTrue(store.delete(listOf(course.id)))

        assertEquals(null, dao.hiddenOf(course.id))
        val entry = history.historyOfTimetableSync(timetableId).single()

        val restored = store.restore(entry.id)

        assertNotNull(restored)
        assertEquals(course.id, restored?.id)
        assertEquals(0, dao.hiddenOf(course.id))
    }

    @Test
    fun `deleting an unknown id writes no history`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)

        assertEquals(false, store.delete(listOf("missing")))
        assertEquals(0, history.countSync())
    }

    @Test
    fun `deleting a whole course hides every occurrence of that subject`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val first = course(id = "eas-a1", source = EventItem.SOURCE_EAS_IMPORT).apply { subjectId = "s1" }
        val second = course(id = "eas-a2", source = EventItem.SOURCE_EAS_IMPORT).apply { subjectId = "s1" }
        val other = course(id = "eas-b1", source = EventItem.SOURCE_EAS_IMPORT).apply { subjectId = "s2" }
        dao.seed(first)
        dao.seed(second)
        dao.seed(other)
        val store = store(dao, history)

        assertTrue(store.deleteSubject("s1", timetableId))

        // 同一门课的所有课次都隐藏，别的课程不受影响。
        assertEquals(1, dao.hiddenOf("eas-a1"))
        assertEquals(1, dao.hiddenOf("eas-a2"))
        assertEquals(0, dao.hiddenOf("eas-b1"))
        assertEquals(2, history.countSync())
    }

    @Test
    fun `deleting an unknown subject reports no change`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)

        assertEquals(false, store.deleteSubject("missing", timetableId))
        assertEquals(0, history.countSync())
    }

    @Test
    fun `restored deletion remains visible but cannot be replayed over later edits`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)
        dao.seed(course("manual", EventItem.SOURCE_MANUAL))
        store.delete(listOf("manual"))
        val id = store.deletedHistory(timetableId).single().id
        assertEquals(DeletedEventStatus.AVAILABLE, store.deletedHistory(timetableId).single().status)
        assertNotNull(store.restore(id, timetableId))
        dao.seed(course("manual", EventItem.SOURCE_MANUAL).apply { name = "后来修改的课程" })

        assertEquals(DeletedEventStatus.RESTORED, store.deletedHistory(timetableId).single().status)
        assertEquals(null, store.restore(id, timetableId))
        assertEquals("后来修改的课程", dao.dao.getEventsInIdsIncludingHiddenSync(listOf("manual")).single().name)
        assertEquals(2, history.countSync())
    }

    @Test
    fun `changed hidden course is never overwritten by an older deletion`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)
        val event = course("eas", EventItem.SOURCE_EAS_IMPORT)
        dao.seed(event)
        store.delete(listOf(event.id))
        event.place = "新的教室"
        val row = store.deletedHistory(timetableId).single()

        assertEquals(DeletedEventStatus.CHANGED, row.status)
        assertEquals(null, store.restore(row.id))
        assertEquals(1, event.hidden)
        assertEquals("新的教室", event.place)
        assertEquals(1, history.countSync())
    }

    @Test
    fun `recreated manual course blocks restoration even when its id matches`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)
        val event = course("manual", EventItem.SOURCE_MANUAL)
        dao.seed(event)
        store.delete(listOf(event.id))
        dao.seed(event)
        val row = store.deletedHistory(timetableId).single()
        assertEquals(DeletedEventStatus.CHANGED, row.status)
        assertEquals(null, store.restore(row.id))
        assertEquals(1, history.countSync())
    }

    @Test
    fun `a newer delete prevents restoring an older version of the same absent event`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)
        val event = course("manual", EventItem.SOURCE_MANUAL)
        dao.seed(event)
        store.delete(listOf(event.id))
        val oldId = store.deletedHistory(timetableId).single().id
        event.name = "新版课程"
        dao.seed(event)
        store.delete(listOf(event.id))
        val rows = store.deletedHistory(timetableId)
        assertEquals(DeletedEventStatus.CHANGED, rows.first { it.id == oldId }.status)
        assertEquals(null, store.restore(oldId))
        val latest = rows.first { it.id != oldId }
        assertEquals(DeletedEventStatus.AVAILABLE, latest.status)
        assertEquals("新版课程", store.restore(latest.id)?.name)
    }

    @Test
    fun `hidden rows cannot be deleted repeatedly to create unsafe snapshots`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)
        dao.seed(course("eas", EventItem.SOURCE_EAS_IMPORT))
        assertTrue(store.delete(listOf("eas")))
        assertEquals(false, store.delete(listOf("eas")))
        assertEquals(1, history.countSync())
        assertEquals(DeletedEventStatus.AVAILABLE, store.deletedHistory(timetableId).single().status)
    }

    @Test
    fun `history from another timetable and non-delete actions cannot restore`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)
        val event = course("manual", EventItem.SOURCE_MANUAL)
        store.recordCreate(event)
        val createId = history.historyOfTimetableSync(timetableId).single().id
        assertEquals(null, store.restore(createId))
        dao.seed(event)
        store.delete(listOf(event.id))
        val row = store.deletedHistory(timetableId).single()
        assertEquals(null, store.restore(row.id, "another-table"))
        assertEquals(null, dao.hiddenOf(event.id))
        assertEquals(2, history.countSync())
    }

    @Test
    fun `invalid stored JSON is listed as unavailable without aborting the list`() {
        val dao = FakeEventItemDao()
        val history = FakeHistoryDao()
        val store = store(dao, history)
        history.insert(EventEditHistoryEntity("broken", timetableId, "e", EventEditHistoryStore.ACTION_DELETE,
            "{broken", null, clock()))
        val row = store.deletedHistory(timetableId).single()
        assertEquals(DeletedEventStatus.UNAVAILABLE, row.status)
        assertEquals(null, store.restore(row.id))
        assertEquals(1, history.countSync())
    }

    private fun store(dao: FakeEventItemDao, history: FakeHistoryDao) = EventEditHistoryStore(
        eventDao = dao.dao,
        historyDao = history,
        clock = clock,
        ids = { "history-${history.nextId++}" },
    )

    private fun course(
        id: String,
        source: String,
    ) = EventItem().apply {
        this.id = id
        this.timetableId = this@EventEditHistoryStoreTest.timetableId
        name = "高等数学"
        place = "A101"
        fromNumber = 1
        lastNumber = 2
        from = Timestamp(clock() + 3_600_000L)
        to = Timestamp(clock() + 7_200_000L)
        this.source = source
    }

    /**
     * 只实现被 [EventEditHistoryStore] 用到的 DAO 方法。
     *
     * [EventItemDao] 有几十个查询方法，逐一 stub 没有价值；用动态代理只拦截真正调用的
     * [getEventsInIdsIncludingHiddenSync]、[hideEventsInIdsSync]、[deleteEventsInIdsSync]、
     * [updateEventSync] 与 [insertEventSync]，其余方法一旦被误用立刻失败。
     */
    private class FakeEventItemDao {
        private val rows = mutableMapOf<String, EventItem>()

        @Suppress("UNCHECKED_CAST")
        private val delegate: EventItemDao = java.lang.reflect.Proxy.newProxyInstance(
            EventItemDao::class.java.classLoader,
            arrayOf(EventItemDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getEventsInIdsIncludingHiddenSync" ->
                    (args[0] as List<String>).mapNotNull { rows[it] }
                "getClassesOfSubjectSync" ->
                    rows.values.filter { it.subjectId == args[0] && it.hidden == 0 }
                "hideEventsInIdsSync" -> {
                    val ids = args[0] as List<String>
                    var changed = 0
                    ids.forEach { id ->
                        val row = rows[id]
                        if (row != null && row.hidden == 0) {
                            row.hidden = 1
                            changed++
                        }
                    }
                    changed
                }
                "deleteEventsInIdsSync" -> {
                    (args[0] as List<String>).forEach { rows.remove(it) }
                    null
                }
                "updateEventSync", "insertEventSync" -> {
                    val event = args[0] as EventItem
                    rows[event.id] = event
                    null
                }
                else -> throw UnsupportedOperationException("unexpected dao call: ${method.name}")
            }
        } as EventItemDao

        fun seed(event: EventItem) {
            rows[event.id] = event
        }

        fun hiddenOf(id: String): Int? = rows[id]?.hidden

        val dao: EventItemDao get() = delegate
    }

    private class FakeHistoryDao : EventEditHistoryDao {
        var nextId = 0
        private val rows = mutableListOf<EventEditHistoryEntity>()

        override fun insert(entry: EventEditHistoryEntity) {
            rows += entry
        }

        override fun historyOfTimetableSync(timetableId: String): List<EventEditHistoryEntity> =
            rows.filter { it.timetableId == timetableId }

        override fun getByIdSync(id: String): EventEditHistoryEntity? = rows.firstOrNull { it.id == id }

        override fun markDeletionRestoredSync(id: String): Int {
            val index = rows.indexOfFirst { it.id == id && it.action == EventEditHistoryStore.ACTION_DELETE }
            if (index < 0) return 0
            rows[index] = rows[index].copy(action = EventEditHistoryStore.ACTION_DELETE_RESTORED)
            return 1
        }

        override fun deleteOldestSync(overflow: Int) {
            rows.sortBy { it.createdAt }
            repeat(overflow.coerceAtMost(rows.size)) { rows.removeAt(0) }
        }

        override fun countSync(): Int = rows.size
    }
}
