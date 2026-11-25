package cn.limpu.hita.data.source.web.grader

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

internal class GraderAuthenticationException : Exception("Grader authentication required")
/**
 * 服务端拒绝或传输失败。带状态码与响应片段：提交作业出问题时，这几行就是唯一线索
 * （部分设备把应用日志关了）。
 */
/** 校外无法直连作业存储（内网对象存储）。 */
internal class GraderUploadStorageException : Exception("Grader upload storage unreachable")

internal class GraderNetworkException(
    val code: Int = 0,
    detail: String? = null,
) : Exception("Grader request failed: $code ${detail.orEmpty()}")
internal data class GraderRemoteSnapshot(
    val account: GraderAccount,
    val assignments: List<GraderAssignment>,
    /** 远端已提交的作业 id：提交过即视为已完成。 */
    val submittedAssignmentIds: Set<String>,
)

/** 待上传文件：名字用于服务端登记与 multipart 头，大小用于进度与本地预检。 */
internal class GraderUploadFile(
    val name: String,
    val sizeBytes: Long,
    val open: () -> InputStream,
)

/**
 * Dedicated client: no shared cookie jar, interceptors, redirects or arbitrary request URLs.
 *
 * 请求始终打在会话建立时用的那个入口上：校园网内是 `grader.hitsz.edu.cn`，校外是
 * aTrust 网关域。跨入口复用同一个会话没有意义——网关会话 cookie 挂在网关域上。
 */
internal class GraderWebSource : GraderSubmitRemote {
    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** 上传单独一套超时：大文件走蜂窝与网关时 45 秒远远不够。 */
    private val uploadClient by lazy {
        client.newBuilder()
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(1, TimeUnit.MINUTES)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()
    }

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

    /** 提交前拉作业详情，补齐列表里可能缺失的说明与提交限制。 */
    override fun assignmentDetail(

        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        assignmentId: String,
        fallbackTitle: String,
    ): GraderAssignmentDetail {
        val cookie = GraderSessionPolicy.cookieHeader(entry, cookieHeader)
            ?: throw GraderAuthenticationException()
        return GraderParser.assignmentDetail(
            get(entry, "/api/assignments/${numericId(assignmentId)}/", cookie),
            fallbackTitle,
        )
    }

    /**
     * 提交第一步：申请上传目标。
     *
     * 请求体照抄 Grader 前端：这里的 `ident` 传的是上传元数据（类型与作业 id），
     * 服务端据此返回签名地址与真正的 ident。
     */
    override fun requestUpload(

        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        fileName: String,
        assignmentId: String,
    ): GraderUploadTarget {
        val cookie = GraderSessionPolicy.cookieHeader(entry, cookieHeader)
            ?: throw GraderAuthenticationException()
        val body = """{"original_file_name":${jsonString(fileName)},"ident":{"type":"submission",""" +
            """"assignment_id":${numericId(assignmentId)}}}"""
        return GraderParser.uploadTarget(postJson(entry, "/api/files/touch/", cookie, body))
    }

    /**
     * 提交第二步：把文件传到服务端签发的地址。
     *
     * 目标先过 [GraderSubmissionPolicy.isSameEntry]——带 cookie 与 CSRF 头的请求绝不能
     * 发往别的源。
     */
    override fun upload(
        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        target: GraderUploadTarget,
        file: GraderUploadFile,
        checkActive: () -> Unit,
        onProgress: (Long) -> Unit,
    ) {
        val plan = GraderSubmissionPolicy.plan(entry, target.url)
            ?: throw GraderInvalidResponseException("upload url invalid: ${target.url}")
        val destination = plan as GraderSubmissionPolicy.UploadPlan.Send
        val cookie = GraderSessionPolicy.cookieHeader(entry, cookieHeader)
            ?: throw GraderAuthenticationException()
        checkActive()
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        target.params.forEach { (key, value) -> multipart.addFormDataPart(key, value) }
        multipart.addFormDataPart("file", sanitizeFileName(file.name), ProgressRequestBody(file, onProgress))
        val request = Request.Builder().url(destination.url)
            .header("Accept", "application/json")
            .apply {
                // 对象存储是预签名上传：凭据由表单签名承担，绝不附上会话 cookie。
                if (destination.authenticated) {
                    header("Cookie", cookie)
                    GraderSessionPolicy.csrfToken(cookie)?.let { header("X-CSRFTOKEN", it) }
                }
            }
            .post(multipart.build())
            .build()
        try {
            execute(uploadClient, request, checkActive)
        } catch (error: GraderNetworkException) {
            // code == 0 是连不上（目标不可达），4xx 是存储拒绝上传本身。
            if (error.code == 0 && entry == GraderSessionPolicy.Entry.GATEWAY) throw GraderUploadStorageException()
            throw error
        }
    }

