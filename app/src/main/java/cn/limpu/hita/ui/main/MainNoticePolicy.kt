package cn.limpu.hita.ui.main

import cn.limpu.hita.data.notice.AppNotice

sealed interface MainNoticeSelection {
    data object None : MainNoticeSelection
    data class Critical(val notice: AppNotice) : MainNoticeSelection
    data class Version(val notice: AppNotice) : MainNoticeSelection
    data class Unseen(val notices: List<AppNotice>) : MainNoticeSelection
}

/** Receives the already-active fetched notices; marking seen belongs to presentation. */
object MainNoticePolicy {
    fun select(
        notices: List<AppNotice>,
        seenIds: Set<String>,
        currentVersionCode: Long,
    ): MainNoticeSelection {
        notices.firstOrNull { it.isCritical }?.let { return MainNoticeSelection.Critical(it) }
        notices.firstOrNull { it.isVersionKind && (it.minAppVersion ?: Long.MIN_VALUE) > currentVersionCode }
            ?.let { return MainNoticeSelection.Version(it) }
        val unseen = notices.filter { notice ->
            notice.id !in seenIds && !notice.isCritical &&
                !(notice.isVersionKind && notice.minAppVersion != null && notice.minAppVersion > currentVersionCode)
        }
        return if (unseen.isEmpty()) MainNoticeSelection.None else MainNoticeSelection.Unseen(unseen)
    }
}
