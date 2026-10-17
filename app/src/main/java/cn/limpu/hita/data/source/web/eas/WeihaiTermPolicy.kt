package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.TermItem
import java.util.Calendar

/**
 * 威海校区的学期下拉框策略：解析 option + 裁剪不可见学期。
 *
 * 为什么单独抽出来：
 * 1. 威海是三校区中唯一需要裁剪学期列表的校区（[AbstractEASWebSource.postProcessTerms] 只有它覆盖）；
 * 2. 威海的学期文本格式与本部/深圳不同（可能出现"第一学期/第二学期"这类不含年份的文案）；
 * 3. 这里只放纯函数，不依赖 Android，可在没有威海账号的情况下用 JVM 单测覆盖。
 *
 * 修正的历史缺陷：
 * 旧实现用 [AbstractEASWebSource.termTimelineKey] 判断"该学期是否晚于当前学期"，而该 key 对秋季取
 * 学年起始年、对春季/夏季取学年结束年，两段刻度不在同一坐标系里。于是当前学期为 2026-2027 秋季时，
 * 2025-2026 春季/夏季的 key 分别是 (2026,1)/(2026,2)，被判成"未来学期"删除，成绩页只剩每年秋季。
 * 现在只按**学年起始年**裁剪：上一学年的春季/夏季必定保留，未来学年整体隐藏；
 * 同一学年内尚未开课的春季/夏季不裁剪（其它校区同样会列出，图二即为此表现）。
 */
internal object WeihaiTermPolicy {

    /** 例如 "2025-20262"、"2025-2026-2"、"202520262"。 */
    private val paddedValue = Regex("""^(\d{4}-\d{4})(\d+)$""")
    private val compactValue = Regex("""^(\d{4})(\d{4})(\d+)$""")
    private val yearRange = Regex("""\d{4}-\d{4}""")
    private val anyYear = Regex("""\d{4}""")

    /**
     * 解析一个 `<option value=... >文本</option>`。
     *
     * @param value option 的 value，学年学期代码，如 "2025-20262"
     * @param label option 的展示文本，如 "2025-2026学年第二学期"、"春季学期"
     * @return 解析失败（拿不到学年或学期）时返回 null，由调用方跳过该 option
     */
    fun parseTermValue(value: String, label: String): TermItem? {
        val cleaned = value.trim()
        // 部分历史页面会给某些学期留空 value，此时退回用文案推断，避免整条学期被丢掉。
        if (cleaned.isEmpty()) return parseFromLabel(label)

        var yearCode: String? = null
        var termCode: String? = null

        paddedValue.matchEntire(cleaned)?.let { match ->
            yearCode = match.groupValues[1]
            termCode = match.groupValues[2]
        }
        if (yearCode == null) {
            compactValue.matchEntire(cleaned)?.let { match ->
                yearCode = "${match.groupValues[1]}-${match.groupValues[2]}"
                termCode = match.groupValues[3]
            }
        }
        if (yearCode == null) {
            yearCode = yearRange.find(cleaned)?.value ?: anyYear.find(cleaned)?.value
        }
        val resolvedYear = yearCode ?: return parseFromLabel(label)

        if (termCode == null) {
            // 先把学年从值里剔除，避免把学年尾数（2026）当成学期码。
            val remainder = cleaned.replace(resolvedYear, "").trim('-', '_', ' ', '/')
            termCode = Regex("""\d+""").find(remainder)?.value
        }
        if (termCode == null) {
            // 值里没有学期位时，退回用文案里的"秋/春/夏"或"第一/第二/第三"推断。
            termCode = termCodeFromLabel(label)
        }
        val resolvedTerm = termCode ?: return null

        return buildTerm(resolvedYear, resolvedTerm, label)
    }

    /** 从 option 文案推断学年与学期，仅用于 value 缺失或不可解析的情况。 */
    private fun parseFromLabel(label: String): TermItem? {
        val compact = label.replace(Regex("""\s+"""), "").trim()
        if (compact.isBlank()) return null

        val yearCode = yearRange.find(compact)?.value
            ?: Regex("""(\d{4})学年""").find(compact)?.groupValues?.getOrNull(1)?.let { "$it-${it.toInt() + 1}" }
            ?: anyYear.find(compact)?.value?.let { "$it-${it.toInt() + 1}" }
            ?: return null
        val termCode = termCodeFromLabel(compact) ?: return null
        return buildTerm(yearCode, termCode, label)
    }

