package cn.limpu.hita.utils

import cn.limpu.hita.data.model.eas.TermItem
import java.util.Calendar

/**
 * 学期工具类
 *
 * 提供学期相关的公共方法，包括过滤、格式化等
 */
object TermUtils {

    fun filterTermsForStudent(
        allTerms: List<TermItem>,
        grade: String?
    ): List<TermItem> = filterTermsForStudent(
        allTerms,
        grade,
        Calendar.getInstance().get(Calendar.YEAR),
        Calendar.getInstance().get(Calendar.MONTH)
    )

    internal fun filterTermsForStudent(
        allTerms: List<TermItem>,
        grade: String?,
        currentYear: Int,
        currentMonth: Int
    ): List<TermItem> {
        val enrollmentYear = Regex("(20\\d{2})").find(grade.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (enrollmentYear == null) return filterRecentTerms(allTerms, currentYear)

        val currentAcademicStart = allTerms.firstOrNull { it.isCurrent }
            ?.yearCode?.let(::startYear)
            ?: if (currentMonth >= Calendar.AUGUST) currentYear else currentYear - 1
        val filtered = allTerms.filter { term ->
            val year = startYear(term.yearCode) ?: return@filter false
            year in enrollmentYear..currentAcademicStart
        }
        return sortNewestFirst(if (filtered.isNotEmpty()) filtered else allTerms)
    }

    fun courseSelectionTerms(allTerms: List<TermItem>): List<TermItem> =
        sortNewestFirst(allTerms)

    /**
     * Filter terms for timetable import. The academic system may expose a
     * newly opened future term before marking it as the current term, so the
     * current-term upper bound used by score/exam history must not apply here.
     */
    fun filterTermsForTimetableImport(
        allTerms: List<TermItem>,
        grade: String?
    ): List<TermItem> {
        val enrollmentYear = Regex("(20\\d{2})").find(grade.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val filtered = if (enrollmentYear == null) {
            filterRecentTerms(allTerms)
        } else {
            allTerms.filter { term ->
                startYear(term.yearCode)?.let { it >= enrollmentYear } == true
            }
        }
        return sortNewestFirst(if (filtered.isNotEmpty()) filtered else allTerms)
    }

    /**
     * 过滤学期列表，只显示最近的学期
     *
     * 原因：API返回了从2007年开始的所有历史学期，用户不需要看到这么多
     * 策略：显示最近4年的学期（约8个学期）
     *
     * 使用场景：
     * - 考试查询的学期选择
     * - 成绩查询的学期选择
     * - 空教室查询的学期选择
     * - 导入课表的学期选择
     *
     * @param allTerms API返回的所有学期
     * @return 过滤后的学期列表，按学年代码和学期代码降序排序
     */
    fun filterRecentTerms(allTerms: List<TermItem>): List<TermItem> {
        return filterRecentTerms(allTerms, Calendar.getInstance().get(Calendar.YEAR))
    }

    internal fun filterRecentTerms(allTerms: List<TermItem>, currentYear: Int): List<TermItem> {
        val cutoffYear = currentYear - 3 // 最近4年（含当前年）

        // 过滤学期：只保留近4年的学期
        val filtered = allTerms.filter { term ->
            val yearCode = term.yearCode // 格式：2025-2026
            val startYear = yearCode.split("-")[0].toIntOrNull() ?: 0

            // 检查学年是否在最近4年内
            startYear >= cutoffYear
        }

        // 排序：按学年代码降序，然后按学期代码降序
        return sortNewestFirst(filtered)
    }

    /**
     * 获取当前年份
     */
    fun getCurrentYear(): Int {
        return Calendar.getInstance().get(Calendar.YEAR)
    }

    /**
     * 检查学期是否在最近4年内
     *
     * @param term 要检查的学期
     * @return true如果在最近4年内，false否则
     */
    fun isRecentTerm(term: TermItem): Boolean {
        val cutoffYear = getCurrentYear() - 3
        val startYear = term.yearCode.split("-")[0].toIntOrNull() ?: 0
        return startYear >= cutoffYear
    }

    private fun sortNewestFirst(terms: List<TermItem>): List<TermItem> = terms
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<TermItem> { startYear(it.yearCode) ?: Int.MIN_VALUE }
                .thenByDescending { it.termCode.toIntOrNull() ?: Int.MIN_VALUE }
        )

    private fun startYear(raw: String): Int? = Regex("(20\\d{2})")
        .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
