package cn.limpu.hita.data.source.web.campus

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Date format matches the portal's own card-history frontend. */
internal enum class CampusCardHistoryRange {
    MONTH, YEAR, ALL;

    fun parameters(now: Instant): Map<String, String> {
        if (this == ALL) return emptyMap()
        val date = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val format = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", java.util.Locale.ROOT)
        val start = if (this == YEAR) date.withDayOfYear(1) else date.withDayOfMonth(1)
        val end = if (this == YEAR) start.plusYears(1).minusDays(1) else start.plusMonths(1).minusDays(1)
        return mapOf(
            "beginTime" to start.atStartOfDay().format(format),
            "endTime" to end.atTime(23, 59, 59).format(format),
        )
    }
}
