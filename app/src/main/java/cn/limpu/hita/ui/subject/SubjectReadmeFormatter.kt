package cn.limpu.hita.ui.subject

import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.utils.CourseCodeUtils
import cn.limpu.hita.utils.CourseNameUtils
import org.jsoup.Jsoup
import java.net.URI

data class SubjectMatchSnapshot(val code: String?, val name: String)

/** Text-only compatibility policy. It never reads the current screen or mutable subject. */
object SubjectReadmeFormatter {
    fun format(markdown: String, subject: SubjectMatchSnapshot, candidate: CourseResourceItem?, detailsFallbackTitle: String): String =
        preprocess(filter(markdown, candidate, subject), detailsFallbackTitle)

    private data class ReadmeSection(
        val level: Int,
        val heading: String,
        val content: String,
    )

    private data class CourseMatchKeys(
        val codeTokens: List<String>,
        val nameKeys: List<String>,
    )

    fun filter(
        markdown: String,
        candidate: CourseResourceItem?,
        subject: SubjectMatchSnapshot,
    ): String {
        if (markdown.isBlank()) return markdown
        val keys = buildCourseMatchKeys(candidate, subject)
        if (keys.codeTokens.isEmpty() && keys.nameKeys.isEmpty()) return markdown
        extractByTomlCourseMeta(markdown, keys)?.let { return it }
        val sections = splitReadmeSections(markdown)
        if (sections.size <= 1) return markdown
        val matched = sections.firstOrNull {
            it.heading.isNotBlank() && sectionMatchesKeys(it.heading, keys)
        } ?: return markdown
        return buildString {
            append("#".repeat(matched.level.coerceAtLeast(2)))
            append(" ")
            append(matched.heading)
            append("\n")
            append(matched.content.trim())
        }.trim()
    }

    private fun splitReadmeSections(markdown: String): List<ReadmeSection> {
        val headingRegex = Regex("(?m)^(#{2,4})\\s+(.+)$")
        val matches = headingRegex.findAll(markdown).toList()
        if (matches.isEmpty()) return listOf(ReadmeSection(level = 0, heading = "", content = markdown))
        val sections = mutableListOf<ReadmeSection>()
        val intro = markdown.substring(0, matches.first().range.first)
        sections.add(ReadmeSection(level = 0, heading = "", content = intro))
        for ((index, match) in matches.withIndex()) {
            val level = match.groupValues[1].length
            val heading = match.groupValues[2].trim()
            val start = match.range.last + 1
            val end = findSectionEnd(markdown, matches, index, level)
            sections.add(ReadmeSection(level = level, heading = heading, content = markdown.substring(start, end)))
        }
        return sections
    }

    private fun findSectionEnd(
        markdown: String,
        headings: List<MatchResult>,
        currentIndex: Int,
        currentLevel: Int,
    ): Int {
        for (i in (currentIndex + 1) until headings.size) {
            val level = headings[i].groupValues[1].length
            if (level <= currentLevel) return headings[i].range.first
        }
        return markdown.length
    }

    private fun buildCourseMatchKeys(
        candidate: CourseResourceItem?,
        subject: SubjectMatchSnapshot,
    ): CourseMatchKeys {
        val subjectCodeRaw = subject.code?.trim().orEmpty()
        val subjectCodeNormalized = CourseCodeUtils.normalize(subject.code)?.trim().orEmpty()
        val subjectNameKey = CourseNameUtils.normalizeKey(subject.name)
        val codeTokens = buildList {
            if (subjectCodeRaw.isNotBlank()) add(subjectCodeRaw.lowercase())
            if (subjectCodeNormalized.isNotBlank()) add(subjectCodeNormalized.lowercase())
            if (subjectCodeRaw.isBlank() && subjectCodeNormalized.isBlank()) {
                candidate?.courseCode?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
                candidate?.courseCode?.let { CourseCodeUtils.normalize(it) }?.takeIf { it.isNotBlank() }
                    ?.let { add(it.lowercase()) }
                candidate?.repoName?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
            }
        }.distinct()
        val nameKeys = buildList {
            if (subjectNameKey.isNotBlank()) add(subjectNameKey)
            candidate?.courseName?.let { CourseNameUtils.normalizeKey(it) }
                ?.takeIf { it.isNotBlank() }?.let { add(it) }
            candidate?.aliases
                ?.map { CourseNameUtils.normalizeKey(it) }
                ?.filter { it.isNotBlank() }
                ?.forEach { add(it) }
        }.distinct()
        return CourseMatchKeys(codeTokens = codeTokens, nameKeys = nameKeys)
    }

