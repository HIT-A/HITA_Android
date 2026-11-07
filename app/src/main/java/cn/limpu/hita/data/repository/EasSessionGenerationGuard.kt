package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken

internal object EasSessionGenerationGuard {
    fun acceptsServiceRefresh(
        refreshEnabled: Boolean,
        tokenGeneration: Long,
        currentGeneration: Long,
        storedSessionLoggedIn: Boolean
    ): Boolean = refreshEnabled &&
        tokenGeneration == currentGeneration &&
        storedSessionLoggedIn

    fun resolveCredentialScopeGeneration(
        storedToken: EASToken,
        incomingToken: EASToken,
        currentGeneration: Long,
        nextGeneration: () -> Long
    ): Long {
        if (currentGeneration > 0L && representsSameAccountSession(storedToken, incomingToken)) {
            return currentGeneration
        }
        return nextGeneration().also { generated ->
            require(generated > 0L && generated != currentGeneration) {
                "Credential scope generation must be a new positive opaque value"
            }
        }
    }

    /**
     * 两个已登录 token 都能确定账号且账号不同。此时不能继承旧会话的 cookie。
     * 任一侧还没有账号标识时返回 false，刷新中的同一会话仍可补齐空字段。
     */
    fun blocksStoredSessionInheritance(stored: EASToken, incoming: EASToken): Boolean {
        if (!stored.isLogin() || stored.campus != incoming.campus ||
            stored.campus == EASToken.Campus.WEIHAI && stored.stutype != incoming.stutype) return true
        val storedAccount = stored.accountIdentity()
        val incomingAccount = incoming.accountIdentity()
        return storedAccount != null &&
            incomingAccount != null &&
            storedAccount != incomingAccount
    }

    private fun representsSameAccountSession(stored: EASToken, incoming: EASToken): Boolean {
        if (!stored.isLogin() || !incoming.isLogin() || stored.campus != incoming.campus ||
            stored.campus == EASToken.Campus.WEIHAI && stored.stutype != incoming.stutype) return false

        val storedAccount = stored.accountIdentity()
        val incomingAccount = incoming.accountIdentity()
        if (storedAccount != null && incomingAccount != null && storedAccount != incomingAccount) {
            return false
        }

        val storedWebSession = stored.webSessionIdentity()
        val incomingWebSession = incoming.webSessionIdentity()
        return storedWebSession == null || incomingWebSession == null || storedWebSession == incomingWebSession
    }

    private fun EASToken.accountIdentity(): String? =
        username?.takeIf(String::isNotBlank)
            ?: stuId?.takeIf(String::isNotBlank)
            ?: id?.takeIf(String::isNotBlank)

    private fun EASToken.webSessionIdentity(): String? =
        webCookies["JSESSIONID"]?.takeIf(String::isNotBlank)
            ?: webCookies["SESSION"]?.takeIf(String::isNotBlank)
}
