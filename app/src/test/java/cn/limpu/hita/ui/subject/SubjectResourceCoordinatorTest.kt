package cn.limpu.hita.ui.subject

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.resource.*
import org.junit.Assert.*
import org.junit.Test

class SubjectResourceCoordinatorTest {
    private class Pending<T>(val callback: (Result<T>) -> Unit) {
        var cancelled = false
        val handle = SubjectRequest { cancelled = true }
        fun success(value: T) = callback(Result.success(value))
        fun failure(message: String = "offline") = callback(Result.failure(IllegalStateException(message)))
    }
    private class Gateway : SubjectResourceGateway {
        val agents = mutableListOf<Pending<List<CourseResourceItem>>>()
        val directs = mutableListOf<Pending<List<CourseResourceItem>>>()
        val searches = mutableListOf<Pending<List<ExternalCourseItem>>>()
        val readmes = mutableListOf<Pair<String, Pending<CourseReadmeData>>>()
        val directories = mutableListOf<Pair<String, Pending<List<ExternalResourceEntry>>>>()
        override fun agent(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit) =
            Pending(result).also { agents.add(it) }.handle
        override fun direct(key: SubjectResourceKey, result: (Result<List<CourseResourceItem>>) -> Unit) =
            Pending(result).also { directs.add(it) }.handle
        override fun search(query: String, result: (Result<List<ExternalCourseItem>>) -> Unit) =
            Pending(result).also { searches.add(it) }.handle
        override fun readme(repo: String, campus: String, result: (Result<CourseReadmeData>) -> Unit) =
            Pending(result).also { readmes.add(repo to it) }.handle
        override fun directory(path: String, source: ResourceSource, result: (Result<List<ExternalResourceEntry>>) -> Unit) =
            Pending(result).also { directories.add(path to it) }.handle
    }
    private val key = SubjectResourceKey("A", null, "程序设计", EASToken.Campus.SHENZHEN, "sz")
    private val text = SubjectResourceText("missing", "choose", "failed", "empty", "open")
    private val first = CourseResourceItem(repoName = "one", courseName = "程序设计", courseCode = "COMP1001")
    private val second = CourseResourceItem(repoName = "two", courseName = "程序设计", courseCode = "COMP2001")
    private val external = ExternalCourseItem(courseName = "程序设计", path = "root")

    @Test fun cardsLoadIndependentlyAndSuccessfulReadmeIsReused() {
        val source = Gateway()
        val model = SubjectResourceCoordinator(source, text) { _, _ -> }
        model.load(key)
        source.agents.single().success(listOf(first, second))
        model.toggleCandidate(first)
        model.toggleCandidate(second)
        source.readmes[1].second.success(CourseReadmeData("https://two/README.md", "second"))
        assertTrue(model.state.value.candidateReadmes.getValue(itemKey(first)).loading)
        assertEquals("second", model.state.value.candidateReadmes.getValue(itemKey(second)).markdown)
        model.toggleCandidate(second)
        model.toggleCandidate(second)
        assertEquals(2, source.readmes.size)
        source.readmes[0].second.success(CourseReadmeData("https://one/README.md", "first"))
        assertEquals("first", model.state.value.candidateReadmes.getValue(itemKey(first)).markdown)
    }

    @Test fun collapsedPendingCardCancelsAndReopenStartsFreshRequest() {
        val source = Gateway()
        val model = SubjectResourceCoordinator(source, text) { _, _ -> }
        model.load(key)
        source.agents.single().success(listOf(first))
        model.toggleCandidate(first)
        model.toggleCandidate(first)
        assertTrue(source.readmes.single().second.cancelled)
        model.toggleCandidate(first)
        source.readmes[1].second.success(CourseReadmeData("new", "new"))
        source.readmes[0].second.success(CourseReadmeData("old", "old"))
        assertEquals("new", model.state.value.candidateReadmes.getValue(itemKey(first)).source)
    }

    @Test fun blankSuccessfulReadmeIsCachedAndFailureIsRetryable() {
        val source = Gateway()
        val model = SubjectResourceCoordinator(source, text) { _, _ -> }
        model.load(key)
        source.agents.single().success(listOf(first, second))
        model.toggleCandidate(first)
        source.readmes.last().second.success(CourseReadmeData())
        model.toggleCandidate(first)
        model.toggleCandidate(first)
        assertEquals(1, source.readmes.size)
        model.toggleCandidate(second)
        source.readmes.last().second.failure()
        model.toggleCandidate(second)
        model.toggleCandidate(second)
        assertEquals(3, source.readmes.size)
    }

