package cn.limpu.hita.ui.eas.login

import cn.limpu.hita.data.model.eas.EASToken

/** Pending form work belongs to one document, even when a reload returns to the same URL. */
internal class EasLoginPageState {
    data class Page(val url: String, val generation: Long)
    private var generation = 0L
    private val submittedPages = mutableSetOf<String>()

    fun navigated() { generation++ }

    fun capture(campus: EASToken.Campus, url: String): Page? =
        if (EasWebCredentialPolicy.matchesLoginPage(campus, url, isMainFrame = true)) {
            Page(url.substringBefore('#'), generation)
        } else null

    fun isCurrent(page: Page, campus: EASToken.Campus, currentUrl: String): Boolean =
        page == capture(campus, currentUrl)

    /** No repeated submission to one form and at most two distinct forms per login window. */
    fun claimSubmission(page: Page): Boolean =
        page.generation == generation && submittedPages.size < 2 && submittedPages.add(page.url)
}
