package cn.limpu.hita.agent.core

class AgentOrchestrator<I, O>(
    private val engine: AgentEngine<I, O>,
) {
    fun run(
        input: I,
        onTrace: (AgentTraceEvent) -> Unit,
        onResult: (AgentToolResult<O>) -> Unit,
    ): AgentCancellation = engine.run(input, onTrace, onResult)
}
