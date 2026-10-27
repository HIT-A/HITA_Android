package cn.limpu.hita.agent.attachment

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CancellationException
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

class AttachmentExpandedLimitException : IOException("Expanded attachment limit exceeded")
internal class AttachmentParseException(cause: Exception) : IOException(cause.message, cause)

/** Format parsing has no URI, session or LiveData dependency. Every output is bounded while produced. */
class DocumentTextParser {
    internal fun parse(file: File, kind: DocumentKind, checkCancelled: () -> Unit): String = try {
        checkCancelled()
        val output = BoundedDocumentText(checkCancelled)
        when (kind) {
            DocumentKind.TEXT -> {
                output.append("【文本文件】\n")
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(1024)
                    while (!output.truncated) {
                        checkCancelled()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        output.append(String(buffer, 0, count))
                    }
                }
            }
            DocumentKind.PDF -> parsePdf(file, output, checkCancelled)
            DocumentKind.DOCX -> { output.append("【Word文档】\n"); output.append(parseDocx(file, checkCancelled)) }
            DocumentKind.XLSX -> parseExcel(file, output, checkCancelled)
            DocumentKind.PPTX -> parsePowerPoint(file, output, checkCancelled)
        }
        checkCancelled()
        output.finish()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (limit: AttachmentExpandedLimitException) {
        throw limit
    } catch (error: Exception) {
        checkCancelled()
        throw AttachmentParseException(error)
    }

