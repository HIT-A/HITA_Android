package cn.limpu.hita.agent.attachment

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDocumentProcessorInstrumentedTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var directory: File
    private lateinit var processor: ChatAttachmentProcessor
    @Before fun setUp() {
        directory = File.createTempFile("chat-processor-test-", "", application.cacheDir).apply { delete(); mkdir() }
        processor = ChatAttachmentProcessor(directory, DocumentTextParser()) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }
    @After fun tearDown() {
        val remaining = directory.listFiles().orEmpty().map { it.name }
        directory.deleteRecursively()
        assertTrue("Processor must close PDF/POI streams and delete cached/scratch files: $remaining", remaining.isEmpty())
    }
    private suspend fun process(name: String, bytes: ByteArray): ChatAttachmentResult = processor.process(object : ChatAttachmentInput {
        override val displayName = name
        override fun open() = ByteArrayInputStream(bytes)
    })

    @Test fun actualProcessorReadsBothChineseCmapsAndCleansPdfScratchFiles() = runBlocking {
        for ((text, cmap, ordering, font) in listOf(
            listOf("课程表 高等数学 2026", "UniGB-UCS2-H", "GB1", "STSong-Light"),
            listOf("課程表 高等數學 2026", "UniCNS-UCS2-H", "CNS1", "MSung-Light"),
        )) {
            val result = process("课程.pdf", makePdf(text, cmap, ordering, font)) as ChatAttachmentResult.Text
            assertTrue(result.content.startsWith("【PDF文档】\n页数：1\n\n内容：\n"))
            assertTrue(result.content.contains(text))
        }
    }

    @Test fun excelPreservesCellKindsAndFirstSheetRowColumnLimits() = runBlocking {
        val bytes = ByteArrayOutputStream().use { output ->
            XSSFWorkbook().use { workbook ->
                val sheet = workbook.createSheet("Sheet One")
                repeat(101) { sheet.createRow(it).createCell(0).setCellValue("x") }
                val first = sheet.getRow(0)
                first.getCell(0).setCellValue(1.0)
                first.createCell(1).setCellValue(true)
                first.createCell(2).cellFormula = "A1+1"
                for (column in 3..20) first.createCell(column).setCellValue("value")
                workbook.createSheet("Ignored")
                workbook.write(output)
            }
            output.toByteArray()
        }
        val result = process("sheet.xlsx", bytes) as ChatAttachmentResult.Text
        assertTrue(result.content.startsWith("【Excel表格】\n工作表数量：2\n\n工作表1：Sheet One\n"))
        assertTrue(result.content.contains("[1.0] [true] [A1+1]"))
        assertTrue(result.content.contains("...(列数过多)"))
        assertTrue(result.content.contains("...(行数过多，仅显示前100行)"))
        assertFalse(result.content.contains("第101行"))
    }

    @Test fun powerpointKeepsSlideOrderAndBoundsTextDuringExtraction() = runBlocking {
        val bytes = ByteArrayOutputStream().use { output ->
            XMLSlideShow().use { slides ->
                slides.createSlide().createTextBox().text = "first slide"
                slides.createSlide().createTextBox().text = "x".repeat(6000)
                slides.write(output)
            }
            output.toByteArray()
        }
        val result = process("slides.pptx", bytes) as ChatAttachmentResult.Text
        assertTrue(result.content.startsWith("【PowerPoint演示文稿】\n幻灯片数量：2\n\n幻灯片1：\nfirst slide"))
        assertTrue(result.content.contains("幻灯片2：\n"))
        assertEquals(5000 + DocumentTextParser.TRUNCATION.length, result.content.length)
    }

    @Test fun malformedPdfAndOfficeReturnParseFailuresAndDeleteCachedFiles() = runBlocking {
        for (name in listOf("broken.pdf", "broken.docx", "broken.xlsx", "broken.pptx")) {
            val result = process(name, "not a document".toByteArray()) as ChatAttachmentResult.Failure
            assertEquals(ChatAttachmentResult.Kind.PARSE, result.kind)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun uriAdapterUsesProviderDisplayNameAndRealBytesDespiteZeroReportedSize() = runBlocking {
        val source = AndroidChatAttachmentSource(application)
        val loader = AndroidChatAttachmentLoader(source, processor)
        val result = loader.load(Uri.parse("content://cn.limpu.hita.test.chatattachments/document")) as ChatAttachmentResult.Text
        assertEquals("notes.txt", result.fileName)
        assertEquals("【文本文件】\nhello local fixture", result.content)
        assertEquals("课程.PDF", source.input(Uri.parse("content://cn.limpu.hita.test.chatattachments/download")).displayName)
    }

    private fun makePdf(text: String, cmap: String, ordering: String, font: String): ByteArray {
        val hex = text.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it.toInt() and 255) }
        val stream = "BT /F1 12 Tf 30 780 Td <$hex> Tj ET\n"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${stream.length} >>\nstream\n${stream}endstream",
            "<< /Type /Font /Subtype /Type0 /BaseFont /$font /Encoding /$cmap /DescendantFonts [6 0 R] >>",
            "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /$font /CIDSystemInfo << /Registry (Adobe) /Ordering ($ordering) /Supplement 4 >> /FontDescriptor 7 0 R /DW 1000 >>",
            "<< /Type /FontDescriptor /FontName /$font /Flags 6 /FontBBox [0 -200 1000 900] /ItalicAngle 0 /Ascent 900 /Descent -200 /CapHeight 700 /StemV 80 >>"
        )
        val output = ByteArrayOutputStream()
        fun write(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        write("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, obj ->
            val offset = output.size()
            write("${i + 1} 0 obj\n$obj\nendobj\n")
            offset
        }
        val xref = output.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }
}
