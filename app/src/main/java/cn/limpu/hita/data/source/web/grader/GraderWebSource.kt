package cn.limpu.hita.data.source.web.grader

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class GraderAuthenticationException : Exception("Grader authentication required")
internal class GraderNetworkException : Exception("Grader request failed")
internal data class GraderRemoteSnapshot(
    val account: GraderAccount,
    val assignments: List<GraderAssignment>,
    /** 远端已提交的作业 id：提交过即视为已完成。 */
    val submittedAssignmentIds: Set<String>,
)

/** Dedicated client: no shared cookie jar, interceptors, redirects or arbitrary request URLs. */
internal class GraderWebSource {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun fetch(cookieHeader: String, expectedAccountId: String? = null, checkActive: () -> Unit = {}): GraderRemoteSnapshot {
        val cookie = GraderSessionPolicy.sessionCookie(cookieHeader) ?: throw GraderAuthenticationException()
        checkActive()
        val account = GraderParser.account(get("/api/auth/current_user/", cookie))
        if (expectedAccountId != null && account.id != expectedAccountId) throw GraderAuthenticationException()
        checkActive()
        val courses = GraderParser.courses(get("/api/courses/?all=true", cookie))
        val assignments = mutableListOf<GraderAssignment>()
        courses.forEach { course ->
            checkActive()
            // IDs came from strict numeric parsing, never from response-provided URLs.
            assignments += GraderParser.assignments(get("/api/assignments/?course=${course.id}", cookie), course)
        }
        checkActive()
        // 提交记录是判断“已完成”的唯一来源：assignment 的 submit_count 恒为 null。
        val submitted = GraderParser.submittedAssignmentIds(get("/api/submissions/", cookie))
        checkActive()
        return GraderRemoteSnapshot(account, GraderParser.unique(assignments), submitted)
    }

    private fun get(path: String, cookie: String): String {
        val request = Request.Builder().url(GraderSessionPolicy.ORIGIN + path)
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403 || response.code in 300..399) {
                    throw GraderAuthenticationException()
                }
                if (!response.isSuccessful) throw GraderNetworkException()
                return response.body?.string() ?: throw GraderInvalidResponseException()
            }
        } catch (_: IOException) {
            throw GraderNetworkException()
        }
    }
}
