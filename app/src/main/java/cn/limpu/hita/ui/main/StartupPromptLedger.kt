package cn.limpu.hita.ui.main

/**
 * Dedupes version prompts for one process lifetime.
 *
 * A version announcement and an update dialog can both target the running version.
 * Whichever channel claims first is shown; the other stays silent until the next process.
 */
internal class StartupPromptLedger {
    private val shownVersions = mutableSetOf<Long>()

    /** Returns true only the first time this version is claimed. */
    fun claimVersionPrompt(versionCode: Long): Boolean = shownVersions.add(versionCode)
}
