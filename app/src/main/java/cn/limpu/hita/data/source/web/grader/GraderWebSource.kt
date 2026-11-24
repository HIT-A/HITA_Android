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

/**
 * Dedicated client: no shared cookie jar, interceptors, redirects or arbitrary request URLs.
 *
 * 请求始终打在会话建立时用的那个入口上：校园网内是 `grader.hitsz.edu.cn`，校外是
 * aTrust 网关域。跨入口复用同一个会话没有意义——网关会话 cookie 挂在网关域上。
 */
internal class GraderWebSource {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun fetch(
        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        expectedAccountId: String? = null,
        checkActive: () -> Unit = {},
    ): GraderRemoteSnapshot {
        val cookie = GraderSessionPolicy.cookieHeader(entry, cookieHeader)
            ?: throw GraderAuthenticationException()
        checkActive()
        val account = GraderParser.account(get(entry, "/api/auth/current_user/", cookie))
        if (expectedAccountId != null && account.id != expectedAccountId) throw GraderAuthenticationException()
        checkActive()
        val courses = GraderParser.courses(get(entry, "/api/courses/?all=true", cookie))
        val assignments = mutableListOf<GraderAssignment>()
        courses.forEach { course ->
            checkActive()
            // IDs came from strict numeric parsing, never from response-provided URLs.
            assignments += GraderParser.assignments(get(entry, "/api/assignments/?course=${course.id}", cookie), course)
        }
        checkActive()
        // 提交记录是判断“已完成”的唯一来源：assignment 的 submit_count 恒为 null。
        val submitted = GraderParser.submittedAssignmentIds(get(entry, "/api/submissions/", cookie))
        checkActive()
        return GraderRemoteSnapshot(account, GraderParser.unique(assignments), submitted)
    }

    private fun get(entry: GraderSessionPolicy.Entry, path: String, cookie: String): String {
        val request = Request.Builder().url(entry.origin + path)
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .get().build()
        try {
            client.newCall(request).execute().use { response ->
                // 网关会话过期时同样以 302 回信任门户，因此 3xx 一律按未登录处理。
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
