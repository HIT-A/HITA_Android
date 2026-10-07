package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.*
import java.util.*

internal class CourseSelectionExecutionTokenStore(
    private val loadToken: () -> EASToken
) {
    private data class ActiveExecution(
        val owner: Any,
        val token: EASToken
    )

    private val lock = Any()
    private val activeExecutions = mutableMapOf<String, ActiveExecution>()

    fun begin(job: CourseSelectionJob, owner: Any): EASToken = synchronized(lock) {
        check(job.id !in activeExecutions) { "Course-selection job is already executing" }
        loadToken().also { token ->
            CourseSelectionCredentialScopePolicy.requireMatching(job, token)
            activeExecutions[job.id] = ActiveExecution(owner, token)
        }
    }

    fun requireToken(job: CourseSelectionJob): EASToken = synchronized(lock) {
        CourseSelectionCredentialScopePolicy.requireMatching(job, loadToken())
        activeExecutions[job.id]?.token ?: error("Course-selection job is not executing")
    }

    fun end(jobId: String, owner: Any) {
        synchronized(lock) {
            if (activeExecutions[jobId]?.owner === owner) {
                activeExecutions.remove(jobId)
            }
        }
    }
}

