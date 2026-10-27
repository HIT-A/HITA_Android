package cn.limpu.hita.agent.attachment

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.Collections
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CancellationException
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
        ZipFile(file).use { zip ->
            val workbook = zip.getEntry("xl/workbook.xml") ?: throw IOException("Excel文件缺少工作簿")
            val sheets = readWorkbookSheets(zip, workbook, checkCancelled)
            val sharedStrings = readSharedStrings(zip, checkCancelled)
            checkCancelled()
            output.append("【Excel表格】\n工作表数量：${sheets.size}\n\n")
            val first = sheets.firstOrNull() ?: throw IOException("Excel文件没有工作表")
            output.append("工作表1：${first.name}\n")
            val relationships = readWorkbookRelationships(zip, checkCancelled)
            val sheetPath = resolveOfficeTarget(relationships[first.relationshipId])
                ?: "xl/worksheets/sheet1.xml"
            val sheet = zip.getEntry(sheetPath) ?: throw IOException("Excel文件缺少首个工作表")
            readWorksheet(zip, sheet, sharedStrings, output, checkCancelled)
        }
    }

    private fun parsePowerPoint(file: File, output: BoundedDocumentText, checkCancelled: () -> Unit) {
        validateOfficeArchive(file, checkCancelled)
        ZipFile(file).use { zip ->
            val slides = Collections.list(zip.entries())
                .mapNotNull { entry ->
                    val match = PPT_SLIDE_ENTRY.matchEntire(entry.name) ?: return@mapNotNull null
                    match.groupValues[1].toIntOrNull()?.let { it to entry }
                }
                .sortedBy { it.first }
            checkCancelled()
            output.append("【PowerPoint演示文稿】\n幻灯片数量：${slides.size}\n\n")
            for ((index, slide) in slides.withIndex()) {
                checkCancelled()
                output.append("幻灯片${index + 1}：\n")
                val text = StringBuilder()
                val handler = object : SecureXmlHandler(checkCancelled) {
                    var insideText = false
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                        checkCancelled()
                        insideText = xmlName(localName, qName) == "t"
                    }
                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        checkCancelled()
                        if (!insideText) return
                        val remaining = MAX_TEXT + 1 - text.length
                        if (remaining > 0) text.append(ch, start, minOf(length, remaining))
                        if (length > remaining) throw TextLimitReached()
                    }
                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        insideText = false
                    }
                }
                var stopped = false
                try {
                    zip.getInputStream(slide.second).use { input -> parseXml(input, handler) }
                } catch (_: TextLimitReached) {
                    stopped = true
                }
                if (text.isNotEmpty()) output.append(text.toString())
                if (text.isNotEmpty()) output.append("\n\n")
                if (stopped || output.truncated) break
            }
        }
    }

    private fun readWorkbookSheets(zip: ZipFile, entry: java.util.zip.ZipEntry, checkCancelled: () -> Unit): List<WorkbookSheet> {
        val result = ArrayList<WorkbookSheet>()
        val handler = object : SecureXmlHandler(checkCancelled) {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                checkCancelled()
                if (xmlName(localName, qName) == "sheet") {
                    result += WorkbookSheet(attribute(attributes, "name").orEmpty(), attribute(attributes, "id").orEmpty())
                }
            }
        }
        zip.getInputStream(entry).use { parseXml(it, handler) }
        return result
    }

    private fun readWorkbookRelationships(zip: ZipFile, checkCancelled: () -> Unit): Map<String, String> {
        val entry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val handler = object : SecureXmlHandler(checkCancelled) {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                checkCancelled()
                if (xmlName(localName, qName) == "Relationship") {
                    val id = attribute(attributes, "Id") ?: attribute(attributes, "id")
                    val target = attribute(attributes, "Target") ?: attribute(attributes, "target")
                    if (!id.isNullOrEmpty() && !target.isNullOrEmpty()) result[id] = target
                }
            }
        }
        zip.getInputStream(entry).use { parseXml(it, handler) }
        return result
    }

    private fun readSharedStrings(zip: ZipFile, checkCancelled: () -> Unit): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val result = ArrayList<String>()
        var current: StringBuilder? = null
        var insideText = false
        val handler = object : SecureXmlHandler(checkCancelled) {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                checkCancelled()
                when (xmlName(localName, qName)) {
                    "si" -> current = StringBuilder()
                    "t" -> if (current != null) insideText = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                checkCancelled()
                if (!insideText) return
                val value = current ?: return
                val remaining = MAX_TEXT + 1 - value.length
                if (remaining > 0) value.append(ch, start, minOf(length, remaining))
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (xmlName(localName, qName)) {
                    "t" -> insideText = false
                    "si" -> current?.let { result += it.toString(); current = null }
                }
            }
        }
        zip.getInputStream(entry).use { parseXml(it, handler) }
        return result
    }

    private fun readWorksheet(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        sharedStrings: List<String>,
        output: BoundedDocumentText,
        checkCancelled: () -> Unit,
    ) {
        var rowNumber = 0
        var currentCell: ExcelCellCapture? = null
        var currentRow: ArrayList<ExcelCell>? = null
        var inValue = false
        var inFormula = false
        var inInlineText = false
        val handler = object : SecureXmlHandler(checkCancelled) {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                checkCancelled()
                when (xmlName(localName, qName)) {
                    "row" -> {
                        if (rowNumber >= 100) {
                            output.append("\n...(行数过多，仅显示前100行)")
                            throw WorksheetLimitReached()
                        }
                        rowNumber++
                        currentRow = ArrayList()
                    }
                    "c" -> {
                        currentCell = ExcelCellCapture(attribute(attributes, "t"), StringBuilder(), StringBuilder())
                        inValue = false
                        inFormula = false
                        inInlineText = false
                    }
                    "v" -> if (currentCell != null) inValue = true
                    "f" -> if (currentCell != null) inFormula = true
                    "is" -> if (currentCell != null) inInlineText = true
                    "t" -> if (inInlineText) inValue = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                checkCancelled()
                val cell = currentCell ?: return
                val target = when {
                    inFormula -> cell.formula
                    inValue -> cell.value
                    else -> null
                } ?: return
                val remaining = MAX_TEXT + 1 - target.length
                if (remaining > 0) target.append(ch, start, minOf(length, remaining))
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (xmlName(localName, qName)) {
                    "v" -> inValue = false
                    "f" -> inFormula = false
                    "t" -> if (inInlineText) inValue = false
                    "is" -> inInlineText = false
                    "c" -> {
                        val cell = currentCell ?: return
                        currentRow?.add(cell.toCell(sharedStrings))
                        currentCell = null
                    }
                    "row" -> {
                        val row = currentRow.orEmpty()
                        val rendered = StringBuilder()
                        var columns = 0
                        for (cell in row) {
                            checkCancelled()
                            if (columns++ >= 20) {
                                rendered.append(" ...(列数过多)")
                                break
                            }
                            if (cell.value.isNotEmpty()) rendered.append("[").append(cell.value.take(MAX_TEXT + 1)).append("] ")
                        }
                        if (rendered.isNotEmpty()) output.append("第${rowNumber}行：$rendered\n")
                        currentRow = null
                    }
                }
            }
        }
        try {
            zip.getInputStream(entry).use { parseXml(it, handler) }
        } catch (_: WorksheetLimitReached) {
            // The bounded row marker has already been emitted.
        }
    }

    private fun parseXml(input: java.io.InputStream, handler: SecureXmlHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.errorHandler = handler
        try { reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler) } catch (_: SAXException) { }
        reader.parse(InputSource(input))
    }

    companion object {
        internal const val MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024L
        internal const val MAX_ZIP_TOTAL_BYTES = 32 * 1024 * 1024L
        internal const val MAX_ZIP_ENTRIES = 1000
        internal const val MAX_TEXT = 5000
        internal const val TRUNCATION = "\n\n...(内容过长，仅显示前5000字)"
        private val PPT_SLIDE_ENTRY = Regex("ppt/slides/slide(\\d+)\\.xml")
    }
}

