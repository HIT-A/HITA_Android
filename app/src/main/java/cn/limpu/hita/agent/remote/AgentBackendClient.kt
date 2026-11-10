package cn.limpu.hita.agent.remote

import cn.limpu.hita.BuildConfig
import cn.limpu.hita.data.analytics.AnalyticsCorrelationInterceptor
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import cn.limpu.hita.utils.LogUtils
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class SkillError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

data class CourseSearchRequest(val keyword: String)

data class BraveSearchRequest(val query: String, val count: Int = 5)

data class TeacherSearchRequest(val name: String)

data class CourseSearchResponse(val ok: Boolean, val results: Any? = null, val error: SkillError? = null)

data class BraveSearchResult(val ok: Boolean, val results: List<Map<String, Any>> = emptyList(), val error: SkillError? = null)

data class CourseReadRequest(val course_code: String, val campus: String = "shenzhen")

data class CourseReadResponse(val ok: Boolean, val course: Any? = null, val result: Any? = null, val error: SkillError? = null)

data class CourseDetailRequest(val course_code: String, val campus: String = "shenzhen")

data class CrawlRequest(val url: String, val content_filter: Boolean = true, val output_format: String = "markdown")

data class CrawlSiteRequest(val url: String, val max_pages: Int = 10, val content_filter: Boolean = true)

data class CrawlStatusRequest(val task_id: String)

data class CrawlResponse(val ok: Boolean, val result: Any? = null, val error: SkillError? = null)

data class RagQueryRequest(val query: String, val repo: String? = null, val top_k: Int = 5)

data class VisitRequest(val device_id: String)