    /** Reads actual expanded bytes before XML/POI object allocation, rather than trusting ZIP metadata. */
    internal fun validateOfficeArchive(file: File, checkCancelled: () -> Unit = {}) {
        var total = 0L
        var entries = 0
        ZipInputStream(file.inputStream()).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                checkCancelled()
                zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) throw AttachmentExpandedLimitException()
                var expanded = 0L
                while (true) {
                    checkCancelled()
                    val count = zip.read(buffer)
                    checkCancelled()
                    if (count < 0) break
                    expanded += count
                    total += count
                    if (expanded > MAX_ZIP_ENTRY_BYTES || total > MAX_ZIP_TOTAL_BYTES) throw AttachmentExpandedLimitException()
                }
                zip.closeEntry()
            }
        }
        if (entries == 0) throw IOException("Office文件格式无效")
    }

    internal fun parseDocx(file: File, checkCancelled: () -> Unit = {}): String {
        validateOfficeArchive(file, checkCancelled)
        val result = StringBuilder()
        val run = StringBuilder()
        var insideText = false
        var limited = false
        fun flushRun() {
            val text = run.toString().trim()
            if (text.isNotEmpty()) {
                val remaining = MAX_TEXT + 1 - result.length
                if (remaining > 0) result.append((text + " ").take(remaining))
            }
            run.setLength(0)
        }
        val handler = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("不支持包含DTD的文档") }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("不支持外部实体")
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                checkCancelled()
                insideText = localName == "t" && uri == "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                checkCancelled()
                if (!insideText) return
                val remaining = MAX_TEXT + 1 - result.length - run.length
                if (remaining > 0) run.append(ch, start, minOf(length, remaining))
                if (length > remaining) { limited = true; throw TextLimitReached() }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (insideText && localName == "t") flushRun()
                insideText = false
            }
        }
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return ""
            zip.getInputStream(entry).use { input ->
                val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
                reader.contentHandler = handler
                reader.entityResolver = handler
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
                try { reader.parse(InputSource(input)) } catch (_: TextLimitReached) { flushRun() }
            }
        }
        checkCancelled()
        val text = result.toString().replace(Regex("\\s+"), " ").trim()
        return if (limited || text.length > MAX_TEXT) text.take(MAX_TEXT) + TRUNCATION else text
    }

    private fun parsePdf(file: File, output: BoundedDocumentText, checkCancelled: () -> Unit) {
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(file.parentFile)
        PDDocument.load(file, memory).use { document ->
            checkCancelled()
            val pages = document.numberOfPages
            output.append("【PDF文档】\n")
            output.append(if (pages > 10) "总页数：${pages}（仅解析前10页）\n\n内容：\n" else "页数：${pages}\n\n内容：\n")
            val stripper = PDFTextStripper().apply { startPage = 1; endPage = minOf(pages, 10); setSortByPosition(true) }
            val writer = object : Writer() {
                override fun write(buffer: CharArray, offset: Int, count: Int) {
                    output.append(String(buffer, offset, count))
                    if (output.truncated) throw TextLimitReached()
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
            try { stripper.writeText(document, writer) } catch (_: TextLimitReached) { }
        }
    }

    private fun parseExcel(file: File, output: BoundedDocumentText, checkCancelled: () -> Unit) {
        validateOfficeArchive(file, checkCancelled)
        file.inputStream().use { input ->
            XSSFWorkbook(input).use { workbook ->
                checkCancelled()
                output.append("【Excel表格】\n工作表数量：${workbook.numberOfSheets}\n\n")
                val sheet = workbook.getSheetAt(0)
                output.append("工作表1：${sheet.sheetName}\n")
                var rows = 0
                for (row in sheet) {
                    checkCancelled()
                    if (rows >= 100) { output.append("\n...(行数过多，仅显示前100行)"); break }
                    val cells = StringBuilder()
                    var columns = 0
                    for (cell in row) {
                        checkCancelled()
                        if (columns++ >= 20) { cells.append(" ...(列数过多)"); break }
                        val value = when (cell.cellType) {
                            CellType.STRING -> cell.stringCellValue
                            CellType.NUMERIC -> cell.numericCellValue.toString()
                            CellType.BOOLEAN -> cell.booleanCellValue.toString()
                            CellType.FORMULA -> cell.cellFormula
                            else -> ""
                        }
                        if (value.isNotEmpty()) cells.append("[").append(value.take(MAX_TEXT + 1)).append("] ")
                        if (cells.length > MAX_TEXT) break
                    }
                    if (cells.isNotEmpty()) output.append("第${rows + 1}行：$cells\n")
                    rows++
                    if (output.truncated) break
                }
            }
        }
    }

    private fun parsePowerPoint(file: File, output: BoundedDocumentText, checkCancelled: () -> Unit) {
        validateOfficeArchive(file, checkCancelled)
        file.inputStream().use { input ->
            XMLSlideShow(input).use { slides ->
                checkCancelled()
                output.append("【PowerPoint演示文稿】\n幻灯片数量：${slides.slides.size}\n\n")
                for ((index, slide) in slides.slides.withIndex()) {
                    checkCancelled()
                    output.append("幻灯片${index + 1}：\n")
                    var hasText = false
                    for (shape in slide.shapes) {
                        checkCancelled()
                        if (shape is XSLFTextShape && shape.text.isNotEmpty()) {
                            output.append(shape.text)
                            output.append("\n")
                            hasText = true
                        }
                        if (output.truncated) break
                    }
                    if (hasText) output.append("\n")
                    if (output.truncated) break
                }
            }
        }
    }

    companion object {
        internal const val MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024L
        internal const val MAX_ZIP_TOTAL_BYTES = 32 * 1024 * 1024L
        internal const val MAX_ZIP_ENTRIES = 1000
        internal const val MAX_TEXT = 5000
        internal const val TRUNCATION = "\n\n...(内容过长，仅显示前5000字)"
    }
}

private class TextLimitReached : SAXException()

private class BoundedDocumentText(private val checkCancelled: () -> Unit) {
    private val value = StringBuilder()
    var truncated = false
        private set
    fun append(text: String) {
        checkCancelled()
        val remaining = DocumentTextParser.MAX_TEXT - value.length
        value.append(text.take(remaining))
        if (text.length > remaining) truncated = true
    }
    fun finish(): String = value.toString() + if (truncated) DocumentTextParser.TRUNCATION else ""
}
