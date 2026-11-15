package cn.limpu.hita.data.source.preference

import cn.limpu.hita.data.work.MemoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceSearchHistoryStoreTest {
    private val memory = MemoryPreferences()
    private val store = ResourceSearchHistoryStore(memory)

    @Test
    fun `history keeps twenty newest unique queries with repeats moved first`() {
        repeat(21) { index -> store.record("query-$index") }
        store.record("query-5")
        store.record("  query-20  ")

        val history = store.list()

        assertEquals(20, history.size)
        assertEquals("query-20", history.first())
        assertEquals("query-5", history[1])
        // 21 条只淘汰最旧的一条，query-1 仍在；再验证尾部顺序未被重排打乱。
        assertTrue("query-0" !in history)
        assertTrue("query-1" in history)
        assertEquals("query-1", history.last())
    }

    @Test
    fun `blank queries are ignored and clear removes every entry`() {
        store.record("编译原理")
        store.record("   ")

        assertEquals(listOf("编译原理"), store.list())
        store.clear()
        assertTrue(store.list().isEmpty())
        assertEquals(null, memory.getString("queries_v1"))
    }
}
