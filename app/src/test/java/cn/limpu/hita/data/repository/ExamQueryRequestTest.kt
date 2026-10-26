package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExamQueryRequestTest {
    @Test fun loggedOutRequestsHaveNoCampusLoadingAndDoNotQuery() = runBlocking {
        EASToken.Campus.entries.forEach { campus ->
            var calls = 0
            val request = ExamQueryRequest.forToken(EASToken().apply { this.campus = campus }, term()) { _, _ ->
                calls++
                success()
            }
            assertNull(request.initialState)
            val state = request.execute()
            assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
            assertNull(state.data)
            assertNull(state.message)
            assertEquals(0, calls)
        }
    }

    @Test fun existingLoginFormsKeepOnlyWeihaiInitialLoading() = runBlocking {
        EASToken.Campus.entries.forEach { campus ->
            val tokens = listOf(
                EASToken().apply { accessToken = "synthetic-access" },
                EASToken().apply { cookies["session"] = "synthetic-cookie" },
                EASToken().apply { webCookies["SESSION"] = "synthetic-web" },
            )
            tokens.forEach { token ->
                token.campus = campus
                var calls = 0
                val request = ExamQueryRequest.forToken(token, term()) { _, _ -> calls++; success() }
                val expected = if (campus == EASToken.Campus.WEIHAI) DataState.STATE.LOADING else null
                assertEquals(expected, request.initialState?.state)
                assertNull(request.initialState?.data)
                assertEquals(DataState.STATE.SUCCESS, request.execute().state)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun delayedQueryRetainsEveryOriginalTermFieldAfterSelectionObjectChanges() = runBlocking {
        val selected = term().apply { name = "custom display"; id = "custom identity"; isCurrent = true }
        val expected = fields(selected)
        var received: TermItem? = null
        val request = ExamQueryRequest.forToken(loggedIn(), selected) { _, captured -> received = captured; success() }
        selected.yearCode = "later-year"
        selected.yearName = "later-year-name"
        selected.termCode = "later-code"
        selected.termName = "later-name"
        selected.name = "later display"
        selected.id = "later identity"
        selected.isCurrent = false

        request.execute()

        assertNotSame(selected, received)
        assertEquals(expected, fields(requireNotNull(received)))
    }

    @Test fun nullTermIsPassedThroughForCampusSpecificPolicy() = runBlocking {
        EASToken.Campus.entries.forEach { campus ->
            var calls = 0
            val request = ExamQueryRequest.forToken(loggedIn(campus), null) { _, captured ->
                assertNull(captured)
                calls++
                success()
            }
            request.execute()
            assertEquals(1, calls)
        }
    }

    @Test fun delayedRequestUsesCapturedAccountSessionAndService() = runBlocking {
        var selectedToken = loggedIn(EASToken.Campus.BENBU).apply { sessionGeneration = 11 }
        val firstToken = selectedToken
        var receivedToken: EASToken? = null
        var selectedService: suspend (EASToken, TermItem?) -> DataState<List<ExamItem>> = { token, _ ->
            receivedToken = token
            success()
        }
        val request = ExamQueryRequest.forToken(selectedToken, term(), selectedService)
        selectedToken = loggedIn(EASToken.Campus.WEIHAI).apply { sessionGeneration = 12 }
        var laterServiceCalls = 0
        val laterService: suspend (EASToken, TermItem?) -> DataState<List<ExamItem>> = { _, _ ->
            laterServiceCalls++
            success()
        }
        selectedService = laterService

        request.execute()

        assertSame(firstToken, receivedToken)
        assertEquals(11L, receivedToken?.sessionGeneration)
        assertNull(request.initialState)
        assertEquals(12L, selectedToken.sessionGeneration)
        assertSame(laterService, selectedService)
        assertEquals(0, laterServiceCalls)
    }

    @Test fun loggedOutRequestDoesNotAdoptLoginAddedWhileWaiting() = runBlocking {
        val token = EASToken()
        var calls = 0
        val request = ExamQueryRequest.forToken(token, null) { _, _ -> calls++; success() }
        token.accessToken = "later-access"
        assertEquals(DataState.STATE.NOT_LOGGED_IN, request.execute().state)
        assertEquals(0, calls)
    }

    @Test fun partialSuccessAndAuthenticationPrefixKeepOriginalResultMetadata() = runBlocking {
        val exam = ExamItem().apply { courseName = "synthetic course" }
        val partial = DataState(listOf(exam, exam)).apply {
            message = "partial warning"
            listAction = DataState.LIST_ACTION.APPEND
            fromCache = true
            stateRetried = DataState.STATE.TOKEN_INVALID
        }
        val expired = DataState(listOf(exam), DataState.STATE.NOT_LOGGED_IN).apply { message = "expired" }
        listOf(partial, expired).forEach { expected ->
            val request = ExamQueryRequest.forToken(loggedIn(), term()) { _, _ -> expected }
            assertSame(expected, request.execute())
        }
    }

    @Test fun queryCancellationPropagatesWithoutBusinessFailure() = runBlocking {
        val expected = CancellationException("controlled exam cancellation")
        val request = ExamQueryRequest.forToken(loggedIn(), term()) { _, _ -> throw expected }
        try {
            request.execute()
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test fun cancelledCallerNeverStartsQuery() = runBlocking {
        var calls = 0
        val request = ExamQueryRequest.forToken(loggedIn(), term()) { _, _ -> calls++; success() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            request.execute()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, calls)
    }

    @Test fun cancellationInsideQueryCannotReturnACompletedState() = runBlocking {
        var returned = false
        val request = ExamQueryRequest.forToken(loggedIn(), term()) { _, _ ->
            currentCoroutineContext().cancel()
            success()
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            request.execute()
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }

    private fun loggedIn(campus: EASToken.Campus = EASToken.Campus.SHENZHEN) = EASToken().apply {
        this.campus = campus
        accessToken = "synthetic-access"
    }

    private fun term() = TermItem("2026-2027", "year name", "1", "term name")
    private fun fields(term: TermItem): List<Any> = listOf(
        term.yearCode, term.yearName, term.termCode, term.termName, term.name, term.id, term.isCurrent,
    )
    private fun success(): DataState<List<ExamItem>> = DataState(emptyList())
}