    private fun extractByTomlCourseMeta(markdown: String, keys: CourseMatchKeys): String? {
        val headingRegex = Regex("(?m)^(#{2,4})\\s+(.+)$")
        val headings = headingRegex.findAll(markdown).toList()
        if (headings.isEmpty()) return null
        val courseMetaRegex = Regex("(?m)^\\s*<!--\\s*TOML-COURSE:\\s*([^>]*)-->\\s*$")
        for ((index, heading) in headings.withIndex()) {
            val level = heading.groupValues[1].length
            val title = heading.groupValues[2].trim()
            val bodyStart = heading.range.last + 1
            val bodyEnd = findSectionEnd(markdown, headings, index, level)
            val body = markdown.substring(bodyStart, bodyEnd)
            val attrs = courseMetaRegex.find(body)?.groupValues?.getOrNull(1) ?: continue
            val code = parseTomlAttr(attrs, "code")
            val name = parseTomlAttr(attrs, "name")
            val tomlHit = tomlCourseMatches(code, name, keys)
            val headingHit = sectionMatchesKeys(title, keys)
            if (!tomlHit && !headingHit) continue
            val cleanedBody = body.replace(courseMetaRegex, "").trim()
            return buildString {
                append("#".repeat(level.coerceAtLeast(2)))
                append(" ")
                append(title)
                if (cleanedBody.isNotBlank()) {
                    append("\n")
                    append(cleanedBody)
                }
            }.trim()
        }
        return null
    }

    private fun parseTomlAttr(attrs: String, key: String): String {
        val regex = Regex("\\b$key\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return regex.find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun tomlCourseMatches(codeRaw: String, nameRaw: String, keys: CourseMatchKeys): Boolean {
        val code = codeRaw.trim().lowercase()
        if (code.isNotBlank()) {
            val normalizedCode = CourseCodeUtils.normalize(code)?.lowercase().orEmpty()
            if (keys.codeTokens.any { token ->
                    token == code ||
                        token == normalizedCode ||
                        code.contains(token) ||
                        token.contains(code) ||
                        (normalizedCode.isNotBlank() && (token.contains(normalizedCode) || normalizedCode.contains(token)))
                }
            ) {
                return true
            }
        }
        val nameKey = CourseNameUtils.normalizeKey(nameRaw)
        return nameKey.isNotBlank() && keys.nameKeys.any { key ->
            key == nameKey || key.contains(nameKey) || nameKey.contains(key)
        }
    }

    private fun sectionMatchesKeys(heading: String, keys: CourseMatchKeys): Boolean {
        val headingKey = CourseNameUtils.normalizeKey(heading)
        val headingLower = heading.lowercase()
        if (headingLower.contains("crossspecialty")) return false
        if (keys.codeTokens.any { token ->
                token.isNotBlank() && (headingLower.contains(token) || token.contains(headingLower))
            }
        ) {
            return true
        }
        if (headingKey.isBlank()) return false
        return keys.nameKeys.any { key ->
            key == headingKey || headingKey.contains(key) || key.contains(headingKey)
        }
    }

    fun preprocess(markdown: String, detailsFallbackTitle: String): String {
        val withTables = convertHtmlTables(markdown)
        val startPattern = Regex("\\{\\{[%<]\\s*details\\s+([^%>]+)\\s*[%>]\\}\\}", RegexOption.IGNORE_CASE)
        val endPattern = Regex("\\{\\{[%<]\\s*/details\\s*[%>]\\}\\}", RegexOption.IGNORE_CASE)
        val replacedStart = startPattern.replace(withTables) { match ->
            val attrs = match.groupValues[1]
            val title = Regex("title\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1) ?: detailsFallbackTitle
            val closed = Regex("closed\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.trim()?.lowercase()
            val openAttr = if (closed == "true") "" else " open"
            "<details$openAttr><summary>$title</summary>"
        }
        return MarkdownPreprocessor.normalizeInlineLatex(
            endPattern.replace(replacedStart, "</details>")
        )
    }

    private fun convertHtmlTables(markdown: String): String {
        val tablePattern = Regex("(?is)<table[^>]*>.*?</table>")
        return tablePattern.replace(markdown) { match ->
            runCatching {
                val doc = Jsoup.parse(match.value)
                val table = doc.selectFirst("table") ?: return@replace match.value
                val rows = table.select("tr")
                if (rows.isEmpty()) return@replace match.value
                val cellsList = rows.map { row ->
                    row.select("th,td").map { it.text().trim() }
                }
                val maxCols = cellsList.maxOfOrNull { it.size } ?: 0
                if (maxCols == 0) return@replace match.value
                fun pad(row: List<String>): List<String> {
                    if (row.size >= maxCols) return row
                    return row + List(maxCols - row.size) { "" }
                }
                val header = pad(cellsList.first())
                val headerRow = header.joinToString(" | ")
                val separator = List(maxCols) { "---" }.joinToString(" | ")
                val body = cellsList.drop(1).joinToString("\n") { row ->
                    pad(row).joinToString(" | ")
                }
                listOf(headerRow, separator, body).filter { it.isNotBlank() }.joinToString("\n")
            }.getOrDefault(match.value)
        }
    }

    fun resolveLink(link: String, source: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        val base = source.trim()
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return runCatching { URI(base).resolve(link).toString() }.getOrDefault(link)
        }
        return link
    }

}
