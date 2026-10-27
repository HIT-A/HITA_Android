package cn.limpu.hita.agent.core

fun interface AgentCancellation {
    fun cancel()

    companion object {
        val NONE = AgentCancellation {}
    }
}

enum class AgentRetrySafety { NEVER, IDEMPOTENT }
enum class AgentToolErrorType { TIMEOUT, TOOL_FAILURE, CANCELLED }

interface AgentTool<I, O> {
    val name: String
    /** Retrying a timeout can overlap work that the underlying API cannot cancel. */
    val retrySafety: AgentRetrySafety get() = AgentRetrySafety.NEVER
    fun execute(input: I, onResult: (AgentToolResult<O>) -> Unit): AgentCancellation
}

/** The constructor cannot represent success with an error or failure with data. */
sealed class AgentToolResult<out T> {
    abstract val attempt: Int
    val ok: Boolean get() = this is Success
    val data: T? get() = (this as? Success)?.value
    val error: String? get() = (this as? Failure)?.message
    val errorType: AgentToolErrorType? get() = (this as? Failure)?.kind

    data class Success<T>(val value: T, override val attempt: Int = 1) : AgentToolResult<T>() {
        init { require(value != null); require(attempt > 0) }
    }

    data class Failure(
        val kind: AgentToolErrorType,
        val message: String,
        override val attempt: Int = 1,
    ) : AgentToolResult<Nothing>() {
        init { require(message.isNotBlank()); require(attempt >= 0) }
    }

    fun <R> map(transform: (T) -> R): AgentToolResult<R> = when (this) {
        is Success -> Success(transform(value), attempt)
        is Failure -> this
    }

    internal fun atAttempt(attempt: Int): AgentToolResult<T> = when (this) {
        is Success -> copy(attempt = attempt)
        is Failure -> copy(attempt = attempt)
    }

    companion object {
        fun <T> success(data: T): AgentToolResult<T> = Success(data)
        fun failure(error: String): Failure = Failure(
            AgentToolErrorType.TOOL_FAILURE,
            AgentTraceSanitizer.sanitizeError(error.ifBlank { "工具执行失败" }),
        )
        fun cancelled(attempt: Int = 0): Failure = Failure(AgentToolErrorType.CANCELLED, "工具执行已取消", attempt)
        fun timeout(timeoutMs: Long, attempt: Int): Failure =
            Failure(AgentToolErrorType.TIMEOUT, "工具执行超时（${timeoutMs}ms）", attempt)
    }
}

/** Preserves the typed cause when adapting to Kotlin Result at a UI boundary. */
class AgentToolException(val failure: AgentToolResult.Failure) : Exception(failure.message)

internal inline fun notifyAgentObserver(callback: () -> Unit) {
    // User observers are outside the tool failure/retry contract.
    try { callback() } catch (_: Exception) { }
}
