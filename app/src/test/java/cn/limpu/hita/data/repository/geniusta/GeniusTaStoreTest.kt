package cn.limpu.hita.data.repository.geniusta

import cn.limpu.hita.data.source.web.geniusta.GeniusTaAssignment
import cn.limpu.hita.data.source.web.geniusta.GeniusTaConnection
import cn.limpu.hita.data.source.web.geniusta.GeniusTaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniusTaStoreTest {
    private val assignment = GeniusTaAssignment("test-assignment", "Synthetic Assignment", "Synthetic Course", 1L)
    private val first = connection("test-account-a")
    private val second = connection("test-account-b")

    @Test fun completionAndRestorationSurviveRestartAndStayAccountScoped() {
        val disk = MemoryDisk()
        val store = disk.open()
        store.replace(first, listOf(assignment))
        assertTrue(store.setCompleted(first.accountId, assignment.id, true)!!.tasks.single().completed)
        val refreshed = assignment.copy(dueAt = 100L)
        store.replace(first, listOf(refreshed))
        store.disconnect()
        assertNull(disk.open().load().accountId)
        assertNull(disk.open().load().session)
        assertTrue(disk.open().load().tasks.isEmpty())

        store.replace(second, listOf(assignment))
        assertNull(store.setCompleted(first.accountId, assignment.id, true))
        assertFalse(disk.open().load().tasks.single().completed)

        store.replace(first, listOf(refreshed))
        val restarted = disk.open()
        val task = restarted.load().tasks.single()
        assertTrue(task.completed)
        assertEquals(100L, task.dueAt)
        assertFalse(restarted.setCompleted(first.accountId, assignment.id, false)!!.tasks.single().completed)
        assertFalse(disk.open().load().tasks.single().completed)
    }

    @Test fun refusingCommitDoesNotReplaceSnapshotOrReportCompletion() {
        val disk = MemoryDisk()
        val store = disk.open()
        store.replace(first, listOf(assignment))
        disk.fail = true
        assertThrows(GeniusTaStorageException::class.java) { store.replace(second, emptyList()) }
        assertThrows(GeniusTaStorageException::class.java) { store.setCompleted(first.accountId, assignment.id, true) }
        assertEquals(first.accountId, store.load().accountId)
        assertFalse(store.load().tasks.single().completed)
        assertEquals(first.accountId, disk.open().load().accountId)
    }

    @Test fun removingExpiredCredentialKeepsItsAccountCacheAndDoesNotPersistTheCookie() {
        val disk = MemoryDisk()
        val store = disk.open()
        store.replace(first, listOf(assignment))
        store.expireSession()
        val restored = disk.open().load()
        assertEquals(first.accountId, restored.accountId)
        assertNull(restored.session)
        assertEquals(assignment.id, restored.tasks.single().id)
        assertFalse(disk.value.orEmpty().contains(first.session.cookieValue))
    }

    private fun connection(id: String) = GeniusTaConnection(id, GeniusTaSession("test-session", "synthetic-cookie", Long.MAX_VALUE))

    private class MemoryDisk {
        var value: String? = null
        var fail = false

        fun open() = GeniusTaStore({ value }) { snapshot ->
            if (fail) false else { value = snapshot; true }
        }
    }
}