internal fun buildAgentBackendHttpError(code: Int, rawBody: String?): SkillError {
    val body = rawBody?.trim().orEmpty()
    val parsedMessage = listOf(
        Regex("\\\"error\\\"\\s*:\\s*\\{[\\s\\S]*?\\\"message\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
        Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
        Regex("\\\"detail\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
    ).firstNotNullOfOrNull { regex ->
        regex.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
    val message = parsedMessage ?: body.takeIf { it.isNotBlank() }?.take(300) ?: "HTTP $code"
    return SkillError(
        code = "HTTP_$code",
        message = message,
        retryable = code in listOf(408, 429, 500, 502, 503),
    )
}

private fun unwrapSkillOutput(body: Map<String, Any>): Map<String, Any>? {
    val output = body["output"].asStringAnyMap() ?: return body
    val nestedOutput = output["output"].asStringAnyMap()
    return nestedOutput ?: output
}

private fun Any?.asStringAnyMap(): Map<String, Any>? {
    val map = this as? Map<*, *> ?: return null
    if (map.keys.any { it !is String }) return null
    @Suppress("UNCHECKED_CAST")
    return map as Map<String, Any>
}

private fun Any?.asStringAnyMapList(): List<Map<String, Any>>? {
    val list = this as? List<*> ?: return null
    return list.map { item -> item.asStringAnyMap() ?: return null }
}

interface AgentBackendApi {
    @POST("api/courses/search")
    fun searchCourses(@Body request: CourseSearchRequest): Call<CourseSearchResponse>

    @POST("api/courses/read")
    fun readCourse(@Body request: CourseReadRequest): Call<CourseReadResponse>

    @POST("api/teachers/search")
    fun searchTeachers(@Body request: TeacherSearchRequest): Call<Map<String, Any>>

    @POST("api/crawl/page")
    fun crawlPage(@Body request: CrawlRequest): Call<CrawlResponse>

    @POST("api/crawl/site")
    fun crawlSite(@Body request: CrawlSiteRequest): Call<CrawlResponse>

    @POST("api/crawl/status")
    fun crawlStatus(@Body request: CrawlStatusRequest): Call<Map<String, Any>>

    @POST("api/search/bocha")
    fun braveSearch(@Body request: BraveSearchRequest): Call<Map<String, Any>>

    @POST("api/rag/query")
    fun ragQuery(@Body request: RagQueryRequest): Call<Map<String, Any>>

    @POST("api/visit")
    fun visit(@Body request: VisitRequest): Call<Map<String, Any>>
}

object AgentBackendClient {

    private val BASE_URL = BuildConfig.AGENT_BACKEND_BASE_URL.removeSuffix("/") + "/"

    val api: AgentBackendApi by lazy {
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(AnalyticsCorrelationInterceptor(BASE_URL.toHttpUrl(), UsageAnalyticsClient::currentOperationId))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AgentBackendApi::class.java)
    }

    fun searchCoursesSync(keyword: String): CourseSearchResponse {
        val response = api.searchCourses(CourseSearchRequest(keyword)).execute()
        if (!response.isSuccessful) {
            return CourseSearchResponse(
                ok = false,
                error = SkillError(
                    code = "HTTP_${response.code()}",
                    message = response.errorBody()?.string()?.take(300) ?: "HTTP ${response.code()}",
                    retryable = response.code() in listOf(408, 429, 500, 502, 503),
                )
            )
        }
        val body = response.body()
            ?: return CourseSearchResponse(ok = false, error = SkillError("EMPTY", "Empty response body", false))

        if (!body.ok) return body

        val results = body.results
        val actualResults = when {
            results is Map<*, *> -> {
                val unwrapped = unwrapSkillOutput(results.asStringAnyMap() ?: return body)
                val data = unwrapped?.get("data") as? Map<*, *>
                data?.get("results") ?: unwrapped?.get("results") ?: results
            }
            results != null -> results
            else -> null
        }

        if (actualResults != null) {
            return CourseSearchResponse(ok = true, results = actualResults)
        }

        return body
    }

    fun searchTeacherSync(name: String): String? {
        return try {
            val response = api.searchTeachers(TeacherSearchRequest(name)).execute()
            if (!response.isSuccessful) {
                LogUtils.w("searchTeacher HTTP ${response.code()}: ${response.errorBody()?.string()?.take(200)}")
                return "教师搜索失败: HTTP ${response.code()}"
            }
            val body = response.body()
            if (body == null) return "教师搜索失败: 空响应"
            
            val ok = body["ok"] as? Boolean ?: false
            if (!ok) {
                val error = body["error"] as? Map<*, *>
                return "教师搜索失败: ${error?.get("message") ?: "未知错误"}"
            }
            
            val unwrapped = unwrapSkillOutput(body)
            if (unwrapped == null) return "教师搜索失败: 无法解析响应"

            val success = unwrapped["success"] as? Boolean ?: false
            if (!success) {
                val errorMsg = unwrapped["error"]?.toString() ?: "教师不存在或无主页"
                return "教师搜索失败: $errorMsg"
            }

            val teacher = unwrapped["teacher"] as? Map<*, *>
            var teacherName = teacher?.get("name") as? String 
                ?: unwrapped["name"] as? String 
                ?: ""
            
            if (teacherName.isBlank()) {
                teacherName = name
            }
            val homepage = unwrapped["homepage"] as? String ?: ""
            
            val rawContent = unwrapped["raw_content"] as? Map<*, *>
            val markdown = rawContent?.get("fit_markdown") as? String
                ?: rawContent?.get("content") as? String
                ?: unwrapped["markdown"] as? String
                ?: ""

            if (markdown.isBlank()) {
                return "教师信息（$teacherName）：暂无详细信息"
            }

            buildString {
                append("教师信息：$teacherName\n")
                if (homepage.isNotBlank()) {
                    append("主页：$homepage\n")
                }
                append("\n")
                append(markdown.take(3000))
            }
        } catch (e: Exception) {
            LogUtils.e( "searchTeacher exception: ${e.message}", e)
            "教师搜索失败: ${e.message}"
        }
    }

    fun crawlPageSync(url: String): String? {
        return try {
            val response = api.crawlPage(CrawlRequest(url = url)).execute()
            if (!response.isSuccessful) return null
            val body = response.body() ?: return null
            if (!body.ok) return null
            val result = body.result as? Map<*, *> ?: return null
            val content = result["content"] as? List<*> ?: return null
            if (content.isEmpty()) return null
            val first = content[0] as? Map<*, *> ?: return null
            val text = first["text"] as? String ?: return null
            val parsed = org.json.JSONObject(text)
            parsed.optString("content", "").takeIf { it.isNotEmpty() } ?: text
        } catch (e: Exception) {
            null
        }
    }

    fun crawlSiteSync(url: String, maxPages: Int = 10): String? {
        return try {
            val response = api.crawlSite(CrawlSiteRequest(url = url, max_pages = maxPages)).execute()
            if (!response.isSuccessful) return null
            val body = response.body() ?: return null
            if (!body.ok) return null
            val result = body.result as? Map<*, *> ?: return null
            val taskId = result["task_id"] as? String ?: return null
            "站点爬取已启动，任务ID: $taskId"
        } catch (e: Exception) {
            null
        }
    }

    fun crawlStatusSync(taskId: String): String? {
        return try {
            val response = api.crawlStatus(CrawlStatusRequest(task_id = taskId)).execute()
            if (!response.isSuccessful) return null
            val body = response.body() ?: return null
            val status = body["status"] as? String ?: "unknown"
            val progress = body["progress"] as? Map<*, *>
            val percent = progress?.get("percent") as? Double ?: 0.0
            "爬取状态: $status (${percent.toInt()}%)"
        } catch (e: Exception) {
            null
        }
    }

    fun braveSearchSync(query: String): BraveSearchResult {
        return try {
            val response = api.braveSearch(BraveSearchRequest(query = query, count = 5)).execute()
            if (!response.isSuccessful) {
                return BraveSearchResult(ok = false, error = buildAgentBackendHttpError(response.code(), response.errorBody()?.string()))
            }
            val body = response.body()
                ?: return BraveSearchResult(ok = false, error = SkillError("EMPTY", "Empty response body", false))
            val ok = body["ok"] as? Boolean ?: false
            if (!ok) {
                val error = body["error"] as? Map<*, *>
                return BraveSearchResult(
                    ok = false,
                    error = SkillError(
                        code = error?.get("code")?.toString() ?: "BACKEND_ERROR",
                        message = error?.get("message")?.toString()?.takeIf { it.isNotBlank() } ?: "未知错误",
                        retryable = false,
                    ),
                )
            }
            val unwrapped = unwrapSkillOutput(body)
            val results = unwrapped?.get("results").asStringAnyMapList() ?: emptyList()
            BraveSearchResult(ok = true, results = results)
        } catch (e: Exception) {
            BraveSearchResult(
                ok = false,
                error = SkillError("EXCEPTION", e.message ?: "网页搜索请求失败", true),
            )
        }
    }

    fun readCourseSync(courseCode: String): CourseReadResponse {
        return try {
            val response = api.readCourse(CourseReadRequest(course_code = courseCode)).execute()
            if (!response.isSuccessful) {
                return CourseReadResponse(ok = false, error = buildAgentBackendHttpError(response.code(), response.errorBody()?.string()))
            }
            val body = response.body()
                ?: return CourseReadResponse(ok = false, error = SkillError("EMPTY", "Empty response body", false))

            if (!body.ok) return body

            val course = body.course
            val actualCourse = when {
                course is Map<*, *> -> {
                    val unwrapped = unwrapSkillOutput(course.asStringAnyMap() ?: return body)
                    val data = unwrapped?.get("data") as? Map<*, *>
                    data?.get("result") ?: unwrapped?.get("result") ?: course
                }
                course != null -> course
                else -> null
            }

            if (actualCourse != null) {
                return CourseReadResponse(ok = true, course = actualCourse)
            }

            return body
        } catch (e: Exception) {
            CourseReadResponse(ok = false, error = SkillError("EXCEPTION", e.message ?: "课程详情读取失败", true))
        }
    }

    fun ragQuerySync(query: String): String? {
        return try {
            val response = api.ragQuery(RagQueryRequest(query = query, top_k = 10)).execute()
            if (!response.isSuccessful) {
                LogUtils.w("RAG query HTTP ${response.code()}: ${response.errorBody()?.string()?.take(200)}")
                return "RAG 查询失败: HTTP ${response.code()}"
            }
            val body = response.body() ?: return "RAG 查询失败: 空响应"
            LogUtils.d("RAG raw response: $body")
            val ok = body["ok"] as? Boolean ?: false
            if (!ok) {
                val error = body["error"] as? Map<*, *>
                return "RAG 查询失败: ${error?.get("message") ?: "未知错误"}"
            }
            val unwrapped = unwrapSkillOutput(body)
            val result = unwrapped ?: body
            LogUtils.d("RAG unwrapped result keys: ${result.keys}")
            // RAG 接口的数据在 result["result"]["hits"] 中
            val data = result["result"].asStringAnyMap() ?: result
            val hits = data["hits"].asStringAnyMapList()
                ?: data["results"].asStringAnyMapList()
                ?: data["documents"].asStringAnyMapList()
            if (hits == null || hits.isEmpty()) {
                return "未找到相关内容。"
            }
            buildString {
                val displayedHits = hits.take(MAX_RAG_HITS)
                append("RAG 返回 ${hits.size} 条相关内容，展示前 ${displayedHits.size} 条摘要:\n")
                displayedHits.forEachIndexed { index, h ->
                    val docId = cleanRagText(h["doc_id"]?.toString().orEmpty())
                    val title = cleanRagText(h["title"]?.toString().orEmpty())
                        .takeIf { it.isNotBlank() }
                        ?: docId.takeIf { it.isNotBlank() }
                        ?: "资料 ${index + 1}"
                    val snippet = cleanRagText(h["snippet"]?.toString().orEmpty())
                        .take(MAX_RAG_SNIPPET_CHARS)
                    val score = h["score"]?.toString()?.take(5) ?: ""
                    val source = cleanRagText(h["source"]?.toString().orEmpty())
                    append("\n• [$title]")
                    if (score.isNotBlank()) append(" 相关度:$score")
                    if (source.isNotBlank()) append(" 来源:$source")
                    if (snippet.isNotBlank()) append("\n  $snippet")
                }
            }
        } catch (e: Exception) {
            LogUtils.e( "RAG query exception: ${e.message}", e)
            "RAG 查询失败: ${e.message}"
        }
    }

    fun reportVisit(deviceId: String) {
        try {
            api.visit(VisitRequest(device_id = deviceId)).execute()
        } catch (e: Exception) {
        }
    }

    private fun cleanRagText(value: String): String {
        return decodeUnicodeEscapes(value)
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeUnicodeEscapes(value: String): String {
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private const val MAX_RAG_HITS = 6
    private const val MAX_RAG_SNIPPET_CHARS = 1200
}