    /** 提交第三步：上传收尾。 */
    override fun finishUpload(

        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        ident: String,
    ) {
        val cookie = GraderSessionPolicy.cookieHeader(entry, cookieHeader)
            ?: throw GraderAuthenticationException()
        postJson(entry, "/api/files/finish/", cookie, """{"ident":${jsonString(ident)}}""")
    }

    /** 提交第四步：创建提交记录；服务端以它为准把该作业标为已提交。 */
    override fun createSubmission(

        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        assignmentId: String,
        ident: String,
    ) {
        val cookie = GraderSessionPolicy.cookieHeader(entry, cookieHeader)
            ?: throw GraderAuthenticationException()
        postJson(
            entry,
            "/api/submissions/?assignment=${numericId(assignmentId)}&file_ident=${encodeQuery(ident)}",
            cookie,
            "{}",
        )
    }

    private fun get(entry: GraderSessionPolicy.Entry, path: String, cookie: String): String {
        val request = Request.Builder().url(entry.origin + path)
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .get().build()
        return execute(client, request)
    }

    private fun postJson(
        entry: GraderSessionPolicy.Entry,
        path: String,
        cookie: String,
        body: String,
    ): String {
        val request = Request.Builder().url(entry.origin + path)
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .apply { GraderSessionPolicy.csrfToken(cookie)?.let { header("X-CSRFTOKEN", it) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(client, request)
    }

    private fun execute(
        http: OkHttpClient,
        request: Request,
        checkActive: () -> Unit = {},
    ): String {
        try {
            http.newCall(request).execute().use { response ->
                checkActive()
                // 网关会话过期时同样以 302 回信任门户，因此 3xx 一律按未登录处理。
                if (response.code == 401 || response.code == 403 || response.code in 300..399) {
                    throw GraderAuthenticationException()
                }
                if (!response.isSuccessful) {
                    val body = runCatching { response.peekBody(512).string() }.getOrNull()
                    throw GraderNetworkException(response.code, body)
                }
                return response.body?.string() ?: throw GraderInvalidResponseException()
            }
        } catch (_: IOException) {
            // 认证/网络异常不是 IOException 的子类，走不到这里；这里只兜传输层失败。
            throw GraderNetworkException()
        }
    }

    private class ProgressRequestBody(
        private val file: GraderUploadFile,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = OCTET_STREAM_MEDIA_TYPE

        override fun contentLength(): Long = file.sizeBytes

        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            file.open().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    written += read
                    onProgress(written)
                }
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaTypeOrNull()

        /** 文件名会进 multipart 头，引号与换行必须去掉。 */
        fun sanitizeFileName(name: String): String =
            name.filterNot { it == '"' || it == '\r' || it == '\n' }.ifBlank { "submission" }

        /** 作业 id 参与 URL 与 JSON，必须是纯数字，避免拼出别的路径。 */
        fun numericId(value: String): String {
            if (value.isEmpty() || value[0] !in '1'..'9' || value.any { it !in '0'..'9' }) {
                throw GraderInvalidResponseException()
            }
            return value
        }

        fun jsonString(value: String): String {
            val builder = StringBuilder(value.length + 2).append('"')
            for (char in value) when (char) {
                '"', '\\' -> builder.append('\\').append(char)
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else ->
                    if (char < ' ') builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    else builder.append(char)
            }
            return builder.append('"').toString()
        }

        fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}
