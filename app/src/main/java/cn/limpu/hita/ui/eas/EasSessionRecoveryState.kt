package cn.limpu.hita.ui.eas

/** Keeps the recovery budget across retries; only a completed query starts a new cycle. */
internal class EasSessionRecoveryState(private val maxRetries: Int = 3) {
    private var retryCount = 0
    private var pendingRetry: (() -> Boolean)? = null
    val isRecovering: Boolean get() = pendingRetry != null

    fun begin(retry: () -> Boolean): Boolean {
        if (isRecovering || retryCount >= maxRetries) return false
        pendingRetry = retry
        return true
    }

    fun loginSucceeded() {
        val retry = pendingRetry ?: return
        // Clear before invoking: an immediate result must not erase a newly queued recovery.
        pendingRetry = null
        retryCount++
        if (!retry()) retryCount = 0
    }

    fun cancel() {
        pendingRetry = null
    }

    fun querySucceeded() {
        // A response from another query must not abandon an open login window.
        if (isRecovering) return
        retryCount = 0
    }
}
