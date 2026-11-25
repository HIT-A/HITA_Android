package cn.limpu.hita.data.repository.grader

import cn.limpu.hita.data.source.web.grader.GraderAccount
import cn.limpu.hita.data.source.web.grader.GraderAssignment
import cn.limpu.hita.data.source.web.grader.GraderCourse
import cn.limpu.hita.data.source.web.grader.GraderInvalidResponseException
import cn.limpu.hita.data.source.web.grader.GraderParser
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GraderStoreTest {
    private val now = Instant.parse("2026-11-07T16:00:00Z").toEpochMilli()
    private fun task(id: String = "11", due: Long = now) = GraderAssignment(id, "实验报告", "合成课程", due, true)

    @Test fun expiredUnsubmittedAssignmentsAreKeptButUnpublishedAndUndatedAreExcluded() {
        val course = GraderCourse("3", "合成课程")
        val assignments = GraderParser.assignments("""[
            {"id":11,"title":"恰好截止","course":3,"published":true,"due_time":"2026-11-08T00:00:00+08:00"},
            {"id":12,"title":"已经过期","course":3,"published":true,"due_time":"2026-11-07T15:59:59Z"},
            {"id":13,"title":"未发布","course":3,"published":false,"due_time":"2026-11-08T16:00:00Z"},
            {"id":14,"title":"没有截止时间","course":3,"published":true,"due_time":null},
            {"id":15,"title":"时间无效","course":3,"published":true,"due_time":"not-a-date"},
            {"id":16,"title":"未来作业","course":3,"published":true,"due_time":"2026-11-08T16:00:00Z"}
        ]""", course)
        val store = GraderStore({ null }, { true })
        store.replace(GraderAccount("1", "合成账号"), "sessionid=synthetic-a", assignments)
        val byId = store.load().todoTasks().associateBy { it.id }
        assertEquals(setOf("11", "12", "16"), byId.keys)
        assertEquals(now - 1000L, byId["12"]?.dueAt)
        assertEquals(now, byId["11"]?.dueAt)
    }

    @Test fun completionSurvivesRefreshDeadlineExtensionRestartAndAccountSwitch() {
        var disk: String? = null
        val store = GraderStore({ disk }, { disk = it; true })
        val firstAccount = GraderAccount("1", "合成账号甲")
        val secondAccount = GraderAccount("2", "合成账号乙")
        store.replace(firstAccount, "sessionid=synthetic-a", listOf(task(), task("12")))
        store.setCompleted("1", "11", true)
        assertTrue(store.load().todoTasks().single { it.id == "11" }.completed)
        store.replace(firstAccount, "sessionid=synthetic-a", listOf(task(due = now + 60_000), task("12")))
        assertTrue(store.load().todoTasks().single { it.id == "11" }.completed)
        // Active snapshots omit the new marker; legacy version 1 payloads still load as active.
        assertFalse(disk.orEmpty().contains("\"disconnected\""))
        assertEquals(firstAccount, GraderStore({ disk }, { error("read only") }).load().activeAccount)
        store.disconnect()
        val restartedWhileDisconnected = GraderStore({ disk }, { error("dormant session must not be rewritten") })
        val disconnected = restartedWhileDisconnected.load()
        assertTrue(disconnected.disconnected)
        assertEquals(firstAccount, disconnected.account)
        assertEquals("sessionid=synthetic-a", disconnected.cookie)
        assertNull(disconnected.activeAccount)
        assertTrue(disconnected.todoTasks().isEmpty())
        assertTrue(disconnected.summaryItems().isEmpty())
        assertNull(restartedWhileDisconnected.setCompleted("1", "11", false))
        assertSame(disconnected, restartedWhileDisconnected.clearForReplacement())
        store.replace(secondAccount, "sessionid=synthetic-b", listOf(task()))
        assertFalse(store.load().todoTasks().single().completed)
        assertNull(store.setCompleted("1", "11", false))
        store.replace(firstAccount, "sessionid=synthetic-a2", listOf(task(), task("12")))
        val restored = GraderStore({ disk }, { error("read only") }).load()
        assertEquals("1", restored.account?.id)
        assertFalse(restored.disconnected)
        assertTrue(restored.todoTasks().single { it.id == "11" }.completed)
        store.setCompleted("1", "11", false)
        assertFalse(GraderStore({ disk }, { true }).load().todoTasks().single { it.id == "11" }.completed)
    }

    @Test fun submittedAssignmentsAreHiddenEvenBeforeDeadline() {
        var disk: String? = null
        val store = GraderStore({ disk }, { disk = it; true })
        val account = GraderAccount("1", "合成账号甲")
        val future = now + 7 * 24 * 3_600_000L
        val assignments = listOf(task("11", future), task("12", future), task("13", future))
        store.replace(account, "sessionid=synthetic-a", assignments)
        store.setCompleted("1", "11", true)
        store.replace(account, "sessionid=synthetic-a", assignments, setOf("11", "13"))
        assertEquals(listOf("12"), store.load().todoTasks().map { it.id })
        assertNull(store.setCompleted("1", "11", false))
        // 远端提交集合持久化后仍然生效。
        val restored = GraderStore({ disk }, { true }).load()
        assertEquals(listOf("12"), restored.todoTasks().map { it.id })
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
        assertFalse(store.load().todoTasks().single().completed)
    }
    @Test fun summaryRetainsPublishedExpiredUndatedAndCompletionForCurrentAccountOnly() {
        val first = GraderAccount("1", "first"); val second = GraderAccount("2", "second")
        val store = GraderStore({ null }, { true })
        store.replace(first, "sessionid=synthetic-first", listOf(
            task("11", now - 1), task("12").copy(dueAt = null), task("13"), task("14").copy(published = false), task("15")), setOf("13"))
        store.setCompleted("1", "15", true)
        val summary = store.load().summaryItems()
        assertEquals(setOf("11", "12", "13", "15"), summary.map { it.id }.toSet())
        assertTrue(summary.all { it.accountId == "1" })
        assertEquals(setOf("13", "15"), summary.filter { it.completed }.map { it.id }.toSet())
        assertEquals(now - 1, summary.single { it.id == "11" }.dueAt)
        assertNull(summary.single { it.id == "12" }.dueAt)
        store.disconnect()
        assertTrue(store.load().summaryItems().isEmpty())
        store.replace(second, "sessionid=synthetic-second", listOf(task("11")))
        assertEquals("2", store.load().summaryItems().single().accountId)
        assertFalse(store.load().summaryItems().single().completed)
    }
    @Test fun gatewaySessionKeepsItsEntryAcrossRestartAndLegacySnapshotStaysDirect() {
        var disk: String? = null
        val store = GraderStore({ disk }, { disk = it; true })
        val gatewayCookie = "SESSION=abc; sessionid=s1; sdp_user_token=xyz"
        store.replace(
            GraderAccount("1", "合成账号甲"), gatewayCookie, listOf(task()),
            entry = GraderSessionPolicy.Entry.GATEWAY,
        )
        val restored = GraderStore({ disk }, { true }).load()
        assertEquals(GraderSessionPolicy.Entry.GATEWAY, restored.entry)
        assertEquals(gatewayCookie, restored.cookie)

        // 旧版本快照没有 entry 字段：那时只可能直连，按直连读。
        val legacy = """{"version":1,"account":{"id":"1","name":"合成账号甲","cookie":"sessionid=synthetic-legacy"},""" +
            """"cache":{"1":[]},"completed":{}}"""
        val legacySnapshot = GraderStore({ legacy }, { true }).load()
        assertEquals(GraderSessionPolicy.Entry.DIRECT, legacySnapshot.entry)
        assertEquals("sessionid=synthetic-legacy", legacySnapshot.cookie)
    }

    @Test fun gatewaySessionWithoutTheAuthCookieIsRejected() {
        val store = GraderStore({ null }, { true })
        assertThrows(GraderStorageException::class.java) {
            store.replace(
                GraderAccount("1", "合成账号甲"), "SESSION=abc", listOf(task()),
                entry = GraderSessionPolicy.Entry.GATEWAY,
            )
        }
    }

}
