package cn.limpu.hita.feature.timetableshare.protocol

import java.time.LocalDate
import java.time.ZoneId

internal object ShareLimits {
    const val MESSAGE_CHARS = 131072
    const val TOKEN_CHARS = 65536
    const val JSON_BYTES = 1048576
    const val COURSES = 512
    const val OCCURRENCES = 4096
    const val PERIODS = 48
    const val JSON_DEPTH = 8
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    val MIN_TIME: Long = LocalDate.of(2000, 1, 1).atStartOfDay(ZONE).toInstant().toEpochMilli()
    val MAX_TIME: Long = LocalDate.of(2100, 1, 1).atStartOfDay(ZONE).toInstant().toEpochMilli()
}
internal fun invalidFields(): Nothing = throw ShareFormatException(ShareError.INVALID_FIELDS)
