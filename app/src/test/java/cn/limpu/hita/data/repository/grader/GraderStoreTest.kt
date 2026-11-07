package cn.limpu.hita.data.repository.grader

import cn.limpu.hita.data.source.web.grader.GraderAccount
import cn.limpu.hita.data.source.web.grader.GraderAssignment
import cn.limpu.hita.data.source.web.grader.GraderCourse
import cn.limpu.hita.data.source.web.grader.GraderInvalidResponseException
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
        store.replace(firstAccount, "sessionid=synthetic-a", listOf(task(), task("12")))
        store.setCompleted("1", "11", true)
        // 已完成的作业不再出现在待办里，刷新或截止延期都不改变这一点。
        assertEquals(listOf("12"), store.load().visibleTasks(now).map { it.id })
        store.replace(firstAccount, "sessionid=synthetic-a", listOf(task(due = now + 60_000), task("12")))
        assertEquals(listOf("12"), store.load().visibleTasks(now).map { it.id })
        store.disconnect()
        assertTrue(store.load().visibleTasks(now).isEmpty())
        store.replace(secondAccount, "sessionid=synthetic-b", listOf(task()))
        assertEquals(listOf("11"), store.load().visibleTasks(now).map { it.id })
        store.replace(firstAccount, "sessionid=synthetic-a2", listOf(task(), task("12")))
        val restored = GraderStore({ disk }, { error("read only") }).load()
        assertEquals("1", restored.account?.id)
        assertEquals(listOf("12"), restored.visibleTasks(now).map { it.id })
        store.setCompleted("1", "11", false)
        assertEquals(listOf("11", "12"), GraderStore({ disk }, { true }).load().visibleTasks(now).map { it.id })
    }

    @Test fun submittedAssignmentsAreHiddenEvenBeforeDeadline() {
        var disk: String? = null
        val store = GraderStore({ disk }, { disk = it; true })
        val account = GraderAccount("1", "合成账号甲")
        val future = now + 7 * 24 * 3_600_000L
        val assignments = listOf(task("11", future), task("12", future), task("13", future))
        // 远端提交过 11 和 13，12 未提交。
        store.replace(account, "sessionid=synthetic-a", assignments, setOf("11", "13"))
        assertEquals(listOf("12"), store.load().visibleTasks(now).map { it.id })
        // 远端提交集合持久化后仍然生效。
        val restored = GraderStore({ disk }, { true }).load()
        assertEquals(listOf("12"), restored.visibleTasks(now).map { it.id })
    }

    @Test fun submittedIdsParseFromSubmissionsPayload() {
        val payload = """[
            {"id":216320,"submit_time":"2026-11-07T13:25:03Z","status":0,"assignment":4498},
            {"id":213081,"submit_time":"2026-11-01T12:01:11Z","status":0,"assignment":4668}
        ]"""
        assertEquals(setOf("4498", "4668"), GraderParser.submittedAssignmentIds(payload))
        assertEquals(emptySet<String>(), GraderParser.submittedAssignmentIds("[]"))
        assertThrows(GraderInvalidResponseException::class.java) {
            GraderParser.submittedAssignmentIds("""[{"assignment":null}]""")
        }
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
