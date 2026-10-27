package cn.limpu.hita.agent.core

import android.os.Handler
import android.os.Looper

/** Implementations enqueue work (including delay 0), serially, and never invoke it inline. */
fun interface AgentExecutionScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): AgentCancellation
}

class MainAgentExecutionScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : AgentExecutionScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): AgentCancellation {
        val task = Runnable(action)
        check(handler.postDelayed(task, delayMs)) { "Agent scheduler is unavailable" }
        return AgentCancellation { handler.removeCallbacks(task) }
    }
}
