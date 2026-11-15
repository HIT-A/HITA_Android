package cn.limpu.hita.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRetentionTest {

    private fun snapshot(
        id: String,
        createdAtMillis: Long,
        ownerKey: String = "owner-a",
        termId: String = "2025-2026-1",
        note: String = "",
    ) = TimetableVersionSnapshot(
        id = id,
        ownerKey = ownerKey,
        termId = termId,
        createdAtMillis = createdAtMillis,
        kind = TimetableSnapshotKind.BEFORE_REFRESH,
        timetable = SnapshotTimetable(
            id = "t1",
            name = "课表",
            code = "2025-2026-1",
            startMillis = 0L,
            endMillis = 0L,
            createdAtMillis = createdAtMillis,
            schedule = emptyList(),
        ),
        subjects = emptyList(),
        events = emptyList(),
        fingerprint = "fp-$id",
        note = note,
    )

    @Test
    fun `retention keeps the newest entries and drops the older ones`() {
        val all = (1..5).map { snapshot("s$it", createdAtMillis = it.toLong() * 100) }

        val kept = SnapshotRetention.apply(all, ownerKey = "owner-a", termId = "2025-2026-1", limit = 3)

        assertEquals(listOf("s3", "s4", "s5"), kept.map { it.id })
        assertEquals(2, SnapshotRetention.removedCount(all, "owner-a", "2025-2026-1", limit = 3))
    }

    @Test
    fun `retention never touches another term or another account`() {
        val all = listOf(
            snapshot("a1", 100),
            snapshot("a2", 200),
            snapshot("b1", 150, termId = "2025-2026-2"),
            snapshot("o1", 120, ownerKey = "owner-b"),
        )

        val kept = SnapshotRetention.apply(all, ownerKey = "owner-a", termId = "2025-2026-1", limit = 1)

        assertEquals(listOf("a2", "b1", "o1"), kept.map { it.id })
    }

    @Test
    fun `deleting one snapshot leaves the others readable`() {
        val store = FakeSnapshotStore(
            listOf(snapshot("s1", 100), snapshot("s2", 200), snapshot("s3", 300)),
        )

        val deleted = store.delete("owner-a", "2025-2026-1", "s2")

        assertEquals(true, deleted)
        assertEquals(listOf("s1", "s3"), store.all().map { it.id })
        assertEquals(2, store.all().size)
    }

    @Test
    fun `deleting an unknown snapshot reports no change`() {
        val store = FakeSnapshotStore(listOf(snapshot("s1", 100)))

        assertEquals(false, store.delete("owner-a", "2025-2026-1", "missing"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `blank note is stored as no note`() {
        assertEquals("", SnapshotRetention.normalizedNote("   "))
        assertEquals("期末备份", SnapshotRetention.normalizedNote("  期末备份 "))
    }

    @Test
    fun `retention ceiling allows every preset the settings dialog offers`() {
        // 设置页的最高档必须能被接受，且不超过上限本身。
        val highest = SnapshotRetention.apply(
            snapshots = (1..50).map { snapshot("s$it", createdAtMillis = it.toLong() * 100) },
            ownerKey = "owner-a",
            termId = "2025-2026-1",
            limit = SnapshotRetention.MAX_LIMIT,
        )

        assertEquals(SnapshotRetention.MAX_LIMIT, highest.size)
        assertTrue(SnapshotRetention.MAX_LIMIT >= SnapshotRetention.DEFAULT_LIMIT)
    }

    /** 只保留备注与删除的语义，不触碰文件系统。 */
    private class FakeSnapshotStore(initial: List<TimetableVersionSnapshot>) {
        private var items = initial

        fun all() = items

        fun delete(ownerKey: String, termId: String, snapshotId: String): Boolean {
            val next = items.filterNot {
                it.ownerKey == ownerKey && it.termId == termId && it.id == snapshotId
            }
            if (next.size == items.size) return false
            items = next
            return true
        }
    }
}
