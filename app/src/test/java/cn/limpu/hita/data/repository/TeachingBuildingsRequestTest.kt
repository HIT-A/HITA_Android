package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TeachingBuildingsRequestTest {
    @Test fun loggedOutRequestDoesNotQueryAnyCampus() = runBlocking {
        EASToken.Campus.entries.forEach { campus ->
            var queryCalls = 0
            val request = TeachingBuildingsRequest.forToken(EASToken().apply { this.campus = campus }) {
                queryCalls++
                success()
            }
            assertNull(request.initialState)
            val result = request.execute()
            assertEquals(DataState.STATE.NOT_LOGGED_IN, result.state)
            assertNull(result.data)
            assertEquals(0, queryCalls)
        }
    }

    @Test fun initialStateRetainsCampusDifferencesForEveryExistingLoginForm() = runBlocking {
        EASToken.Campus.entries.forEach { campus ->
            val tokens = listOf(
                EASToken().apply { accessToken = "synthetic-access-token" },
                EASToken().apply { cookies["session"] = "synthetic-cookie" },
                EASToken().apply { webCookies["SESSION"] = "synthetic-web-cookie" },
            )
            tokens.forEach { token ->
                token.campus = campus
                var called = false
                val request = TeachingBuildingsRequest.forToken(token) { called = true; success() }
                val expected = if (campus == EASToken.Campus.SHENZHEN) null else DataState.STATE.NOTHING
                assertEquals(expected, request.initialState?.state)
                assertEquals(DataState.STATE.SUCCESS, request.execute().state)
                assertTrue(called)
            }
        }
    }

    @Test fun delayedExecutionUsesCapturedTokenAndSessionAfterAccountSelectionChanges() = runBlocking {
        var selectedToken = loggedIn(EASToken.Campus.BENBU, generation = 41)
        val originalToken = selectedToken
        var receivedToken: EASToken? = null
        val request = TeachingBuildingsRequest.forToken(selectedToken) { captured ->
            receivedToken = captured
            success()
        }
        selectedToken = loggedIn(EASToken.Campus.SHENZHEN, generation = 42)
        assertNull(receivedToken)

        request.execute()

        val actualToken = requireNotNull(receivedToken)
        assertSame(originalToken, actualToken)
        assertEquals(41L, actualToken.sessionGeneration)
        assertEquals(EASToken.Campus.BENBU, actualToken.campus)
        assertEquals(DataState.STATE.NOTHING, request.initialState?.state)
        assertEquals(42L, selectedToken.sessionGeneration)
    }

    @Test fun loggedOutRequestDoesNotAdoptLoginAddedAfterItsCreation() = runBlocking {
        val token = EASToken()
        var queryCalls = 0
        val request = TeachingBuildingsRequest.forToken(token) { queryCalls++; success() }
        token.accessToken = "later-synthetic-login"
        token.sessionGeneration = 5

        assertEquals(DataState.STATE.NOT_LOGGED_IN, request.execute().state)
        assertNull(request.initialState)
        assertEquals(0, queryCalls)
    }

    @Test fun terminalResultPreservesListOrderMessagesAndMetadata() = runBlocking {
        val duplicate = BuildingItem().apply { id = "same"; name = "同名楼" }
        val success = DataState(listOf(duplicate, duplicate)).apply {
            message = "source details"
            fromCache = true
            stateRetried = DataState.STATE.TOKEN_INVALID
            listAction = DataState.LIST_ACTION.APPEND
        }
        val failure = DataState<List<BuildingItem>>(DataState.STATE.FETCH_FAILED, "original failure")
        listOf(success, failure).forEach { expected ->
            val request = TeachingBuildingsRequest.forToken(loggedIn()) { expected }
            assertSame(expected, request.execute())
        }
    }

    @Test fun queryCancellationIsPropagatedWithoutConvertingToFailure() = runBlocking {
        val cancellation = CancellationException("controlled cancellation")
        val request = TeachingBuildingsRequest.forToken(loggedIn()) { throw cancellation }
        try {
            request.execute()
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun cancelledCallerDoesNotStartQuery() = runBlocking {
        var queryCalls = 0
        val request = TeachingBuildingsRequest.forToken(loggedIn()) { queryCalls++; success() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            request.execute()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, queryCalls)
    }

    private fun loggedIn(
        campus: EASToken.Campus = EASToken.Campus.SHENZHEN,
        generation: Long = 1,
    ) = EASToken().apply {
        this.campus = campus
        accessToken = "synthetic-access-token"
        sessionGeneration = generation
    }

    private fun success(): DataState<List<BuildingItem>> = DataState(emptyList())
}
