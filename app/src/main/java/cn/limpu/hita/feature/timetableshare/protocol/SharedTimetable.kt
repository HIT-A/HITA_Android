package cn.limpu.hita.feature.timetableshare.protocol

enum class ShareMode { FULL, BUSY }
data class SharedPeriod(val startMinute: Int, val endMinute: Int)
data class SharedTerm(val timetableName: String, val termName: String, val startMillis: Long, val endMillis: Long, val zoneId: String = "Asia/Shanghai")
data class SharedOccurrence(val startMillis: Long, val endMillis: Long, val courseIndex: Int? = null, val place: String? = null)
data class SharedTimetable(val shareId: String, val nickname: String, val mode: ShareMode, val term: SharedTerm, val periods: List<SharedPeriod>, val courses: List<String>, val occurrences: List<SharedOccurrence>)
enum class ShareError { NO_TOKEN, MULTIPLE_TOKENS, UNSUPPORTED_VERSION, TOO_LONG, CORRUPT_DATA, DECOMPRESSED_TOO_LARGE, INVALID_FIELDS, EMPTY_SCHEDULE }
class ShareFormatException(val error: ShareError) : IllegalArgumentException(error.name)
