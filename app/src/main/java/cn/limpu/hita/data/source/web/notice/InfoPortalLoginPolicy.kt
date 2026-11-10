package cn.limpu.hita.data.source.web.notice

import cn.limpu.hita.data.model.eas.EASToken
import java.net.URI

/**
 * 深圳校园通知门户（`http://info.hitsz.edu.cn/`）的登录规则。
 *
 * 事实依据（2026-11-10 实测）：
 * - `http://info.hitsz.edu.cn/` 与任意 `list.jsp` 未登录时都 302 到
 *   `/system/resource/code/auth/caslogin.jsp`，再 302 到
 *   `https://ids.hit.edu.cn/authserver/login?service=http%3A%2F%2Finfo.hitsz.edu.cn%2F...`。
 * - 该 CAS 与本部教务登录是**同一套**表单（`username` / `password` / `lt` /
 *   `execution` / `rememberMe`），所以门户登录可直接复用本部的已保存凭据，
 *   也应当复用同一套「自动勾选 N 天免登录 + 自动填充 + 自动提交」。
 * - https 由另一个前置应答（`VAppServer/6.0.0`），登录回跳的 service 是 http；
 *   cookie 因此挂在 http 域上，用 https 取 cookie 会取不到。
 */
internal object InfoPortalLoginPolicy {

    /** 门户自身的 host；cookie 与成功判定都只看它。 */
    const val PORTAL_HOST = "info.hitsz.edu.cn"

    /**
     * 按实际 CAS 页面归属解析凭据校区。
     *
     * 门户的 `service` 固定回跳 http 门户，绝大多数情况落到本部 `ids.hit.edu.cn`；
     * 深圳校区经 aTrust 放行后则落到 `ids-hit-edu-cn-s.hitsz.edu.cn`。凭据与
     * origin 白名单都必须按页面真实归属选，否则深圳用户永远匹配不上白名单，
     * 静默重登会立刻失败。
     */
    fun credentialCampusFor(url: String): EASToken.Campus {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().lowercase()
        return when {
            host == "ids-hit-edu-cn-s.hitsz.edu.cn" -> EASToken.Campus.SHENZHEN
            host == "webvpn.hitwh.edu.cn" -> EASToken.Campus.WEIHAI
            else -> EASToken.Campus.BENBU
        }
    }

    /** 门户首页与列表页：只有这类页面才值得尝试提取通知。 */
    fun isPortalPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.host.orEmpty().equals(PORTAL_HOST, ignoreCase = true)) return false
        if (uri.port != -1) return false
        val scheme = uri.scheme.orEmpty().lowercase()
        return scheme == "http" || scheme == "https"
    }

    /**
     * 门户的通知列表页。登录成功后会回跳到 `caslogin.jsp`，再由门户重定向到
     * 最初请求的 `list.jsp`，因此判定必须包含 `list.jsp` 而非只看首页。
     */
    fun isNoticeListPage(url: String): Boolean {
        if (!isPortalPage(url)) return false
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        return path.contains("list.jsp")
    }

    /**
     * 需要用户/脚本继续处理的登录中转页：CAS 表单、门户的 caslogin 中转。
     * 到达这里说明**还没登录成功**。
     */
    fun isLoginTransitPage(url: String): Boolean {
        if (CampusNoticeParser.isCasLoginUrl(url)) return true
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        return path.contains("caslogin")
    }

    /**
     * 多因子认证页。真实账号（非可信浏览器）在第一轮账号密码之后必然落到这里，
     * 需要短信/APP 验证码，脚本无法自动完成，必须交回用户或立即结束静默流程。
     */
    fun isMultifactorPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        if (path.contains("/authserver/reauthentication")) return true
        if (path.contains("reauthcheck")) return true
        return uri.rawQuery.orEmpty().contains("isMultifactor=true", ignoreCase = true)
    }

    /** 统一身份认证表单页：这类页面才允许自动填充账号密码并自动提交。 */
    fun isCasLoginForm(url: String): Boolean {
        if (!CampusNoticeParser.isCasLoginUrl(url)) return false
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        // 只认表单本体；`/authserver/reAuthCheck/` 是多因子，必须交回用户。
        return path.trimEnd('/').endsWith("/authserver/login")
    }

    /**
     * 门户会话是否已建立。
     *
     * 门户放行后自己的 `JSESSIONID` 才是业务会话；`iPlanetDirectoryPro` 是部分
     * 部署下的身份票据，历史上也用它判断过，保留以兼容旧会话。
     * 只看 `CASTGC` 不算登录：它属于 `ids.hit.edu.cn`，单独存在不代表门户已放行。
     */
    fun hasPortalSession(cookies: Map<String, String>): Boolean {
        val sessionId = cookies.entries.firstOrNull {
            it.key.equals("JSESSIONID", ignoreCase = true)
        }?.value.orEmpty()
        if (sessionId.isNotBlank()) return true
        val directoryPro = cookies.entries.firstOrNull {
            it.key.equals("iPlanetDirectoryPro", ignoreCase = true)
        }?.value.orEmpty()
        return directoryPro.isNotBlank()
    }

    /** 登录成功后回写的持久身份票据：决定下次能否免密重登。 */
    fun hasPersistentCasTicket(cookies: Map<String, String>): Boolean {
        val ticket = cookies.entries.firstOrNull {
            it.key.equals("CASTGC", ignoreCase = true)
        }?.value.orEmpty()
        return ticket.isNotBlank()
    }

    /** 通知列表页且会话有效，才算这次登录真的可用了。 */
    fun isLoginComplete(url: String, cookies: Map<String, String>): Boolean =
        isNoticeListPage(url) && hasPortalSession(cookies)
}
