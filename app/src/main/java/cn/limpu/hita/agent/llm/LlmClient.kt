package cn.limpu.hita.agent.llm

import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import android.os.SystemClock
import android.content.Context
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.data.repository.AiChatProvider
import cn.limpu.hita.data.repository.AiSettings
import cn.limpu.hita.data.repository.AiSettingsRepository
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface LlmApiService {
    @POST("v1/chat/completions")
    fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest,
    ): Call<ChatCompletionResponse>
}

// 智谱多模态API接口
interface ZhipuApiService {
    @POST("api/paas/v4/chat/completions")
    fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ZhipuChatRequest,
    ): Call<ZhipuChatResponse>
}

// 智谱请求数据类
data class ZhipuChatRequest(
    val model: String = "glm-4.6v-flash",
    val messages: List<ZhipuMessage>,
    val stream: Boolean = false
)

data class ZhipuMessage(
    val role: String,
    val content: List<ZhipuContent>
)

data class ZhipuContent(
    val type: String,
    val text: String? = null,
    val image_url: ZhipuImageUrl? = null,
    val video_url: ZhipuVideoUrl? = null
)

data class ZhipuImageUrl(val url: String)
data class ZhipuVideoUrl(val url: String)

// 智谱响应数据类
data class ZhipuChatResponse(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<ZhipuChoice>,
    val usage: ZhipuUsage
)

data class ZhipuChoice(
    val index: Int,
    val message: ZhipuResponseMessage,
    val finish_reason: String
)

data class ZhipuResponseMessage(
    val role: String,
    val content: String
)

data class ZhipuUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

object LlmClient {
    private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/"

    const val MODEL = "deepseek-v4-flash"

    private val directService: LlmApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(DEEPSEEK_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlmApiService::class.java)
    }

    suspend fun chatCompletion(context: Context, request: ChatCompletionRequest): Response<ChatCompletionResponse> {
        val settings = AiSettingsRepository(context).getSettings()
        val apiKey = resolveDeepSeekApiKey(
            chatProvider = settings.chatProvider,
            builtInKey = BuildConfig.DEEPSEEK_API_KEY,
            customKey = settings.customDeepSeekApiKey,
        )
        val started = SystemClock.elapsedRealtime()
        var outcome = "failure"
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        try {
            val response = directService.chatCompletion(authHeader(apiKey), request).awaitCancellable()
            if (response.isSuccessful && response.body() != null) outcome = "success"
            response.body()?.usage?.let { inputTokens = it.promptTokens?.toLong(); outputTokens = it.completionTokens?.toLong() }
            return response
        } finally {
            UsageAnalyticsClient.measurement(UsageAnalyticsEvent.MODEL_REQUEST_FINISHED,
                mapOf("provider" to "deepseek", "model" to request.model, "outcome" to outcome),
                SystemClock.elapsedRealtime() - started, inputTokens, outputTokens)
        }
    }

    private fun authHeader(apiKeyOrToken: String): String {
        return if (apiKeyOrToken.startsWith("Bearer ", ignoreCase = true)) {
            apiKeyOrToken
        } else {
            "Bearer $apiKeyOrToken"
        }
    }
}

/**
 * Retrofit's blocking `execute()` has no coroutine cancellation hook. Keep the underlying
 * OkHttp call tied to the caller's Job so deleting a chat session can stop an in-flight request.
 */
internal suspend fun <T> Call<T>.awaitCancellable(): Response<T> =
    suspendCancellableCoroutine { continuation: CancellableContinuation<Response<T>> ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call<T>, error: Throwable) {
                continuation.resumeWithException(error)
            }
        })
    }

internal fun resolveDeepSeekApiKey(
    chatProvider: AiChatProvider,
    builtInKey: String,
    customKey: String,
): String {
    val builtIn = builtInKey.trim()
    val custom = customKey.trim()
    return when (chatProvider) {
        AiChatProvider.CUSTOM_DEEPSEEK -> custom.ifBlank {
            throw IllegalStateException("请先在 AI 设置中填写 DeepSeek API Key")
        }
        AiChatProvider.BUILTIN_DEEPSEEK -> builtIn.ifBlank { custom }.ifBlank {
            throw IllegalStateException("未配置内置 DeepSeek API Key，请在 AI 设置中填写自定义 Key")
        }
    }
}

object ZhipuClient {
    private const val BASE_URL = "https://open.bigmodel.cn/"

    const val MODEL = "glm-4.6v-flash"

    val service: ZhipuApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ZhipuApiService::class.java)
    }

    fun authHeader(context: Context): String {
        val customKey = AiSettingsRepository(context).getCustomZhipuApiKey()
        val apiKey = customKey.ifBlank { BuildConfig.ZHIPU_API_KEY.trim() }
        if (apiKey.isBlank()) {
            throw IllegalStateException("未配置智谱 API Key")
        }
        return if (apiKey.startsWith("Bearer ", ignoreCase = true)) apiKey else "Bearer $apiKey"
    }
}
