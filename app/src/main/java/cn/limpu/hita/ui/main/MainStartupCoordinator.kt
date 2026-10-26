package cn.limpu.hita.ui.main

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.AutoImportResult

/** Immutable identity: cookie refreshes keep the attempt, logout/account changes invalidate it. */
internal data class MainSession(val key: String?, val generation: Long, val undergraduate: Boolean) {
    companion object {
        fun from(token: EASToken): MainSession {
            val account = token.username?.takeIf(String::isNotBlank)
                ?: token.stuId?.takeIf(String::isNotBlank)
                ?: token.id?.takeIf(String::isNotBlank) ?: "authenticated"
            return MainSession(
                if (token.isLogin()) "${token.campus.name}:$account" else null,
                token.sessionGeneration,
                token.stutype == EASToken.TYPE.UNDERGRAD,
            )
        }
    }
}

internal interface MainStartupSettings {
    fun autoImportEnabled(): Boolean
    fun lastSuccessfulImport(): Long
    fun saveSuccessfulImport(time: Long)
}

internal sealed interface MainStartupAction {
    data class Import(val session: MainSession, val sequence: Long) : MainStartupAction
    data object SyncBlog : MainStartupAction
    data class CheckUpdate(val versionCode: Long) : MainStartupAction
}

/** Pure startup policy; Android work and lifecycle delivery belong to the caller. */
internal class MainStartupCoordinator(
    private val settings: MainStartupSettings,
    private val now: () -> Long,
) {
    private var session = MainSession(null, 0, true)
    private var attempted = false
    private var importSequence = 0L
    private var activeImport: MainStartupAction.Import? = null
    private var blogVisible = false
    private var blogAttempted = false
    private var checkedUpdate = false
    private var lastUpdate = 0L
    private var expiredPromptShown = false
    private var closed = false

    fun accountChanged(next: MainSession, showsBlogTab: Boolean): List<MainStartupAction> {
        if (closed) return emptyList()
        if (next.key != session.key) {
            attempted = false
            expiredPromptShown = false
        }
        if (next.key != session.key || next.generation != session.generation) activeImport = null
        session = next
        if (!blogVisible && showsBlogTab) blogAttempted = false
        blogVisible = showsBlogTab
        return initialActions()
    }

    fun start(hasLocalUser: Boolean, versionCode: Long): List<MainStartupAction> {
        if (closed) return emptyList()
        val actions = initialActions().toMutableList()
        if (now() - lastUpdate > 5 * 60 * 1000L) checkedUpdate = false
        if (!checkedUpdate) {
            // Preserve the legacy contract: logged-out starts also check, without setting the throttle.
            if (hasLocalUser) {
                checkedUpdate = true
                lastUpdate = now()
            }
            actions += MainStartupAction.CheckUpdate(versionCode)
        }
        return actions
    }

    private fun initialActions(): List<MainStartupAction> = buildList {
        if (settings.autoImportEnabled() && session.key != null && !attempted &&
            now() - settings.lastSuccessfulImport() >= 10 * 60 * 1000L
        ) {
            attempted = true
            val request = MainStartupAction.Import(session, ++importSequence)
            activeImport = request
            add(request)
        }
        if (blogVisible && !blogAttempted) {
            blogAttempted = true
            add(MainStartupAction.SyncBlog)
        }
    }

    /** Returns true exactly once when the current session needs a relogin prompt. */
    fun importFinished(request: MainStartupAction.Import, result: AutoImportResult): Boolean {
        if (closed || request != activeImport || request.session != session) return false
        activeImport = null
        if (result == AutoImportResult.SUCCESS) settings.saveSuccessfulImport(now())
        if (result != AutoImportResult.NOT_LOGGED_IN || expiredPromptShown) return false
        expiredPromptShown = true
        return true
    }

    fun reloginSucceeded() { expiredPromptShown = false }
    fun retryInterruptedUpdate() { checkedUpdate = false }
    fun close() { closed = true; activeImport = null }
}