    @Test fun rootChildBackRejectsStaleDirectoryAndKeepsBreadcrumb() {
        val source = Gateway()
        val model = SubjectResourceCoordinator(source, text) { _, _ -> }
        model.load(key)
        source.searches.single().success(listOf(external))
        model.toggleExternal(external)
        val child = ExternalResourceEntry(name = "child", isDir = true, path = "root/child")
        source.directories.single().second.success(listOf(child))
        assertNull(model.selectEntry(itemKey(external), child))
        model.backExternal(external)
        source.directories[2].second.success(listOf(child))
        source.directories[1].second.success(listOf(ExternalResourceEntry(name = "stale")))
        val current = model.state.value.directories.getValue(itemKey(external))
        assertEquals("程序设计", current.breadcrumb)
        assertEquals(listOf(child), current.entries)
        assertTrue(source.directories[1].second.cancelled)
        model.backExternal(external)
        assertEquals(3, source.directories.size)
    }

    @Test fun collapsedPendingDirectoryRestartsOnReopenAndRejectsOldResult() {
        val source = Gateway()
        val model = SubjectResourceCoordinator(source, text) { _, _ -> }
        model.load(key)
        source.searches.single().success(listOf(external))
        model.toggleExternal(external)
        model.toggleExternal(external)
        model.toggleExternal(external)
        assertEquals(listOf("root", "root"), source.directories.map { it.first })
        source.directories[1].second.success(emptyList())
        source.directories[0].second.success(listOf(ExternalResourceEntry(name = "stale")))
        assertEquals("empty", model.state.value.directories.getValue(itemKey(external)).message)
    }

    @Test fun subjectChangeAndCloseCancelEverySourceAndRejectLateResults() {
        val source = Gateway()
        val backfills = mutableListOf<String>()
        val model = SubjectResourceCoordinator(source, text) { owner, _ -> backfills.add(owner.subjectId) }
        model.load(key)
        source.agents.single().success(listOf(first))
        source.searches.single().success(listOf(external))
        model.toggleCandidate(first)
        model.toggleExternal(external)
        backfills.clear()
        model.load(key.copy(subjectId = "B"))
        source.readmes.single().second.success(CourseReadmeData("old", "old"))
        source.directories.single().second.success(listOf(ExternalResourceEntry(name = "old")))
        source.searches[0].success(listOf(external))
        assertTrue(model.state.value.candidateReadmes.isEmpty())
        assertTrue(model.state.value.externalCourses.isEmpty())
        model.close()
        source.agents.last().success(listOf(first))
        source.searches.last().success(listOf(external))
        assertTrue(backfills.isEmpty())
        assertNull(model.state.value.key)
        assertTrue(source.readmes.single().second.cancelled)
        assertTrue(source.directories.single().second.cancelled)
        assertTrue(source.searches.last().cancelled)
    }

    @Test fun backfillRequiresUniqueExactNameOrAliasAndBlankExistingCode() {
        val source = Gateway()
        val backfills = mutableListOf<String>()
        val model = SubjectResourceCoordinator(source, text) { _, code -> backfills.add(code) }
        model.load(key)
        source.agents.last().success(listOf(first, second))
        assertTrue(backfills.isEmpty())
        model.load(key.copy(subjectId = "B"))
        source.agents.last().success(listOf(first.copy(courseName = "other", aliases = listOf("程序设计"))))
        assertEquals(listOf("COMP1001"), backfills)
        model.load(key.copy(subjectId = "C", code = "existing"))
        source.agents.last().success(listOf(first))
        assertEquals(listOf("COMP1001"), backfills)
    }

    @Test fun inputMutationDoesNotChangeCapturedCandidateAndFileMustBelongToCurrentDirectory() {
        val source = Gateway()
        val model = SubjectResourceCoordinator(source, text) { _, _ -> }
        model.load(key)
        val item = first.copy()
        source.agents.last().success(listOf(item))
        item.repoName = "mutated"
        model.toggleCandidate(first)
        assertEquals("one", source.readmes.single().first)
        source.searches.last().success(listOf(external))
        model.toggleExternal(external)
        val file = ExternalResourceEntry(name = "a.pdf", path = "root/a.pdf")
        source.directories.last().second.success(listOf(file))
        assertEquals(file, model.selectEntry(itemKey(external), file))
        model.toggleExternal(external)
        assertNull(model.selectEntry(itemKey(external), file))
    }
}
