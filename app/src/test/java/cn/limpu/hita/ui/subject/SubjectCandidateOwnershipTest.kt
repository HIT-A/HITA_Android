package cn.limpu.hita.ui.subject

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.resource.CourseResourceItem
import org.junit.Assert.*
import org.junit.Test

class SubjectCandidateOwnershipTest {
    private class Pending(val key: SubjectResourceKey, val callback: (Result<List<CourseResourceItem>>) -> Unit) {
        var cancelled = false
        fun succeed(name: String) = callback(Result.success(listOf(CourseResourceItem(courseName = name))))
        fun fail() = callback(Result.failure(IllegalStateException("offline")))
    }
    private class Gateway : SubjectCandidateGateway {
        val agents = mutableListOf<Pending>()
        val fallbacks = mutableListOf<Pending>()
        override fun agent(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit) =
            Pending(key, result).let { agents.add(it); SubjectRequest { it.cancelled = true } }
        override fun direct(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit) =
            Pending(key, result).let { fallbacks.add(it); SubjectRequest { it.cancelled = true } }
    }
    private val a = SubjectResourceKey("A", null, "程序设计", EASToken.Campus.SHENZHEN, "sz")

    @Test fun queuedOldAgentCannotReplaceNewSubject() {
        val gateway = Gateway()
        val delivered = mutableListOf<String>()
        val resolver = SubjectCandidateResolver(gateway, {}, { key, _ -> delivered.add(key.subjectId) })
        resolver.load(a)
        resolver.load(a.copy(subjectId = "B"))
        gateway.agents[1].succeed("B")
        gateway.agents[0].succeed("A")
        assertTrue(gateway.agents[0].cancelled)
        assertEquals(listOf("B"), delivered)
    }

    @Test fun delayedFallbackCannotReplaceNewSubject() {
        val gateway = Gateway()
        val delivered = mutableListOf<String>()
        val resolver = SubjectCandidateResolver(gateway, {}, { key, _ -> delivered.add(key.subjectId) })
        resolver.load(a)
        gateway.agents.single().fail()
        resolver.load(a.copy(subjectId = "B"))
        gateway.agents.last().succeed("B")
        gateway.fallbacks.single().succeed("A")
        assertEquals(listOf("B"), delivered)
        assertTrue(gateway.fallbacks.single().cancelled)
    }

    @Test fun cancellationDoesNotStartFallbackAndClearRejectsDelivery() {
        val gateway = Gateway()
        val delivered = mutableListOf<String>()
        val resolver = SubjectCandidateResolver(gateway, {}, { key, _ -> delivered.add(key.subjectId) })
        resolver.load(a)
        resolver.clear()
        gateway.agents.single().fail()
        assertTrue(gateway.fallbacks.isEmpty())
        assertTrue(delivered.isEmpty())
    }

    @Test fun sameSubjectReplayIsCheapButCampusNameAndCodeChangesReload() {
        val gateway = Gateway()
        val resolver = SubjectCandidateResolver(gateway, {}, { _, _ -> })
        resolver.load(a)
        resolver.load(a)
        assertEquals(1, gateway.agents.size)
        resolver.load(a.copy(campus = EASToken.Campus.WEIHAI))
        resolver.load(a.copy(name = "数据库"))
        resolver.load(a.copy(code = "CS100"))
        assertEquals(4, gateway.agents.size)
    }

    @Test fun duplicateAgentFailureFallsBackOnlyOnce() {
        val gateway = Gateway()
        val resolver = SubjectCandidateResolver(gateway, {}, { _, _ -> })
        resolver.load(a)
        gateway.agents.single().fail()
        gateway.agents.single().fail()
        assertEquals(1, gateway.fallbacks.size)
    }

    @Test fun explicitCancellationResultDoesNotBecomeFallback() {
        val gateway = Gateway()
        val resolver = SubjectCandidateResolver(gateway, {}, { _, _ -> })
        resolver.load(a)
        gateway.agents.single().callback(Result.failure(java.util.concurrent.CancellationException()))
        assertTrue(gateway.fallbacks.isEmpty())
    }
}
