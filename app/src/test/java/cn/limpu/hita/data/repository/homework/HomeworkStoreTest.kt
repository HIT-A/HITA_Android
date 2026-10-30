package cn.limpu.hita.data.repository.homework

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HomeworkStoreTest {
    @Test fun unfinishedAndCompletedSurviveRestart() {
        var disk: String? = null
        val store = HomeworkStore({ disk }, { disk = it; true })
        val first = store.add("  操作系统实验报告  ", 1).single()
        store.add("数值分析习题", 2)
        store.setCompleted(first.id, true)
        val restored = HomeworkStore({ disk }, { error("read only") }).load()
        assertEquals(2, restored.size)
        assertEquals("操作系统实验报告", restored.first().title)
        assertTrue(restored.first().completed)
        assertFalse(restored.last().completed)
    }

    @Test fun sameTitleHasIndependentCompletion() {
        val store = HomeworkStore({ null }, { true })
        val first = store.add("习题", 1).single()
        val second = store.add("习题", 2).last()
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(true, false), store.setCompleted(first.id, true).map { it.completed })
    }

    @Test fun failedCommitKeepsPreviousSnapshotAndCanRetry() {
        var writable = true
        val store = HomeworkStore({ null }, { writable })
        val first = store.add("已有作业", 1).single()
        writable = false
        assertThrows(IOException::class.java) { store.setCompleted(first.id, true) }
        assertFalse(store.load().single().completed)
        assertThrows(IOException::class.java) { store.add("不能保存", 2) }
        assertEquals(1, store.load().size)
        writable = true
        assertTrue(store.setCompleted(first.id, true).single().completed)
    }

    @Test fun corruptStorageIsNeverSilentlyOverwritten() {
        var writes = 0
        val store = HomeworkStore({ "not json" }, { writes++; true })
        assertThrows(Exception::class.java) { store.load() }
        assertThrows(Exception::class.java) { store.add("新作业", 1) }
        assertEquals(0, writes)
    }

    @Test fun rejectsNullAndDuplicateRecords() {
        for (raw in listOf("null", "[null]",
            """[{"id":"x","title":"a","createdAt":1},{"id":"x","title":"b","createdAt":2}]""",
            """[{"id":"x","title":null,"createdAt":1}]""")) {
            val store = HomeworkStore({ raw }, { error("must not overwrite") })
            assertThrows(Exception::class.java) { store.load() }
        }
    }

    @Test fun emptyOrOversizedTitlesDoNotWrite() {
        var writes = 0
        val store = HomeworkStore({ null }, { writes++; true })
        for (title in listOf("   ", "字".repeat(201))) {
            assertThrows(IllegalArgumentException::class.java) { store.add(title, 1) }
        }
        assertEquals(0, writes)
    }

    @Test fun completionCanBeUndoneAndUnknownIdDoesNotCreateItems() {
        var writes = 0
        val store = HomeworkStore({ null }, { writes++; true })
        val item = store.add("作业", 1).single()
        store.setCompleted(item.id, true)
        assertFalse(store.setCompleted(item.id, false).single().completed)
        val previous = writes
        assertEquals(listOf(item), store.setCompleted("missing", true))
        assertEquals(previous, writes)
    }

    @Test fun clearingCompletedKeepsUnfinishedItemsAfterRestart() {
        var disk: String? = null
        val store = HomeworkStore({ disk }, { disk = it; true })
        val done = store.add("已完成", 1).single()
        val pending = store.add("未完成", 2).last()
        store.setCompleted(done.id, true)
        store.clearCompleted()
        assertEquals(listOf(pending), HomeworkStore({ disk }, { true }).load())
    }
    @Test fun legacyUndatedTasksAndPlannedDatesSurviveEditingAndRestart() {
        var disk: String? = """[{"id":"legacy","title":"旧作业","createdAt":1}]"""
        val store = HomeworkStore({ disk }, { disk = it; true })
        assertNull(store.load().single().plannedDate)
        val tomorrow = store.add("明天的作业", 2, "2026-10-31").last()
        store.setCompleted(tomorrow.id, true)
        store.edit("legacy", "补上日期", "2026-10-30")
        val restored = HomeworkStore({ disk }, { error("read only") }).load()
        assertEquals(listOf("2026-10-30", "2026-10-31"), restored.map { it.plannedDate })
        assertTrue(restored.last().completed)
        store.edit(tomorrow.id, "改期", "2026-11-01")
        assertTrue(store.load().last().completed)
        assertEquals("2026-11-01", store.load().last().plannedDate)
        store.delete("legacy")
        assertEquals(tomorrow.id, HomeworkStore({ disk }, { true }).load().single().id)
    }

    @Test fun failedRescheduleAndDeleteKeepOriginalDateTitleAndCompletion() {
        var writable = true
        val store = HomeworkStore({ null }, { writable })
        val item = store.add("今天", 1, "2026-10-30").single()
        writable = false
        assertThrows(IOException::class.java) { store.edit(item.id, "明天", "2026-10-31") }
        assertThrows(IOException::class.java) { store.delete(item.id) }
        assertEquals(item, store.load().single())
        writable = true
        assertEquals("2026-10-31", store.edit(item.id, "明天", "2026-10-31").single().plannedDate)
    }

    @Test fun invalidOrNonCanonicalDatesNeverOverwriteStorage() {
        var writes = 0
        val store = HomeworkStore({ null }, { writes++; true })
        for (date in listOf("", "2026-02-29", "2026-1-01", "0000-01-01", "2026-10-30T12:00:00")) {
            assertThrows(Exception::class.java) { store.add("作业", 1, date) }
        }
        assertEquals(0, writes)
        val corrupt = HomeworkStore({
            """[{"id":"x","title":"作业","createdAt":1,"plannedDate":"yesterday"}]"""
        }, { writes++; true })
        assertThrows(Exception::class.java) { corrupt.load() }
        assertEquals(0, writes)
        assertEquals("2028-02-29", store.add("闰日", 1, "2028-02-29").single().plannedDate)
    }

}
