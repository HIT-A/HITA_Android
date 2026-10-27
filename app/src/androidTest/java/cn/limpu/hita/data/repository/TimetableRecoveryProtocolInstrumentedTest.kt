package cn.limpu.hita.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Current-protocol tests; legacy baseline intentionally runs only the frozen boundary class. */
@RunWith(AndroidJUnit4::class)
class TimetableRecoveryProtocolInstrumentedTest {
    @Test fun atomicFileKeepsBaseWhenStaleBackupExists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "atomic-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "state.json")
        val backup = File(file.path + ".bak")
        try {
            file.writeText("new")
            backup.writeText("old")
            assertEquals("new", TimetableAtomicFile(file).readOrNull())
            assertFalse("a stale backup must not survive a successful base publication", backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun atomicFilePromotesLegacyTemporaryPayloadWhenBaseIsMissing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "atomic-legacy-tmp-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "state.json")
        val temporary = File(file.path + ".tmp")
        try {
            temporary.writeText("pending")
            assertEquals("pending", TimetableAtomicFile(file).readOrNull())
            assertEquals("pending", file.readText())
            assertFalse("legacy temporary payload must be promoted once", temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun committedHeldAdoptionReplaysAfterSnapshotPublicationFailure() =
        TimetableRecoveryTestFixture().use { f ->
            f.seedPendingState()
            val failure = f.denyDirectoryWrites(f.snapshotFile.parentFile!!) {
                runCatching { f.applier.adoptHeldBatch() }
            }

            // The Room commit is durable even though the post-commit snapshot publication failed.
            assertNull("file publication failure must not roll back committed courses", failure.exceptionOrNull())
            assertEquals(listOf("Incoming"), f.imported().map { it.name })
            assertEquals(1, f.database.timetableChangeOutboxDao().pending().size)

            val restored = f.reconstructFromDurableBytes()
            restored.use { r ->
                val recovery = TimetableChangeRecovery(
                    r.application,
                    r.database,
                    r.changes,
                    r.snapshots,
                    TimetableMutationLock(),
                )
                assertTrue("a reconstructed process must replay the durable outbox", recovery.recover())
                assertTrue(r.database.timetableChangeOutboxDao().pending().isEmpty())
                assertNull(r.changes.currentState().heldBatch)
                assertEquals(
                    listOf("Original"),
                    r.snapshots.snapshots(r.owner, r.term.id).single().events.map { it.name },
                )

                // A second replay is a no-op: the marker/ack protocol must not duplicate snapshots.
                assertTrue(recovery.recover())
                assertEquals(1, r.snapshots.snapshots(r.owner, r.term.id).size)
            }
        }

    @Test fun committedHeldAdoptionReplaysAfterChangePublicationFailure() =
        TimetableRecoveryTestFixture().use { f ->
            f.seedPendingState()
            val failure = f.denyDirectoryWrites(f.changeFile.parentFile!!) {
                runCatching { f.applier.adoptHeldBatch() }
            }

            assertNull("file publication failure must not roll back committed courses", failure.exceptionOrNull())
            assertEquals(listOf("Incoming"), f.imported().map { it.name })
            assertEquals(1, f.database.timetableChangeOutboxDao().pending().size)

            val restored = f.reconstructFromDurableBytes()
            restored.use { r ->
                val recovery = TimetableChangeRecovery(
                    r.application,
                    r.database,
                    r.changes,
                    r.snapshots,
                    TimetableMutationLock(),
                )
                assertTrue(recovery.recover())
                assertTrue(r.database.timetableChangeOutboxDao().pending().isEmpty())
                assertNull(r.changes.currentState().heldBatch)
                assertEquals(1, r.snapshots.snapshots(r.owner, r.term.id).size)
            }
        }
}
