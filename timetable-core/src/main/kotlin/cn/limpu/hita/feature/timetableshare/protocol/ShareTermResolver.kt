package cn.limpu.hita.feature.timetableshare.protocol

import java.time.Instant

data class ShareTermIdentity(val key: String, val displayName: String)

object ShareTermResolver {
    private val academicCode = Regex("^(?:(?:BENBU|WEIHAI|SHENZHEN):)?([0-9]{4})-([0-9]{4})-?([123])$")

    fun resolve(code: String?, startMillis: Long): ShareTermIdentity {
        val match = code?.let(academicCode::matchEntire)
        if (match != null) {
            val first = match.groupValues[1].toInt()
            val second = match.groupValues[2].toInt()
            if (second == first + 1) {
                val semester = match.groupValues[3]
                val season = when (semester) { "1" -> "秋季"; "2" -> "春季"; else -> "夏季" }
                return ShareTermIdentity("$first-$second-$semester", "$first-${second}学年$season")
            }
        }
        val date = Instant.ofEpochMilli(startMillis).atZone(ShareLimits.ZONE).toLocalDate()
        val spring = date.monthValue <= 7
        return ShareTermIdentity("${date.year}-${if (spring) "spring" else "autumn"}", "${date.year}年${if (spring) "春季" else "秋季"}")
    }
}
