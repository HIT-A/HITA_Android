package cn.limpu.hita.data.repository

import com.limpu.component.data.DataState

/**
 * 本次请求已经结束、而且没有新的可用数据。
 *
 * 页面据此决定是换成错误页（没有旧数据可看）还是保留上次成功的结果并提示刷新失败。
 */
internal fun DataState.STATE.endsWithoutData(): Boolean = when (this) {
    DataState.STATE.FETCH_FAILED,
    DataState.STATE.NOT_LOGGED_IN,
    DataState.STATE.TOKEN_INVALID,
    -> true
    else -> false
}
