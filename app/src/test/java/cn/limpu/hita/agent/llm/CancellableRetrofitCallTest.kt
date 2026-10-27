package cn.limpu.hita.agent.llm

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Timeout
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class CancellableRetrofitCallTest {
    @Test
    fun cancellationCancelsCallAndIgnoresLateFailure() = runBlocking {
        val call = ControlledCall<Unit>()
        val result = async { call.awaitCancellable() }
        call.enqueued.await()

        result.cancelAndJoin()
        assertTrue(call.cancelled.get())

        call.fail(IOException("late response"))
        assertTrue(result.isCancelled)
    }

    @Test
    fun callbackResponseCompletesTheSuspension() = runBlocking {
        val call = ControlledCall<Unit>()
        val result = async { call.awaitCancellable() }
        call.enqueued.await()

        val expected = Response.success(Unit)
        call.succeed(expected)

        assertSame(expected, result.await())
    }

    private class ControlledCall<T> : Call<T> {
        val enqueued = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        private var callback: Callback<T>? = null

        override fun enqueue(callback: Callback<T>) {
            this.callback = callback
            enqueued.complete(Unit)
        }

        override fun cancel() {
            cancelled.set(true)
        }

        fun fail(error: Throwable) {
            callback?.onFailure(this, error)
        }

        fun succeed(response: Response<T>) {
            callback?.onResponse(this, response)
        }

        override fun execute(): Response<T> = error("test call must be asynchronous")
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = cancelled.get()
        override fun clone(): Call<T> = ControlledCall()
        override fun request(): Request = Request.Builder().url("http://localhost/").build()
        override fun timeout(): Timeout = Timeout.NONE
    }
}
