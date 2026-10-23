package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import java.util.concurrent.atomic.AtomicReference

/** A single import may finish, expire or commit once; a late response cannot reopen it. */
internal class EasTimetableImportGuard(
    token: EASToken,
    private val generation: Long,
    private val deadlineMillis: Long,
) {
    private enum class State { WAITING, PROCESSING, COMMITTING, FINISHED }
    private val state = AtomicReference(State.WAITING)
    private val campus = token.campus
    private val studentType = token.stutype
    private val username = token.username?.trim()?.takeIf { it.isNotEmpty() }
    private val studentId = token.stuId?.trim()?.takeIf { it.isNotEmpty() }
    private val accountId = token.id?.trim()?.takeIf { it.isNotEmpty() }

    fun isCurrentSession(currentGeneration: Long, current: EASToken): Boolean =
        currentGeneration == generation && current.isLogin() &&
            current.campus == campus && current.stutype == studentType &&
            compatible(username, current.username) && compatible(studentId, current.stuId) &&
            compatible(accountId, current.id)

    fun tryStartProcessing(): Boolean = state.compareAndSet(State.WAITING, State.PROCESSING)

    fun isCommitting(): Boolean = state.get() == State.COMMITTING

    /** Call under the shared timetable and token-state locks immediately before any side effects. */
    fun tryBeginCommit(currentGeneration: Long, current: EASToken, nowMillis: Long): Boolean =
        nowMillis < deadlineMillis && isCurrentSession(currentGeneration, current) &&
            state.compareAndSet(State.PROCESSING, State.COMMITTING)

    /** Timeouts and network failures must never cancel a write that already owns the commit. */
    fun finishWaiting(): Boolean {
        while (true) {
            val previous = state.get()
            if (previous == State.COMMITTING || previous == State.FINISHED) return false
            if (state.compareAndSet(previous, State.FINISHED)) return true
        }
    }

    /** Only the single processing worker calls this, after success or an exception. */
    fun finishProcessing(): Boolean = state.getAndSet(State.FINISHED) != State.FINISHED

    private fun compatible(expected: String?, actual: String?): Boolean {
        val knownActual = actual?.trim()?.takeIf { it.isNotEmpty() }
        // Session enrichment can fill an identifier that was unavailable at import start.
        return expected == null || knownActual == null || expected == knownActual
    }
}
