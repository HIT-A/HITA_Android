package cn.limpu.hita.data.source.web.campus

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Date format matches the portal's own card-history frontend. */
internal enum class CampusCardHistoryRange {
    MONTH, ALL;

    fun parameters(now: Instant): Map<String, String> {
        if (this == ALL) return emptyMap()
        val date = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val format = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", java.util.Locale.ROOT)
        return mapOf(
            "beginTime" to date.withDayOfMonth(1).atStartOfDay().format(format),
            "endTime" to date.withDayOfMonth(date.lengthOfMonth()).atTime(23, 59, 59).format(format),
        )
    }
}
