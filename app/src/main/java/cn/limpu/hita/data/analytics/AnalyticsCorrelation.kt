package cn.limpu.hita.data.analytics

import kotlinx.coroutines.asContextElement
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import java.util.UUID

/** Scoped to one ReAct operation, including coroutine dispatch; never carries an installation ID. */
internal object AnalyticsOperationContext {
    data class Value(val id: String, val generation: Long)
    private val current = ThreadLocal<Value?>()
    fun get(): Value? = current.get()
    fun coroutineContext(value: Value) = current.asContextElement(value)
    fun <T> withOperation(value: Value, block: () -> T): T {
        val previous = current.get()
        current.set(value)
        return try { block() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}

/** Network interception also removes these headers on cross-origin redirects. */
internal class AnalyticsCorrelationInterceptor(
    private val origin: HttpUrl,
    private val operationId: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain) = chain.proceed(correlate(chain.request()))
    internal fun correlate(request: Request): Request {
        val builder = request.newBuilder().removeHeader("X-HITA-Operation-ID").removeHeader("X-HITA-Trace-ID")
        if (request.url.scheme == origin.scheme && request.url.host == origin.host && request.url.port == origin.port) {
            val id = operationId()?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
            if (id != null) builder.header("X-HITA-Operation-ID", id)
        }
        return builder.build()
    }
}
