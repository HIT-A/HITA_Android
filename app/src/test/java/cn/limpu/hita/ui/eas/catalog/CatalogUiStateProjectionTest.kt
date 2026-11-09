package cn.limpu.hita.ui.eas.catalog

import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUiStateProjectionTest {

    private fun state(
        state: DataState.STATE,
        message: String? = null,
    ) = DataState<Any>(state, message)

    @Test
    fun `term success without message yields ready state`() {
        assertEquals(CatalogUiState.Ready(), CatalogUiStateProjection.fromTerms(state(DataState.STATE.SUCCESS)))
    }

    @Test
    fun `term failure keeps the backend message and falls back when blank`() {
        assertEquals(
            CatalogUiState.Error("教务返回的学期错误"),
            CatalogUiStateProjection.fromTerms(state(DataState.STATE.FETCH_FAILED, "教务返回的学期错误")),
        )
        assertEquals(
            CatalogUiState.Error("学期列表加载失败"),
            CatalogUiStateProjection.fromTerms(state(DataState.STATE.FETCH_FAILED)),
        )
    }

    @Test
    fun `term unavailable states use their own fallback`() {
        assertEquals(
            CatalogUiState.Error("学期列表暂不可用"),
            CatalogUiStateProjection.fromTerms(state(DataState.STATE.TOKEN_INVALID)),
        )
        assertEquals(
            CatalogUiState.Error("学期列表暂不可用"),
            CatalogUiStateProjection.fromTerms(state(DataState.STATE.SPECIAL)),
        )
    }

    @Test
    fun `not logged in is surfaced as needs web login`() {
        assertEquals(
            CatalogUiState.NeedsWebLogin("会话已失效"),
            CatalogUiStateProjection.fromTerms(state(DataState.STATE.NOT_LOGGED_IN, "会话已失效")),
        )
        assertEquals(
            CatalogUiState.NeedsWebLogin(null),
            CatalogUiStateProjection.fromCourses(state(DataState.STATE.NOT_LOGGED_IN), hasItems = true),
        )
    }

    @Test
    fun `nothing keeps the previous state`() {
        assertNull(CatalogUiStateProjection.fromTerms(state(DataState.STATE.NOTHING)))
        assertNull(CatalogUiStateProjection.fromCourses(state(DataState.STATE.NOTHING), hasItems = false))
    }

    @Test
    fun `empty course page reports the empty message`() {
        assertEquals(
            CatalogUiState.Ready(message = "当前筛选条件下没有课程"),
            CatalogUiStateProjection.fromCourses(state(DataState.STATE.SUCCESS), hasItems = false),
        )
    }

    @Test
    fun `non empty course page has no message`() {
        assertEquals(
            CatalogUiState.Ready(message = null),
            CatalogUiStateProjection.fromCourses(state(DataState.STATE.SUCCESS), hasItems = true),
        )
    }

    @Test
    fun `course failure with backend message stays ready with that message`() {
        val projection = CatalogUiStateProjection.fromCourses(
            state(DataState.STATE.FETCH_FAILED, "网络不可用"),
            hasItems = false,
        )
        assertEquals(CatalogUiState.Ready(message = "网络不可用"), projection)
    }

    @Test
    fun `course failure without message falls back to the loading failure text`() {
        assertEquals(
            CatalogUiState.Ready(message = "课程数据加载失败"),
            CatalogUiStateProjection.fromCourses(state(DataState.STATE.FETCH_FAILED), hasItems = false),
        )
    }

    @Test
    fun `ready states compare by value so recomposition can skip equal state`() {
        val first = CatalogUiStateProjection.fromCourses(state(DataState.STATE.SUCCESS), hasItems = true)
        val second = CatalogUiStateProjection.fromCourses(state(DataState.STATE.SUCCESS), hasItems = true)
        assertEquals(first, second)
        assertTrue(first is CatalogUiState.Ready)
    }
}
