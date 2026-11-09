package cn.limpu.hita.ui.eas.catalog

/**
 * 深圳课程浏览页的可见状态。
 *
 * 从 Activity 内提升为顶层类型，使其可被纯函数推导并被单测覆盖。
 * 语义与原 `bindLiveData()` 的分支保持等价：
 * - 学期列表与课程列表共用同一个状态机；
 * - `NOTHING` 表示"不要动"，保留上一次状态；
 * - 其余分支统一落到 Ready(message) 或 Error。
 */
internal sealed interface CatalogUiState {
    data object Loading : CatalogUiState
    data class NeedsWebLogin(val message: String?) : CatalogUiState
    data class Error(val message: String) : CatalogUiState
    data class Ready(
        val refreshing: Boolean = false,
        val message: String? = null
    ) : CatalogUiState
}

/**
 * 把 DataState 的三元信息（state/data/message）折叠成页面状态。
 *
 * @param isTermSource 学期列表与课程列表在失败/异常时的兜底文案不同，用语义参数区分，
 *                     避免调用方各自复制一份 when 分支。
 * @param keepOnNothing 为 true 时 NOTHING 返回 null（调用方保持现状）。
 */
internal object CatalogUiStateProjection {

    fun fromTerms(state: com.limpu.component.data.DataState<*>): CatalogUiState? =
        when (state.state) {
            com.limpu.component.data.DataState.STATE.SUCCESS -> CatalogUiState.Ready()
            com.limpu.component.data.DataState.STATE.NOT_LOGGED_IN -> CatalogUiState.NeedsWebLogin(state.message)
            com.limpu.component.data.DataState.STATE.FETCH_FAILED ->
                CatalogUiState.Error(state.message ?: "学期列表加载失败")
            com.limpu.component.data.DataState.STATE.NOTHING -> null
            else -> CatalogUiState.Error(state.message ?: "学期列表暂不可用")
        }

    fun fromCourses(
        state: com.limpu.component.data.DataState<*>,
        hasItems: Boolean,
    ): CatalogUiState? =
        project(
            state = state.state,
            message = state.message,
            emptyMessage = if (hasItems) null else "当前筛选条件下没有课程",
            failureFallback = "课程数据加载失败",
            unavailableFallback = "课程数据暂不可用",
            refreshing = false,
        )

    private fun project(
        state: com.limpu.component.data.DataState.STATE,
        message: String?,
        emptyMessage: String?,
        failureFallback: String,
        unavailableFallback: String,
        refreshing: Boolean,
    ): CatalogUiState? = when (state) {
        com.limpu.component.data.DataState.STATE.SUCCESS ->
            CatalogUiState.Ready(message = emptyMessage, refreshing = refreshing)
        com.limpu.component.data.DataState.STATE.NOT_LOGGED_IN ->
            CatalogUiState.NeedsWebLogin(message)
        com.limpu.component.data.DataState.STATE.FETCH_FAILED ->
            CatalogUiState.Ready(message = message ?: failureFallback)
        com.limpu.component.data.DataState.STATE.NOTHING -> null
        else -> CatalogUiState.Ready(message = message ?: unavailableFallback)
    }
}
