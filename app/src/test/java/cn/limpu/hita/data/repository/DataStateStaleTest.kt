package cn.limpu.hita.data.repository

import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「保留旧数据 + 标记本次刷新失败」这一状态约定。 */
class DataStateStaleTest {

    @Test
    fun freshStateIsNeitherStaleNorTimestamped() {
        val state = DataState(listOf("row"))

        assertFalse(state.refreshFailed)
        assertNull(state.cachedAtMillis)
    }

    @Test
    fun setStaleKeepsPayloadAndRecordsCacheTime() {
        val state = DataState(listOf("row"))

        state.setStale(1_700_000_000_000L, "offline")

        assertEquals(DataState.STATE.SUCCESS, state.state)
        assertEquals(listOf("row"), state.data)
        assertTrue(state.refreshFailed)
        assertEquals(1_700_000_000_000L, state.cachedAtMillis)
        assertEquals("offline", state.message)
    }

    @Test
    fun setStaleWithoutMessageKeepsExistingOne() {
        val state = DataState(listOf("row")).apply { message = "kept" }

        state.setStale(null, "  ")

        assertEquals("kept", state.message)
        assertTrue(state.refreshFailed)
        assertNull(state.cachedAtMillis)
    }

    @Test
    fun onlyStatesWithoutNewDataEndARequest() {
        val terminal = DataState.STATE.entries.filter { it.endsWithoutData() }.toSet()

        assertEquals(
            setOf(
                DataState.STATE.FETCH_FAILED,
                DataState.STATE.NOT_LOGGED_IN,
                DataState.STATE.TOKEN_INVALID,
            ),
            terminal,
        )
    }
}
