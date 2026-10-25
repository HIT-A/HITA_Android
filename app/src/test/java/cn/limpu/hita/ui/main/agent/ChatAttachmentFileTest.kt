package cn.limpu.hita.ui.main.agent

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatAttachmentFileTest {
    @get:Rule val temporary = TemporaryFolder()

    private class TrackedInput(bytes: ByteArray) : FilterInputStream(ByteArrayInputStream(bytes)) {
        var closed = false
        override fun available() = 0
        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test fun `available zero does not reject nonempty content and stream is closed`() {
        val bytes = "%PDF-test".toByteArray()
        val input = TrackedInput(bytes)
        val attachment = cacheChatAttachment(input, temporary.root, 100)
        attachment.use {
            assertEquals(bytes.size, it.byteCount)
            assertArrayEquals(bytes, it.file.readBytes())
            assertTrue(input.closed)
        }
        assertFalse(attachment.file.exists())
    }

    @Test fun `exact limit across multiple buffers is accepted`() {
        val bytes = ByteArray(16384) { (it % 127).toByte() }
        cacheChatAttachment(ByteArrayInputStream(bytes), temporary.root, bytes.size).use {
            assertEquals(bytes.size, it.byteCount)
            assertArrayEquals(bytes, it.file.readBytes())
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `one byte above limit closes source and deletes partial file`() {
        val input = TrackedInput(ByteArray(8193))
        try {
            cacheChatAttachment(input, temporary.root, 8192)
            fail("oversized stream accepted")
        } catch (expected: ChatAttachmentTooLargeException) {
            assertEquals(8192, expected.maxBytes)
        }
        assertTrue(input.closed)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `zero bulk reads make bounded forward progress`() {
        val bytes = "streamed content".toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        cacheChatAttachment(input, temporary.root, bytes.size).use {
            assertArrayEquals(bytes, it.file.readBytes())
        }
        try {
            cacheChatAttachment(object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            }, temporary.root, 2)
            fail("zero bulk read bypassed limit")
        } catch (_: ChatAttachmentTooLargeException) { }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `read exception closes source and removes partial file`() {
        var closed = false
        val input = object : InputStream() {
            var reads = 0
            override fun read(): Int = throw IOException("provider failed")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("provider failed")
                buffer[offset] = 1
                return 1
            }
            override fun close() { closed = true }
        }
        try {
            cacheChatAttachment(input, temporary.root, 100)
            fail("read failure lost")
        } catch (_: IOException) { }
        assertTrue(closed)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `caller parse exception also removes cached file`() {
        try {
            cacheChatAttachment(ByteArrayInputStream(byteArrayOf(1)), temporary.root, 10).use {
                throw IllegalArgumentException("parser failed")
            }
            fail("parse failure lost")
        } catch (_: IllegalArgumentException) { }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `close failure removes completed temporary file`() {
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { throw IOException("provider close failed") }
        }
        try {
            cacheChatAttachment(input, temporary.root, 10)
            fail("close failure lost")
        } catch (_: IOException) { }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `concurrent attachments have independent temporary files`() {
        cacheChatAttachment(ByteArrayInputStream(byteArrayOf(1)), temporary.root, 10).use { first ->
            cacheChatAttachment(ByteArrayInputStream(byteArrayOf(2)), temporary.root, 10).use { second ->
                assertNotEquals(first.file, second.file)
                assertArrayEquals(byteArrayOf(1), first.file.readBytes())
                assertArrayEquals(byteArrayOf(2), second.file.readBytes())
            }
            assertTrue(first.file.exists())
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
}