    private fun buildTerm(yearCode: String, termCode: String, label: String): TermItem {
        val normalizedLabel = normalizeTermLabel(label)
        val derivedTermName = when {
            normalizedLabel.isNotBlank() -> normalizedLabel
            termCode.startsWith("1") -> "$yearCode 秋季学期"
            termCode.startsWith("2") -> "$yearCode 春季学期"
            termCode.startsWith("3") -> "$yearCode 夏季学期"
            termCode.startsWith("4") -> "$yearCode 冬季学期"
            else -> "$yearCode $termCode"
        }

        return TermItem(
            yearCode = yearCode,
            yearName = yearCode,
            termCode = termCode,
            termName = derivedTermName
        ).apply {
            name = derivedTermName
        }
    }

    /**
     * 裁剪不可见学期：只隐藏"未来学年"，不按季节裁剪。
     *
     * 当前学年起点取三者最大值，避免教务页面默认选中项停留在旧学期时把可用学期一起裁掉
     * （与 [cn.limpu.hita.utils.TermUtils.filterTermsForStudent] 的口径一致）：
     * 1. 日历年推算的学年起点（8 月及以后算新学年）；
     * 2. 所有被标记为当前学期的学年起点；
     * 3. 无法解析学年代码的学期一律保留，宁可多显示也不隐藏。
     */
    fun filterVisible(
        terms: List<TermItem>,
        calendarYear: Int = Calendar.getInstance().get(Calendar.YEAR),
        calendarMonth: Int = Calendar.getInstance().get(Calendar.MONTH),
    ): List<TermItem> {
        if (terms.isEmpty()) return terms
        val calendarAcademicStart = if (calendarMonth >= Calendar.AUGUST) calendarYear else calendarYear - 1
        val markedAcademicStart = terms.asSequence()
            .filter { it.isCurrent }
            .mapNotNull { academicStartYear(it.yearCode) }
            .maxOrNull()
        val currentAcademicStart = maxOf(calendarAcademicStart, markedAcademicStart ?: calendarAcademicStart)

        val filtered = terms.filter { term ->
            val start = academicStartYear(term.yearCode) ?: return@filter true
            start <= currentAcademicStart
        }
        return filtered.ifEmpty { terms }
    }

    /** 取 "2025-2026" / "2025" 这类学年代码的起始年；无法解析返回 null。 */
    fun academicStartYear(yearCode: String): Int? {
        val trimmed = yearCode.trim()
        if (trimmed.isEmpty()) return null
        return anyYear.find(trimmed)?.value?.toIntOrNull()
    }

    private fun termCodeFromLabel(label: String): String? {
        val compact = label.replace(Regex("""\s+"""), "")
        return when {
            compact.contains("秋") -> "1"
            compact.contains("春") -> "2"
            compact.contains("夏") -> "3"
            compact.contains("冬") || compact.contains("寒") -> "4"
            compact.contains("第一") || compact.contains("上学期") -> "1"
            compact.contains("第二") || compact.contains("下学期") -> "2"
            compact.contains("第三") -> "3"
            compact.contains("第四") -> "4"
            else -> null
        }
    }

    private fun normalizeTermLabel(label: String): String {
        val compact = label.replace(Regex("""\s+"""), "").trim()
        if (compact.isBlank()) return ""

        val yearCode = yearRange.find(compact)?.value
            ?: Regex("""(\d{4})学年""").find(compact)?.groupValues?.getOrNull(1)?.let { "$it-${it.toInt() + 1}" }
            ?: ""

        val season = when {
            compact.contains("秋") -> "秋季学期"
            compact.contains("春") -> "春季学期"
            compact.contains("夏") -> "夏季学期"
            compact.contains("寒") || compact.contains("冬") -> "冬季学期"
            compact.contains("第一") -> "秋季学期"
            compact.contains("第二") -> "春季学期"
            compact.contains("第三") -> "夏季学期"
            compact.contains("第四") -> "冬季学期"
            compact.contains("上学期") -> "秋季学期"
            compact.contains("下学期") -> "春季学期"
            else -> ""
        }

        return when {
            yearCode.isNotBlank() && season.isNotBlank() -> "$yearCode $season"
            compact.contains("学年") && season.isNotBlank() -> compact.replace("学年", "学年 ").replace("学期", "学期 ").trim()
            else -> compact
        }
    }
}