private class TextLimitReached : SAXException()
private class WorksheetLimitReached : SAXException()

private data class WorkbookSheet(val name: String, val relationshipId: String)

private data class ExcelCell(val value: String)

private class ExcelCellCapture(
    private val type: String?,
    val value: StringBuilder,
    val formula: StringBuilder,
) {
    fun toCell(sharedStrings: List<String>): ExcelCell {
        val raw = value.toString()
        val resolved = when (type) {
            "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it).orEmpty() }.orEmpty()
            "b" -> if (raw == "1") "true" else "false"
            "inlineStr", "str" -> raw
            else -> if (formula.isNotEmpty()) formula.toString() else raw
        }
        return ExcelCell(resolved)
    }
}

private abstract class SecureXmlHandler(protected val checkCancelled: () -> Unit) : DefaultHandler2() {
    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        throw SAXException("不支持包含DTD的文档")
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
        throw SAXException("不支持外部实体")
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        checkCancelled()
    }
}

private fun xmlName(localName: String?, qName: String?): String =
    localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

private fun attribute(attributes: Attributes?, name: String): String? {
    if (attributes == null) return null
    for (index in 0 until attributes.length) {
        val local = attributes.getLocalName(index)
        val qualified = attributes.getQName(index)
        if (local == name || qualified == name || qualified.substringAfter(':') == name) {
            return attributes.getValue(index)
        }
    }
    return null
}

private fun resolveOfficeTarget(target: String?): String? {
    if (target.isNullOrBlank()) return null
    val initial = if (target.startsWith('/')) target.drop(1) else "xl/$target"
    val path = java.util.ArrayDeque<String>()
    initial.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (path.isNotEmpty()) path.removeLast()
            else -> path.addLast(part)
        }
    }
    return path.joinToString("/")
}

private fun SAXParserFactory.setFeatureIfSupported(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: Exception) {
        // Android XML parser implementations differ in the optional feature set.
    }
}

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
