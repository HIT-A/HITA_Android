package cn.limpu.hita.agent.attachment

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatAttachmentProcessorTest {
    @get:Rule val folder = TemporaryFolder()
    private fun processor() = ChatAttachmentProcessor(folder.root, DocumentTextParser()) { java.util.Base64.getEncoder().encodeToString(it) }
    private fun input(name: String, bytes: ByteArray) = object : ChatAttachmentInput {
        override val displayName = name
        override fun open() = ByteArrayInputStream(bytes)
    }
    private fun assertClean() = assertTrue(folder.root.listFiles().orEmpty().isEmpty())

    @Test fun `text result preserves document prefix and bounds output while reading`() = runBlocking {
        val result = processor().process(input("notes.TXT", "x".repeat(6000).toByteArray())) as ChatAttachmentResult.Text
        assertTrue(result.content.startsWith("【文本文件】\n"))
        assertEquals(5000 + DocumentTextParser.TRUNCATION.length, result.content.length)
        assertTrue(result.content.endsWith(DocumentTextParser.TRUNCATION))
        assertClean()
    }

    @Test fun `empty unsupported and text cutoff report distinct failures and clean files`() = runBlocking {
        for ((name, bytes, kind) in listOf(
            Triple("empty.pdf", byteArrayOf(), ChatAttachmentResult.Kind.EMPTY),
            Triple("legacy.doc", byteArrayOf(1), ChatAttachmentResult.Kind.UNSUPPORTED),
            Triple("large.txt", ByteArray(100 * 1024), ChatAttachmentResult.Kind.UNSUPPORTED),
        )) {
            assertEquals(kind, (processor().process(input(name, bytes)) as ChatAttachmentResult.Failure).kind)
            assertClean()
        }
    }

    @Test fun `all advertised images and videos use bounded media encoding`() = runBlocking {
        for ((extension, mime) in mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp", "mp4" to "video/mp4", "mov" to "video/quicktime", "avi" to "video/x-msvideo", "mkv" to "video/x-matroska", "webm" to "video/webm")) {
            val result = processor().process(input("local.$extension", byteArrayOf(1, 2, 3))) as ChatAttachmentResult.Media
            assertEquals(mime, result.mimeType)
            assertEquals("AQID", result.base64)
            assertClean()
        }
    }

    @Test fun `actual media and document byte limits reject one extra byte and close source`() = runBlocking {
        for ((name, limit) in listOf("photo.png" to ChatAttachmentProcessor.MEDIA_MAX_BYTES, "document.pdf" to ChatAttachmentProcessor.DOCUMENT_MAX_BYTES)) {
            var closed = false
            val content = object : ChatAttachmentInput {
                override val displayName = name
                override fun open() = object : InputStream() {
                    var remaining = limit + 1
                    override fun read(): Int = if (remaining-- > 0) 1 else -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length, remaining)
                        buffer.fill(1, offset, offset + count)
                        remaining -= count
                        return count
                    }
                    override fun close() { closed = true }
                }
            }
            val result = processor().process(content) as ChatAttachmentResult.Failure
            assertEquals(ChatAttachmentResult.Kind.TOO_LARGE, result.kind)
            assertEquals(limit, result.maxBytes)
            assertTrue(closed)
            assertClean()
        }
    }

    @Test fun `cancellation during provider read closes stream removes file and emits no failure`() = runBlocking {
        var closed = false
        var delivered = false
        val job = launch {
            val context = currentCoroutineContext()
            val content = object : ChatAttachmentInput {
                override val displayName = "notes.txt"
                override fun open() = object : ByteArrayInputStream(ByteArray(100)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        context.cancel()
                        return super.read(buffer, offset, length)
                    }
                    override fun close() { closed = true; super.close() }
                }
            }
            processor().process(content)
            delivered = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(delivered)
        assertTrue(closed)
        assertClean()
    }

    @Test fun `docx decodes xml entities preserves text order and closes the archive`() = runBlocking {
        val zip = makeZip("word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:t>A &amp; B</w:t><w:t>中文 &lt;课程&gt;</w:t></w:document>""".toByteArray())
        val result = processor().process(input("notes.docx", zip)) as ChatAttachmentResult.Text
        assertEquals("【Word文档】\nA & B 中文 <课程>", result.content)
        assertClean()
    }

    @Test fun `docx rejects dtd before external entity access and cleans temporary file`() = runBlocking {
        val zip = makeZip("word/document.xml" to """<!DOCTYPE w:document [<!ENTITY x SYSTEM "file:///must-not-read">]><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:t>&x;</w:t></w:document>""".toByteArray())
        val result = processor().process(input("notes.docx", zip)) as ChatAttachmentResult.Failure
        assertEquals(ChatAttachmentResult.Kind.PARSE, result.kind)
        assertClean()
    }

    @Test fun `office expanded bytes and entry counts are limited independently of compressed size`() = runBlocking {
        val expanded = makeZip("word/document.xml" to ByteArray(DocumentTextParser.MAX_ZIP_ENTRY_BYTES.toInt() + 1) { 32 })
        assertTrue(expanded.size < 100 * 1024)
        assertEquals(ChatAttachmentResult.Kind.EXPANDED_LIMIT, (processor().process(input("large.docx", expanded)) as ChatAttachmentResult.Failure).kind)
        val entries = makeZip(*(0..DocumentTextParser.MAX_ZIP_ENTRIES).map { "p$it" to byteArrayOf(1) }.toTypedArray())
        assertEquals(ChatAttachmentResult.Kind.EXPANDED_LIMIT, (processor().process(input("many.xlsx", entries)) as ChatAttachmentResult.Failure).kind)
        assertClean()
    }

    @Test fun `office total expanded limit spans multiple individually valid entries`() = runBlocking {
        val expanded = makeZip(*(0..4).map { "part$it.xml" to ByteArray(7 * 1024 * 1024) { 32 } }.toTypedArray())
        assertEquals(ChatAttachmentResult.Kind.EXPANDED_LIMIT, (processor().process(input("large.pptx", expanded)) as ChatAttachmentResult.Failure).kind)
        assertClean()
    }

    @Test fun `cancellation during archive expansion remains cancellation and closes archive`() {
        val file = folder.newFile("source.docx").apply { writeBytes(makeZip("word/document.xml" to ByteArray(64 * 1024))) }
        var checks = 0
        try {
            DocumentTextParser().validateOfficeArchive(file) { if (++checks > 4) throw CancellationException("cancel parser") }
            fail("cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertTrue(file.delete())
        assertClean()
    }

    @Test fun `pdf mht display name is normalized without changing ordinary filenames`() {
        assertEquals("课程.PDF", normalizeChatAttachmentName("课程.PDF.download.MHT"))
        assertEquals("notes.docx", normalizeChatAttachmentName("notes.docx"))
    }

    private fun makeZip(vararg entries: Pair<String, ByteArray>): ByteArray = java.io.ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
        }
        bytes.toByteArray()
    }
}
