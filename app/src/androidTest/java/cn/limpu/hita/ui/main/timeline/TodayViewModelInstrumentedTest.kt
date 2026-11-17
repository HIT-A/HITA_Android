package cn.limpu.hita.ui.main.timeline

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.ResolvedDisplayScope
import cn.limpu.hita.data.repository.today.TodayReadState
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayViewModelInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-11-17T04:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun scope(id: String, ids: List<String> = listOf(id)) = ResolvedDisplayScope(DisplayScope.Single(id), id, ids, emptyList())
    private fun event(id: String) = EventItem().apply {
        this.id = id; name = id; from = Timestamp(now + 1000); to = Timestamp(now + 61_000)
    }
    private class Sources : TodayReadSources {
        override val scopes = MutableStateFlow<TodayReadState<ResolvedDisplayScope?>>(TodayReadState.Loading)
        override val session = MutableStateFlow(TodaySession())
        val records = MutableStateFlow<TodayReadState<List<EventItem>>>(TodayReadState.Loading)
        val queries = mutableListOf<TodayTimelineQuery>()
        var loads = 0
        var isVisible = false
        override fun currentSession() = session.value
        override fun currentScope() = (scopes.value as? TodayReadState.Ready)?.value
        override fun read(query: TodayTimelineQuery): Flow<TodayReadState<List<EventItem>>> {
            queries += query
            return records
        }
        override suspend fun load(source: TodayRetrySource, retry: Boolean) { loads++ }
        override fun visible(visible: Boolean) { isVisible = visible }
        override suspend fun findEvent(query: TodayTimelineQuery, id: String) =
            (records.value as? TodayReadState.Ready)?.value?.firstOrNull { it.id == id }
    }
    private fun harness(test: (Sources, FragmentTimelineViewModel, AtomicReference<TodayUiState>) -> Unit) {
        val source = Sources()
        val store = ViewModelStore()
        lateinit var model: FragmentTimelineViewModel
        lateinit var job: Job
        val state = AtomicReference<TodayUiState>()
        instrumentation.runOnMainSync {
            model = FragmentTimelineViewModel(source, Clock.fixed(Instant.ofEpochMilli(now), zone))
            store.put("today", model)
            model.onVisible(now, zone)
            job = CoroutineScope(Dispatchers.Main.immediate).launch { model.uiState.collect { state.set(it) } }
        }
        try { test(source, model, state) }
        finally { instrumentation.runOnMainSync { model.onHidden(); job.cancel(); store.clear() } }
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(test: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) { if (test()) return; Thread.sleep(20) }
        assertTrue("Timed out waiting for state", test())
    }
    @Test fun loadingNoTargetAndSuccessfulEmptyAreDistinct() = harness { source, _, state ->
        await { state.get()?.status == TodayPageStatus.LOADING }
        main { source.scopes.value = TodayReadState.Ready(null) }
        await { state.get()?.status == TodayPageStatus.NO_TARGET }
        main { source.scopes.value = TodayReadState.Ready(scope("a")); source.records.value = TodayReadState.Ready(emptyList()) }
        await { state.get()?.status == TodayPageStatus.READY }
        assertNotNull(state.get().agenda); assertTrue(state.get().agenda!!.allToday.isEmpty())
    }
    @Test fun retainedFailureAndRetryDoNotBecomeEmptySuccess() = harness { source, model, state ->
        val item = event("a")
        main { source.scopes.value = TodayReadState.Ready(scope("a")); source.records.value = TodayReadState.Ready(listOf(item)) }
        await { state.get()?.agenda?.allToday?.size == 1 }
        main { source.records.value = TodayReadState.Failed(listOf(item), IllegalStateException("fixture")) }
        await { state.get()?.status == TodayPageStatus.FAILED }
        assertEquals("a", state.get().agenda!!.allToday.single().id)
        val previous = source.queries.size
        main { model.retry(TodayRetrySource.AGENDA) }
        await { source.queries.size > previous }
    }
    @Test fun minuteTicksDoNotRequeryOrRefreshWidgetsAndMembershipInvalidatesActions() = harness { source, model, state ->
        main { source.scopes.value = TodayReadState.Ready(scope("a")); source.records.value = TodayReadState.Ready(listOf(event("a"))) }
        await { state.get()?.status == TodayPageStatus.READY }
        val initial = state.get()
        main { assertEquals(1000L, model.nextWakeDelay(initial)) }
        val count = source.queries.size
        main { assertTrue(model.consumeWidgetChange(initial)); model.onTimeChanged(now + 60_000, zone) }
        await { state.get()?.now == now + 60_000 }
        assertEquals(count, source.queries.size)
        main { assertFalse(model.consumeWidgetChange(state.get())); assertTrue(model.accepts(initial))
            source.scopes.value = TodayReadState.Ready(scope("a", listOf("a", "b")))
            assertFalse(model.accepts(initial)) }
        await { source.queries.size > count }
        val beforeSessionChange = state.get()
        main { source.session.value = TodaySession("other", 2); assertFalse(model.accepts(beforeSessionChange)) }
    }
    @Test fun midnightAndTimeZoneChangeReplaceTheQueryWindow() = harness { source, model, state ->
        main { source.scopes.value = TodayReadState.Ready(scope("a")); source.records.value = TodayReadState.Ready(emptyList()) }
        await { state.get()?.status == TodayPageStatus.READY }
        val old = state.get()
        main { model.onTimeChanged(old.window.tomorrowStart, zone) }
        await { state.get()?.window?.date == old.window.date.plusDays(1) }
        main { assertFalse(model.accepts(old)); model.onTimeChanged(now, ZoneId.of("America/New_York")) }
        await { state.get()?.window?.zoneId == ZoneId.of("America/New_York") }
        await { source.queries.map { it.window }.distinct().size >= 3 }
    }
    @Test fun hiddenPageDoesNotStartExplicitSummaryRetries() = harness { source, model, _ ->
        main { model.onHidden() }
        Thread.sleep(100)
        val previous = source.loads
        main { model.retry(TodayRetrySource.HOMEWORK); model.retry(TodayRetrySource.GRADER) }
        Thread.sleep(100)
        assertEquals(previous, source.loads); assertFalse(source.isVisible)
    }
}
