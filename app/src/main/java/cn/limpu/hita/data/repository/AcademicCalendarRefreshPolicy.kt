package cn.limpu.hita.data.repository

/** Used by the repository's single active IO refresh. Success and retry windows are per campus. */
internal class AcademicCalendarRefreshPolicy {
    private val attempts = mutableMapOf<String, Long>()
    fun begin(campus: String, now: Long, hasCache: Boolean, updatedAt: Long): Boolean {
        val attemptedAt = attempts[campus]
        if (attemptedAt != null && now - attemptedAt in 0 until 15 * 60_000L) return false
        if (hasCache && updatedAt > 0 && now - updatedAt in 0 until 24 * 60 * 60_000L) return false
        attempts[campus] = now
        return true
    }
}
