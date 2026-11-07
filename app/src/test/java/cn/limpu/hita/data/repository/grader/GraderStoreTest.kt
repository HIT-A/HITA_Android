package cn.limpu.hita.data.repository.grader

import cn.limpu.hita.data.source.web.grader.GraderAccount
import cn.limpu.hita.data.source.web.grader.GraderAssignment
import cn.limpu.hita.data.source.web.grader.GraderCourse
import cn.limpu.hita.data.source.web.grader.GraderParser
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GraderStoreTest {
    private val now = Instant.parse("2026-11-07T16:00:00Z").toEpochMilli()
    private fun task(id: String = "11", due: Long = now) = GraderAssignment(id, "实验报告", "合成课程", due, true)

    @Test fun deadlineBoundaryAndPublicationControlVisibility() {
        val course = GraderCourse("3", "合成课程")
        val assignments = GraderParser.assignments("""[
            {"id":11,"title":"恰好截止","course":3,"published":true,"due_time":"2026-11-08T00:00:00+08:00"},
            {"id":12,"title":"已经过期","course":3,"published":true,"due_time":"2026-11-07T15:59:59Z"},
            {"id":13,"title":"未发布","course":3,"published":false,"due_time":"2026-11-08T16:00:00Z"},
            {"id":14,"title":"没有截止时间","course":3,"published":true,"due_time":null},
            {"id":15,"title":"时间无效","course":3,"published":true,"due_time":"not-a-date"},
            {"id":16,"title":"未来作业","course":3,"published":true,"due_time":"2026-11-08T16:00:00Z"}
        ]""", course)
        assertEquals(listOf("11", "16"), GraderParser.visibleTasks(assignments, emptySet(), now).map { it.id })
        assertEquals(listOf("16"), GraderParser.visibleTasks(assignments, emptySet(), now + 1).map { it.id })
    }

    @Test fun completionSurvivesRefreshDeadlineExtensionRestartAndAccountSwitch() {
        var disk: String? = null
        val store = GraderStore({ disk }, { disk = it; true })
        val firstAccount = GraderAccount("1", "合成账号甲")
        val secondAccount = GraderAccount("2", "合成账号乙")
        store.replace(firstAccount, "sessionid=synthetic-a", listOf(task(), task()))
        store.setCompleted("1", "11", true)
        store.replace(firstAccount, "sessionid=synthetic-a", listOf(task(due = now + 60_000)))
        assertTrue(store.load().visibleTasks(now).single().completed)
        store.disconnect()
        assertTrue(store.load().visibleTasks(now).isEmpty())
        store.replace(secondAccount, "sessionid=synthetic-b", listOf(task()))
        assertFalse(store.load().visibleTasks(now).single().completed)
        store.replace(firstAccount, "sessionid=synthetic-a2", listOf(task()))
        val restored = GraderStore({ disk }, { error("read only") }).load()
        assertEquals("1", restored.account?.id)
        assertTrue(restored.visibleTasks(now).single().completed)
        store.setCompleted("1", "11", false)
        assertFalse(GraderStore({ disk }, { true }).load().visibleTasks(now).single().completed)
    }

    @Test fun failedCommitKeepsDiskSnapshotAndDoesNotSwitchAccount() {
        var disk: String? = null
        var writable = true
        val store = GraderStore({ disk }, { if (writable) { disk = it; true } else false })
        store.replace(GraderAccount("1", "合成账号甲"), "sessionid=synthetic-a", listOf(task()))
        val saved = disk
        writable = false
        assertThrows(GraderStorageException::class.java) { store.setCompleted("1", "11", true) }
        assertThrows(GraderStorageException::class.java) {
            store.replace(GraderAccount("2", "合成账号乙"), "sessionid=synthetic-b", listOf(task()))
        }
        assertEquals(saved, disk)
        assertEquals("1", store.load().account?.id)
        assertFalse(store.load().visibleTasks(now).single().completed)
    }
}
