package com.limpu.component.data

import org.junit.Assert.*
import org.junit.Test

class ResultContractTest {
    @Test
    fun successfulPayloadSurvivesMappingAndUiConversion() {
        val payload = mutableListOf("cached", "remote")
        val result = Result.success(payload)
        var callbacks = 0
        assertSame(result, result.onSuccess { assertSame(payload, it); callbacks++ })
        assertSame(result, result.onError { fail("success must not call onError") })
        assertEquals(1, callbacks)
        assertSame(payload, result.getOrNull())
        assertSame(payload, result.toDataState().data)
        assertEquals(DataState.STATE.SUCCESS, result.toDataState().state)
        assertEquals(2, result.map { it.size }.getOrNull())
    }

    @Test
    fun errorsKeepIdentityAndDoNotInvokeSuccessTransforms() {
        val cause = IllegalStateException("offline")
        val result = Result.Error(ErrorCode.NETWORK_ERROR, "retry later", cause)
        assertSame(result, result.map<String> { fail("error must not transform"); "" })
        assertSame(result, result.onSuccess { fail("error must not succeed") })
        var errors = 0
        assertSame(result, result.onError { assertSame(cause, it.throwable); errors++ })
        assertEquals(1, errors)
        assertNull(result.getOrNull())
        assertEquals("fallback", result.getOrDefault("fallback"))
        assertEquals("retry later", result.toDataState().message)
    }

    @Test
    fun errorGroupsRetainAuthenticationAndMissingDataStates() {
        val exceptionalStates = mapOf(
            ErrorCode.NOT_LOGGED_IN to DataState.STATE.NOT_LOGGED_IN,
            ErrorCode.TOKEN_INVALID to DataState.STATE.TOKEN_INVALID,
            ErrorCode.NOT_FOUND to DataState.STATE.NOT_EXIST,
            ErrorCode.EMPTY_DATA to DataState.STATE.NOT_EXIST
        )
        for (code in ErrorCode.entries) {
            val state = Result.error(code).toDataState()
            assertEquals(code.name, exceptionalStates[code] ?: DataState.STATE.FETCH_FAILED, state.state)
            assertEquals(code.defaultMessage, state.message)
            assertEquals("", Result.error(code, "").toDataState().message)
        }
    }

    @Test
    fun loadingAndNullSuccessKeepLegacyUiStates() {
        assertSame(Result.Loading, Result.loading().map { fail("loading must not transform") })
        assertEquals(DataState.STATE.LOADING, Result.loading().toDataState().state)
        val nullSuccess = Result.success<String?>(null)
        assertTrue(nullSuccess.isSuccess)
        assertEquals(DataState.STATE.FETCH_FAILED, nullSuccess.toDataState().state)
        assertEquals("default", nullSuccess.getOrDefault("default"))
    }

    @Test
    fun safeCallPreservesFailureCodeMessageAndThrowable() {
        val cause = IllegalArgumentException("bad input")
        val result = Result.safeCall<String>(ErrorCode.PARAM_ERROR) { throw cause }
        val error = requireNotNull(result.errorOrNull())
        assertEquals(ErrorCode.PARAM_ERROR, error.code)
        assertEquals("bad input", error.message)
        assertSame(cause, error.throwable)
        assertEquals("ok", Result.safeCall { "ok" }.getOrNull())
    }

    @Test
    fun cachedListCanCarryRetryFailureWithoutDiscardingData() {
        val payload = listOf("one", "two")
        val state = DataState(payload)
        assertSame(state, state.setFromCache(true).setListAction(DataState.LIST_ACTION.APPEND)
            .setRetryState(DataState.STATE.FETCH_FAILED))
        assertSame(payload, state.data)
        assertEquals(DataState.STATE.SUCCESS, state.state)
        assertTrue(state.fromCache)
        assertEquals(DataState.LIST_ACTION.APPEND, state.listAction)
        assertEquals(DataState.STATE.FETCH_FAILED, state.stateRetried)
        var successes = 0
        state.onSuccess { assertSame(payload, it); successes++ }.onError { _, _ -> fail() }
        assertEquals(1, successes)
    }

    @Test
    fun uiErrorCallbackKeepsItsLegacyNonSuccessMeaning() {
        for (status in DataState.STATE.entries.filter { it != DataState.STATE.SUCCESS }) {
            val state = DataState<String>(status, "detail")
            var errors = 0
            var failures = 0
            assertSame(state, state.onError { actual, message ->
                assertEquals(status, actual)
                assertEquals("detail", message)
                errors++
            }.onSuccess { fail() }.onFetchFailed { failures++ })
            assertEquals(1, errors)
            assertEquals(if (status == DataState.STATE.FETCH_FAILED) 1 else 0, failures)
        }
    }
}
