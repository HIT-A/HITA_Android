package cn.limpu.hita.agent.core

/** Direct engines may emit initial validation synchronously; AgentSession serializes all delivery. */
interface AgentEngine<I, O> {
    fun run(
        input: I,
        onTrace: (AgentTraceEvent) -> Unit,
        onResult: (AgentToolResult<O>) -> Unit,
    ): AgentCancellation
}
