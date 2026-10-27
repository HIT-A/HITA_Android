package cn.limpu.hita.agent.core

import java.util.PriorityQueue

internal class ManualAgentScheduler : AgentExecutionScheduler {
    private data class Task(val at: Long, val order: Int, val action: () -> Unit, var cancelled: Boolean = false)
    private val tasks = PriorityQueue(compareBy<Task> { it.at }.thenBy { it.order })
    private var sequence = 0
    private var now = 0L
    val pending: Int get() = tasks.count { !it.cancelled }
    override fun schedule(delayMs: Long, action: () -> Unit): AgentCancellation {
        val task = Task(now + delayMs, sequence++, action)
        tasks.add(task)
        return AgentCancellation { task.cancelled = true }
    }
    fun runCurrent() = advanceBy(0)
    fun advanceBy(delta: Long) {
        val end = now + delta
        var runs = 0
        while (tasks.isNotEmpty() && tasks.peek().at <= end) {
            check(runs++ < 10_000) { "Scheduler did not settle" }
            val task = tasks.remove()
            now = task.at
            if (!task.cancelled) task.action()
        }
        now = end
    }
}
