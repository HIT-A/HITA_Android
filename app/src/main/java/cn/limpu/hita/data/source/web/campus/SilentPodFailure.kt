package cn.limpu.hita.data.source.web.campus

/**
 * 静音仓请求的失败类型。
 *
 * 页面必须能区分「没登录」和「网络坏了」：前者要引导去登录，后者要给出可读原因，
 * 所以异常只携带类型与细节，文案由 UI 层从 `strings.xml` 取。
 */
internal enum class SilentPodFailureKind {
    /** 会话缺失或已被服务端拒绝（401/403/登录跳转）。 */
    AUTHENTICATION,

    /** aTrust 网关 502 / 应用服务器连不上。 */
    GATEWAY,

    /** 请求超时。 */
    TIMEOUT,

    /** 其它网络层失败（DNS、连接中断等）。 */
    NETWORK,

    /** 业务错误信封（`errorCode` + `errorMsg`）。 */
    API,

    /** 响应不是合法 JSON 或缺少必要字段。 */
    PARSE,
}

internal class SilentPodException(
    val kind: SilentPodFailureKind,
    val code: String? = null,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: kind.name, cause)
