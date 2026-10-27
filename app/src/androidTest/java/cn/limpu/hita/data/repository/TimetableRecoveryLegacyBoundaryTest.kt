package cn.limpu.hita.data.repository

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Identical tests run on the actual frozen old SourceApplier/Room/stores and current production. */
@RunWith(AndroidJUnit4::class)
class TimetableRecoveryLegacyBoundaryTest {
    @Test fun failedRoomWritePreservesCourseRowsAndPendingDecisions() = TimetableRecoveryTestFixture().use { f ->
        f.seedPendingState()
        val before = f.changes.currentState()
        val vetoes = f.changes.getVetoes(f.term.id)
        val bytes = f.changeFile.readBytes()
        f.failFinalCommit()
        f.statements.clear()
        val failure = runCatching { f.applier.adoptHeldBatch() }.exceptionOrNull()
        assertTrue("Real SQLite write failure must abort the transaction: $failure", failure is SQLiteException)
        assertTrue(failure!!.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
        assertEquals(listOf("Original"), f.imported().map { it.name })
        assertTrue("The transaction reached real course persistence", f.statements.any { it.contains("INSERT", true) && it.contains("`events`", true) })
        assertEquals("COMMIT failure must leave decisions retryable", before, f.changes.currentState())
        assertEquals(vetoes, f.changes.getVetoes(f.term.id))
        assertArrayEquals("No file cleanup before the actual DB commit", bytes, f.changeFile.readBytes())
    }

    @Test fun failedRealFileWriteKeepsThePublishedInMemoryState() = TimetableRecoveryTestFixture().use { f ->
        f.changes.recordHeldBatch(f.held())
        val before = f.changes.currentState()
        val bytes = f.changeFile.readBytes()
        val failure = f.denyDirectoryWrites(f.changeFile.parentFile!!) {
            runCatching { f.changes.recordHeldBatch(f.held(20, f.incoming("Later"))) }.exceptionOrNull()
        }
        assertTrue("Actual filesystem write must fail: $failure", failure is IOException)
        assertArrayEquals(bytes, f.changeFile.readBytes())
        assertEquals("Unpersisted successor must not become the current UI state", before, f.changes.currentState())
    }

    @Test fun successfulHeldAdoptionPreservesManualEventsAndPersonalDisplayMetadata() = TimetableRecoveryTestFixture().use { f ->
        f.seedPendingState()
        f.applier.adoptHeldBatch()
        assertEquals(listOf("Incoming"), f.imported().map { it.name })
        assertEquals("Local name", f.table().name)
        assertEquals("group", f.table().groupId)
        assertTrue(f.database.eventItemDao().getEventsOfTimetableSync("table").any { it.id == "manual" })
        assertNull(f.changes.currentState().heldBatch)
        assertTrue(f.changes.currentState().decisions.isEmpty())
        assertTrue(f.changes.getVetoes(f.term.id).isEmpty())
        assertEquals(listOf("Original"), f.snapshots.snapshots(f.owner, f.term.id).single().events.map { it.name })
    }

}
