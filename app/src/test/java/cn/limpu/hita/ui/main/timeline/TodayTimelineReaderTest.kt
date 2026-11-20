package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.ResolvedDisplayScope
import cn.limpu.hita.data.repository.today.TodayReadState
import cn.limpu.hita.data.repository.today.asTodayReadState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TodayTimelineReaderTest {
    private val window = TodayDateWindow.from(Instant.parse("2026-11-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private fun query(id: String, members: List<String> = listOf(id), day: TodayDateWindow = window) =
        TodayTimelineQuery.from(ResolvedDisplayScope(DisplayScope.Group(id), id, members, emptyList()), day)

    @Test fun `query identity includes canonical members scope date and zone`() {
        assertEquals(query("g", listOf("b", "a", "a")), query("g", listOf("a", "b")))
        assertNotEquals(query("g", listOf("a")), query("g", listOf("a", "b")))
        assertNotEquals(query("g", listOf("a")), query("other", listOf("a")))
        assertNotEquals(query("g"), query("g", day = TodayDateWindow.from(Instant.ofEpochMilli(window.tomorrowStart), window.zoneId)))
        assertNotEquals(query("g"), query("g", day = TodayDateWindow.from(Instant.ofEpochMilli(window.start), ZoneId.of("UTC"))))
    }

    @Test fun `target switch cancels old read clears content and skips equivalent queries`() = runBlocking {
        withTimeout(5_000) {
            val inputs = Channel<TodayReadState<TodayTimelineQuery?>>(Channel.UNLIMITED)
            val outputs = Channel<TodayReadState<TodayTimelineSnapshot?>>(Channel.UNLIMITED)
            val oldRows = Channel<List<EventItem>>()
            val cancelled = CompletableDeferred<Unit>()
            val reads = mutableListOf<String>()
            val operation = launch {
                observeTodayTimeline(inputs.receiveAsFlow(), MutableStateFlow(0)) { key ->
                    reads += key.scopeId
                    flow {
                        emit(TodayReadState.Loading)
                        if (key.scopeId == "old") {
                            try { for (rows in oldRows) emit(TodayReadState.Ready(rows)) }
                            finally { cancelled.complete(Unit) }
                        } else emit(TodayReadState.Ready(emptyList()))
                    }
                }.collect { outputs.send(it) }
            }
            try {
                inputs.send(TodayReadState.Loading)
                assertEquals(TodayReadState.Loading, outputs.receive())
                inputs.send(TodayReadState.Ready(query("old")))
                assertEquals(TodayReadState.Loading, outputs.receive())
                oldRows.send(listOf(EventItem().apply { id = "old-event" }))
                val old = outputs.receive() as TodayReadState.Ready
                assertEquals("old", requireNotNull(old.value).query.scopeId)
                inputs.send(TodayReadState.Ready(query("old")))
                inputs.send(TodayReadState.Ready(query("new")))
                cancelled.await()
                assertEquals(TodayReadState.Loading, outputs.receive())
                val new = outputs.receive() as TodayReadState.Ready
                assertEquals("new", requireNotNull(new.value).query.scopeId)
                assertTrue(requireNotNull(new.value).events.isEmpty())
                assertEquals(listOf("old", "new"), reads)
                // No-target is an explicit successful resolution, not a failed query or loading.
                inputs.send(TodayReadState.Ready(null))
                assertEquals(TodayReadState.Ready<TodayTimelineSnapshot?>(null), outputs.receive())
            } finally { operation.cancelAndJoin(); inputs.close(); oldRows.close(); outputs.close() }
        }
    }

    @Test fun `explicit retry recollects failed query without carrying another subscription snapshot`() = runBlocking {
        withTimeout(5_000) {
            val outputs = Channel<TodayReadState<TodayTimelineSnapshot?>>(Channel.UNLIMITED)
            val retry = MutableStateFlow(0)
            var attempts = 0
            val operation = launch {
                observeTodayTimeline(MutableStateFlow(TodayReadState.Ready(query("g"))), retry) {
                    flow<List<EventItem>> {
                        if (++attempts == 1) throw IllegalStateException("synthetic read failure")
                        emit(emptyList())
                    }.asTodayReadState()
                }.collect { outputs.send(it) }
            }
            try {
                assertEquals(TodayReadState.Loading, outputs.receive())
                assertNull((outputs.receive() as TodayReadState.Failed).retained)
                retry.value++
                assertEquals(TodayReadState.Loading, outputs.receive())
                assertTrue(outputs.receive() is TodayReadState.Ready)
                assertEquals(2, attempts)
            } finally { operation.cancelAndJoin(); outputs.close() }
        }
    }

    @Test fun `course colors follow scope and date without retaining the previous subscription`() = runBlocking {
        withTimeout(5_000) {
            val queries = MutableStateFlow<TodayReadState<TodayTimelineQuery?>>(TodayReadState.Ready(query("a")))
            val first = MutableStateFlow(mapOf("course-a" to 10, "off-day-course" to 20))
            val second = MutableStateFlow(mapOf("course-b" to 30))
            val outputs = Channel<TodayReadState<TodayTimelineSnapshot?>>(Channel.UNLIMITED)
            val operation = launch {
                observeTodayTimeline(queries, MutableStateFlow(0),
                    readCourseColors = { if (it.scopeId == "a") first else second },
                    read = { flowOf(TodayReadState.Ready(emptyList())) },
                ).collect { outputs.send(it) }
            }
            suspend fun ready(): TodayTimelineSnapshot {
                while (true) {
                    val next = outputs.receive()
                    if (next is TodayReadState.Ready) return requireNotNull(next.value)
                }
            }
            try {
                assertEquals(first.value, ready().courseColors)
                queries.value = TodayReadState.Ready(query("a", day = TodayDateWindow.from(
                    Instant.ofEpochMilli(window.tomorrowStart), window.zoneId)))
                assertEquals(first.value, ready().courseColors)
                queries.value = TodayReadState.Ready(query("b"))
                val changed = ready()
                assertEquals("b", changed.query.scopeId)
                assertEquals(second.value, changed.courseColors)
                assertEquals(0, first.subscriptionCount.value)
            } finally { operation.cancelAndJoin(); outputs.close() }
        }
    }
}
