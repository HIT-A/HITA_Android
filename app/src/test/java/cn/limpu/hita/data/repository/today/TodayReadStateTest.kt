package cn.limpu.hita.data.repository.today

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TodayReadStateTest {
    @Test fun `cold failure is not empty success and recollection retries`() = runBlocking {
        val failure = IllegalStateException("local read failed")
        var attempts = 0
        val source = flow<List<String>> {
            if (++attempts == 1) throw failure
            emit(emptyList())
        }.asTodayReadState()
        val first = source.toList()
        assertEquals(TodayReadState.Loading, first.first())
        val failed = first.last() as TodayReadState.Failed
        assertNull(failed.retained)
        assertSame(failure, failed.failure)
        assertEquals(listOf(TodayReadState.Loading, TodayReadState.Ready(emptyList<String>())), source.toList())
    }

    @Test fun `failure retains this subscription snapshot only`() = runBlocking {
        val failure = IllegalStateException("read failed after initial success")
        val states = flow { emit(listOf("known")); throw failure }.asTodayReadState().toList()
        assertEquals(listOf("known"), (states.last() as TodayReadState.Failed).retained)
        assertEquals(3, states.size)
    }

    @Test fun `cancellation propagates without publishing failed state`() = runBlocking {
        val cancellation = CancellationException("query switched")
        val states = mutableListOf<TodayReadState<String>>()
        try {
            flow<String> { throw cancellation }.asTodayReadState().toList(states)
            fail("cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(listOf(TodayReadState.Loading), states)
    }
}
