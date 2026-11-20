package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.repository.today.TodayReadState
import com.limpu.component.data.DataState
import org.junit.Assert.*
import org.junit.Test

class ExamReadStateStoreTest {
    private fun scope(id: String = "1", generation: Long = 1) = EasQueryScope.from(EASToken().apply {
        username = id; accessToken = "synthetic"; sessionGeneration = generation
    })
    private fun exam(name: String = "数学") = ExamItem().apply {
        courseName = name; examDate = "2026-11-17"; termId = "term"; examTime = "08:00-10:00"
    }
    @Test fun `unknown success empty and failure are distinct`() {
        val current = scope()
        val store = ExamReadStateStore { current }
        store.selectSession(current)
        assertTrue(store.state.value.terms.isEmpty())
        val ticket = store.begin(current, "term")
        assertEquals(TodayReadState.Loading, store.state.value.terms["term"])
        assertTrue(store.finish(ticket, DataState(emptyList())))
        val success = store.state.value.terms["term"] as TodayReadState.Ready
        assertTrue(success.value.items.isEmpty())
        val retry = store.begin(current, "term")
        store.finish(retry, DataState(DataState.STATE.FETCH_FAILED))
        assertNotNull((store.state.value.terms["term"] as TodayReadState.Failed).retained)
    }
    @Test fun `older response cannot replace newer request and cancellation restores accepted data`() {
        val current = scope(); val store = ExamReadStateStore { current }
        val old = store.begin(current, "term")
        val newer = store.begin(current, "term")
        assertFalse(store.finish(old, DataState(listOf(exam("old")))))
        assertTrue(store.finish(newer, DataState(listOf(exam("new"))), "target"))
        val result = store.state.value
        val cancelled = store.begin(current, "term")
        store.cancel(cancelled)
        assertEquals(result, store.state.value)
        assertFalse(store.finish(old, DataState(emptyList())))
    }
    @Test fun `account and session changes clear results and reject late responses`() {
        var current = scope()
        val store = ExamReadStateStore { current }
        val old = store.begin(current, "term")
        current = scope("2")
        store.selectSession(current)
        assertTrue(store.state.value.terms.isEmpty())
        assertFalse(store.finish(old, DataState(listOf(exam()))))
        val second = store.begin(current, "term")
        current = scope("2", 2)
        assertFalse(store.finish(second, DataState(emptyList())))
        assertTrue(store.state.value.terms.isEmpty())
        store.selectSession(scope("1"))
        assertEquals(current.ownerKey, store.state.value.ownerKey)
    }
    @Test fun `stored records are immutable copies bounded and memo free`() {
        val current = scope(); val store = ExamReadStateStore { current }
        val row = exam()
        val memo = exam("personal").apply { memoId = "memo" }
        store.finish(store.begin(current, "term"), DataState(listOf(row, memo)))
        row.courseName = "changed after publish"
        val result = (store.state.value.terms["term"] as TodayReadState.Ready).value
        assertEquals("数学", result.items.single().courseName)
        for (index in 0..ExamReadStateStore.MAX_TERMS) {
            store.finish(store.begin(current, "t$index"), DataState(emptyList()))
        }
        assertEquals(ExamReadStateStore.MAX_TERMS, store.state.value.terms.size)
        val large = List(ExamReadStateStore.MAX_ITEMS + 1) { exam("x".repeat(ExamReadStateStore.MAX_FIELD + 1)) }
        store.finish(store.begin(current, "large"), DataState(large))
        val bounded = (store.state.value.terms["large"] as TodayReadState.Ready).value
        assertTrue(bounded.truncated)
        assertEquals(ExamReadStateStore.MAX_ITEMS, bounded.items.size)
        assertEquals(ExamReadStateStore.MAX_FIELD, bounded.items.first().courseName!!.length)
    }
    @Test fun `cancelling an unaccepted first query returns to unknown`() {
        val current = scope(); val store = ExamReadStateStore { current }
        val ticket = store.begin(current, null)
        store.cancel(ticket)
        assertTrue(store.state.value.terms.isEmpty())
        assertNull(store.begin(current.copy(loggedIn = false), "term"))
    }
    @Test fun `cancelled and already completed tickets reject late publications`() {
        val current = scope(); val store = ExamReadStateStore { current }
        val cancelled = store.begin(current, "term")
        store.cancel(cancelled)
        assertFalse(store.finish(cancelled, DataState(listOf(exam()))))
        val completed = store.begin(current, "term")
        assertTrue(store.finish(completed, DataState(emptyList())))
        assertFalse(store.finish(completed, DataState(listOf(exam()))))
    }
    @Test fun `overlapping retries retain the last accepted snapshot after a newer failure`() {
        val current = scope(); val store = ExamReadStateStore { current }
        store.finish(store.begin(current, "term"), DataState(listOf(exam())))
        val older = store.begin(current, "term")
        val newer = store.begin(current, "term")
        store.finish(newer, DataState(DataState.STATE.FETCH_FAILED))
        assertEquals("数学", (store.state.value.terms["term"] as TodayReadState.Failed).retained?.items?.single()?.courseName)
        store.cancel(older)
        assertTrue(store.state.value.terms["term"] is TodayReadState.Failed)
    }

    @Test fun `successful source warnings remain partial coverage`() {
        val current = scope(); val store = ExamReadStateStore { current }
        store.finish(store.begin(current, "term"), DataState(listOf(exam())).apply { message = "one exam category failed" })
        assertTrue((store.state.value.terms["term"] as TodayReadState.Ready).value.partial)
    }

}
