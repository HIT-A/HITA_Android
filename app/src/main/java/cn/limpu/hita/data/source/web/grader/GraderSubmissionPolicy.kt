package cn.limpu.hita.data.source.web.grader

import java.net.URI

/**
 * 提交作业的前置规则：文件本地预检，以及上传目标地址的可信判定。
 *
 * 预检参照 Grader 前端（`_assignmentId_` chunk）的顺序：先比大小上限，再比扩展名；
 * 两者都用作业自身下发的字段，而不是写死常量。
 */
internal object GraderSubmissionPolicy {

    enum class Violation {
        /** 服务端已不允许提交（过期关闭或未发布）。 */
        CLOSED,

        /** 超过作业的大小上限。 */
        TOO_LARGE,

        /** 扩展名不在作业允许列表内。 */
        EXTENSION,
    }

    /** 文件扩展名：小写、不含点；没有扩展名返回 null。 */
    fun extensionOf(fileName: String): String? =
        fileName.substringAfterLast('.', "").trim().lowercase().takeIf { it.isNotEmpty() }

    /**
     * 返回第一个不满足的条件，全部通过返回 null。
     *
     * 扩展名列表为空表示作业未限制；此时不拦，交由服务端判定。
     */
    fun violation(detail: GraderAssignmentDetail, fileName: String, sizeBytes: Long): Violation? = when {
        detail.submitAllowed == false -> Violation.CLOSED
        detail.maxFileSize != null && sizeBytes > detail.maxFileSize -> Violation.TOO_LARGE
        detail.allowedExtensions.isNotEmpty() && extensionOf(fileName) !in detail.allowedExtensions ->
            Violation.EXTENSION
        else -> null
    }

    /**
     * 把服务端给的上传地址补全成绝对地址。
     *
     * Grader 前端用 axios，相对地址会按页面 origin 解析；原生请求没有这一层，
     * 必须先按入口补齐，否则会被同源校验直接拒掉。
     */
    fun resolve(entry: GraderSessionPolicy.Entry, raw: String): String? = runCatching {
        val uri = URI(raw)
        val absolute = if (uri.isAbsolute) uri else URI(entry.origin).resolve(uri)
        absolute.toString()
    }.getOrNull()

    /** 上传方案：要访问的地址，以及是否附带入口会话凭据。 */
    internal sealed interface UploadPlan {
        data class Send(val url: String, val authenticated: Boolean) : UploadPlan
    }

    /**
     * 解析服务端签发的上传目标。
     *
     * 上传地址是预签名的（凭据在表单字段里），与会话入口无关：不管会话是从校园网直连
     * 建立还是经网关建立，文件都应直接送到签发的地址、且**不带**会话 cookie。
     *
     * 只有目标恰好就是入口自身时，才按普通请求带凭据发送。
     *
     * 校外（蜂窝）时该地址是内网主机（`s3.cs-lab.top` → `10.249.12.224`），直连会失败，
     * 上层据此提示用户改用校园网或 aTrust 客户端。
     */
    fun plan(entry: GraderSessionPolicy.Entry, raw: String): UploadPlan? {
        val resolved = resolve(entry, raw) ?: return null
        val uri = runCatching { URI(resolved) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (uri.rawFragment != null) return null
        val host = uri.host?.lowercase() ?: return null
        return UploadPlan.Send(resolved, authenticated = host == entry.host)
    }

    /**
     * 上传目标是否属于当前入口。
     *
     * 上传请求会带上会话 cookie 与 CSRF 头，因此目标必须是同一个 https 源：host 精确匹配、
     * 端口只接受 443 或缺省、禁止 userinfo 与 fragment。服务端若返回别处地址，一律拒绝。
     */
    fun isSameEntry(entry: GraderSessionPolicy.Entry, url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return false
        if (!uri.host.orEmpty().equals(entry.host, ignoreCase = true)) return false
        if (uri.port != -1 && uri.port != 443) return false
        return uri.rawFragment == null
    }
}
