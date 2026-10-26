package cn.limpu.hita.feature.timetablecompare

import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.feature.timetableshare.protocol.SharedOccurrence

/** 参与对比的一门课。key 在我的课表里是 subjectId(空则课名),好友完整课表里是课程下标字符串。 */
data class ComparisonCourseChoice(val key: String, val label: String)

object ComparisonSelection {
    fun ownCourseKey(subjectId: String, name: String): String = subjectId.ifBlank { name }

    /** Name plus same-name ordinal survives unrelated dictionary insertions without conflating duplicates. */
    fun friendCourseKeys(courses: List<String>): List<String> {
        val counts = mutableMapOf<String, Int>()
        return courses.map { name ->
            val ordinal = counts[name] ?: 0
            counts[name] = ordinal + 1
            "$ordinal:$name"
        }
    }

    fun friendExcludedIndexes(courses: List<String>, excludedKeys: Set<String>?, legacyIndexes: Set<Int>): Set<Int> =
        if (excludedKeys == null) legacyIndexes
        else friendCourseKeys(courses).mapIndexedNotNull { index, key -> index.takeIf { key in excludedKeys } }.toSet()

    /** 未排除的占用。忙闲课表没有课程身份,忽略排除集。 */
    fun friendSpans(mode: ShareMode, occurrences: List<SharedOccurrence>, excludedIndexes: Set<Int>): List<TimeSpan> =
        occurrences.mapNotNull { occurrence ->
            if (mode == ShareMode.FULL && occurrence.courseIndex != null && occurrence.courseIndex in excludedIndexes) {
                null
            } else {
                TimeSpan(occurrence.startMillis, occurrence.endMillis)
            }
        }
}
