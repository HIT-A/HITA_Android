package cn.limpu.hita.ui.main

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WallpaperFilesTest {
    private lateinit var directory: File
    private lateinit var previous: File
    private lateinit var preference: Preference
    private lateinit var files: WallpaperFiles
    private val worker = Executors.newSingleThreadExecutor()

    @Before fun setUp() {
        directory = Files.createTempDirectory("main-wallpaper-test").toFile()
        previous = File(directory, "timetable_wallpaper_previous").apply { writeText("previous") }
        File(directory, "avatar.jpg").writeText("avatar")
        preference = Preference("local://${previous.absolutePath}")
        files = WallpaperFiles(directory, preference)
    }

    @After fun tearDown() {
        try {
            worker.shutdownNow()
            assertTrue("Wallpaper worker did not stop", worker.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun successfulReplacementClosesInputAndRetiresOnlyWallpaperFiles() {
        val source = TrackedInput("new wallpaper")
        assertEquals(WallpaperWriteResult.SAVED, files.replace(files.beginChange(), { source }))
        assertTrue(source.closed)
        assertEquals("new wallpaper", selectedFile().readText())
        assertFalse(previous.exists())
        assertEquals(setOf("avatar.jpg", selectedFile().name), names())
        assertEquals("avatar", File(directory, "avatar.jpg").readText())
    }

    @Test fun copyFailureKeepsPreviousSelectionAndRemovesPartialOutput() {
        val failure = IOException("input failed after first chunk")
        val source = object : InputStream() {
            var closed = false
            private var emitted = false
            override fun read(): Int = throw failure
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (emitted) throw failure
                emitted = true
                val bytes = "partial".toByteArray()
                bytes.copyInto(buffer, offset)
                return bytes.size
            }
            override fun close() { closed = true }
        }
        assertSame(failure, assertThrows(IOException::class.java) { files.replace(files.beginChange(), { source }) })
        assertTrue(source.closed)
        assertPreviousSelection()
    }

    @Test fun missingOrThrowingInputLeavesPreviousSelectionUntouched() {
        assertThrows(IllegalStateException::class.java) { files.replace(files.beginChange(), { null }) }
        assertPreviousSelection()
        assertThrows(SecurityException::class.java) {
            files.replace(files.beginChange(), { throw SecurityException("permission revoked") })
        }
        assertPreviousSelection()
    }

    @Test fun publicationFailureKeepsPreviousSelectionAndCleansCandidate() {
        preference.failWrites = true
        val source = TrackedInput("candidate")
        assertThrows(IOException::class.java) { files.replace(files.beginChange(), { source }) }
        assertTrue(source.closed)
        assertPreviousSelection()
    }

    @Test fun newerSelectionWinsWhenOlderCopyFinishesLast() {
        val slow = BlockedInput("old request")
        val first = files.beginChange()
        val oldResult = worker.submit<WallpaperWriteResult> { files.replace(first, { slow }) }
        try {
            assertTrue("First copy never reached its input", slow.entered.await(5, TimeUnit.SECONDS))
            assertEquals(WallpaperWriteResult.SAVED, files.replace(files.beginChange(), { TrackedInput("new request") }))
            val latestPath = preference.value
            slow.release.countDown()
            assertEquals(WallpaperWriteResult.SUPERSEDED, oldResult.get(5, TimeUnit.SECONDS))
            assertEquals(latestPath, preference.value)
            assertEquals("new request", selectedFile().readText())
            assertEquals(setOf("avatar.jpg", selectedFile().name), names())
            assertTrue(slow.closed)
        } finally {
            slow.release.countDown()
        }
    }

    @Test fun removePreventsAnOlderInFlightCopyFromRestoringWallpaper() {
        val slow = BlockedInput("old request")
        val first = files.beginChange()
        val oldResult = worker.submit<WallpaperWriteResult> { files.replace(first, { slow }) }
        try {
            assertTrue("First copy never reached its input", slow.entered.await(5, TimeUnit.SECONDS))
            assertEquals(WallpaperWriteResult.SAVED, files.remove(files.beginChange()))
            slow.release.countDown()
            assertEquals(WallpaperWriteResult.SUPERSEDED, oldResult.get(5, TimeUnit.SECONDS))
            assertEquals("", preference.value)
            assertEquals(setOf("avatar.jpg"), names())
            assertTrue(slow.closed)
        } finally {
            slow.release.countDown()
        }
    }

    @Test fun olderRemoveCannotClearANewerSelection() {
        val pendingRemove = files.beginChange()
        files.replace(files.beginChange(), { TrackedInput("latest") })
        val latestPath = preference.value
        assertEquals(WallpaperWriteResult.SUPERSEDED, files.remove(pendingRemove))
        assertEquals(latestPath, preference.value)
        assertEquals("latest", selectedFile().readText())
    }

    @Test fun cancellationDuringCopyClosesInputAndCleansTemporaryFile() {
        val cancelled = AtomicBoolean(false)
        val source = object : ByteArrayInputStream(ByteArray(DEFAULT_BUFFER_SIZE * 2) { 1 }) {
            var closed = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { cancelled.set(true) }
            override fun close() { closed = true; super.close() }
        }
        assertThrows(CancellationException::class.java) {
            files.replace(files.beginChange(), { source }) {
                if (cancelled.get()) throw CancellationException("screen cleared")
            }
        }
        assertTrue(source.closed)
        assertPreviousSelection()
    }

    @Test fun removeClearsSelectionAndPreservesUnrelatedFiles() {
        assertEquals(WallpaperWriteResult.SAVED, files.remove(files.beginChange()))
        assertEquals("", preference.value)
        assertFalse(previous.exists())
        assertEquals(setOf("avatar.jpg"), names())
    }

    @Test fun outputOpenFailureStillClosesInput() {
        val missingDirectory = File(directory, "missing-directory")
        val source = TrackedInput("candidate")
        val unavailable = WallpaperFiles(missingDirectory, preference)
        assertThrows(IOException::class.java) { unavailable.replace(unavailable.beginChange(), { source }) }
        assertTrue(source.closed)
        assertPreviousSelection()
    }

    private fun assertPreviousSelection() {
        assertEquals("local://${previous.absolutePath}", preference.value)
        assertTrue("The selected previous wallpaper must survive", previous.exists())
        assertEquals("previous", previous.readText())
        assertEquals(setOf("timetable_wallpaper_previous", "avatar.jpg"), names())
    }

    private fun selectedFile() = File(preference.value.removePrefix("local://"))
    private fun names() = directory.listFiles().orEmpty().map { it.name }.toSet()

    private class Preference(@Volatile var value: String) : WallpaperPath {
        var failWrites = false
        override fun read() = value
        override fun write(path: String) {
            if (failWrites) throw IOException("Cannot persist selection")
            value = path
        }
    }

    private open class TrackedInput(value: String) : ByteArrayInputStream(value.toByteArray()) {
        @Volatile var closed = false
        override fun close() { closed = true; super.close() }
    }

    private class BlockedInput(value: String) : TrackedInput(value) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var blocked = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!blocked) {
                blocked = true
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Timed out waiting to complete the old copy")
            }
            return super.read(buffer, offset, length)
        }
    }
}
