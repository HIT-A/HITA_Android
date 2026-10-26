package cn.limpu.hita.ui.subject

import cn.limpu.hita.data.model.resource.CourseResourceItem
import java.util.concurrent.CancellationException

/** Candidate ownership extracted from SubjectActivity; the gateway owns platform observation. */
internal class SubjectCandidateResolver(
    private val gateway: SubjectCandidateGateway,
    private val onLoading: (SubjectResourceKey) -> Unit,
    private val onCandidates: (SubjectResourceKey, List<CourseResourceItem>) -> Unit,
) {
    private var resolveKey: SubjectResourceKey? = null
    private var generation = 0L
    private var agent: SubjectRequestSlot? = null
    private var direct: SubjectRequestSlot? = null

    fun load(key: SubjectResourceKey) {
        if (resolveKey == key) return
        clear()
        resolveKey = key
        val owner = generation
        onLoading(key)
        val request = SubjectRequestSlot().also { agent = it }
        request.attach(gateway.agent(key) { result ->
            if (owner != generation || !request.finish()) return@agent
            if (result.exceptionOrNull() is CancellationException) return@agent
            if (result.isSuccess) {
                onCandidates(key, result.getOrDefault(emptyList()))
            } else {
                val fallbackRequest = SubjectRequestSlot().also { direct = it }
                fallbackRequest.attach(gateway.direct(key) { fallback ->
                    if (owner == generation && fallbackRequest.finish()) {
                        onCandidates(key, fallback.getOrDefault(emptyList()))
                    }
                })
            }
        })
    }

    fun clear() {
        generation++
        resolveKey = null
        agent?.cancel()
        direct?.cancel()
        agent = null
        direct = null
    }
}

/** Registration may synchronously return a cached result before it returns its cancel handle. */
internal class SubjectRequestSlot : SubjectRequest {
    private var active = true
    private var request: SubjectRequest? = null
    fun attach(request: SubjectRequest) {
        if (active) this.request = request else request.cancel()
    }
    fun finish(): Boolean {
        if (!active) return false
        cancel()
        return true
    }
    override fun cancel() {
        active = false
        request?.cancel()
        request = null
    }
}
