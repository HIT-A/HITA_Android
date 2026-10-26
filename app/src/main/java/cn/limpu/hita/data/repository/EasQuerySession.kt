package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.source.web.service.EASService
import java.util.Calendar
import cn.limpu.hita.utils.TermUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class TermQueryKind { ACADEMIC, SHENZHEN_SELECTION_WEB }

/** Credentials remain private to the captured source; consumers use immutable scope fields. */
internal class EasQuerySession(
    val scope: EasQueryScope,
    internal val token: EASToken,
    internal val service: EASService,
    private val currentScope: () -> EasQueryScope,
    calendar: Calendar = Calendar.getInstance(),
    private val scopes: StateFlow<EasQueryScope>? = null,
) {
    val campus get() = scope.campus
    val ownerKey get() = scope.ownerKey
    val calendarYear = calendar.get(Calendar.YEAR)
    val calendarMonth = calendar.get(Calendar.MONTH)
    val grade = scope.grade?.takeIf(String::isNotBlank)
        ?: if (scope.campus == EASToken.Campus.SHENZHEN) {
            TermUtils.enrollmentGradeFromStudentId(token.stuId, calendarYear)
        } else scope.grade
    val studentType get() = scope.studentType
    val loggedIn get() = scope.loggedIn
    fun isCurrent(): Boolean = currentScope() == scope
    fun ensureCurrent() {
        if (!isCurrent()) throw CancellationException("The EAS query belongs to an expired session")
    }

    suspend fun <T> whileCurrent(block: suspend () -> T): T = coroutineScope {
        ensureCurrent()
        val operation = this
        val watcher = scopes?.let { changes ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                changes.first { it != scope }
                operation.cancel("The EAS query session changed")
            }
        }
        try { block().also { ensureCurrent() } } finally { watcher?.cancel() }
    }
}
