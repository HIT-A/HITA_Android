package cn.limpu.hita.feature.timetableshare.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class StrictShareJsonTest {
    private val codec = TimetableShareCodec()
    private fun invalid(json: String, expected: ShareError = ShareError.INVALID_FIELDS) {
        assertEquals(expected, assertThrows(ShareFormatException::class.java) { codec.decodeJson(json) }.error)
    }
    @Test fun acceptsFixedRawJson() { assertEquals(fullFixture(), codec.decodeJson(FULL_JSON)) }
    @Test fun rejectsDuplicateAndUnknownAndMissingKeys() {
        invalid(FULL_JSON.replace("\"v\":1", "\"v\":1,\"v\":1"))
        invalid(FULL_JSON.replace("\"n\":\"小林\"", "\"n\":\"小林\",\"n\":\"小林\""))
        invalid(FULL_JSON.replace("\"v\":1", "\"v\":1,\"x\":0"))
        invalid(FULL_JSON.replace("\"n\":\"小林\",", ""))
    }
    @Test fun rejectsNonIntegerTimesAndWrongTypes() {
        listOf("\"1788136200000\"", "1788136200000.0", "1.7881362e12", "null", "true").forEach {
            invalid(FULL_JSON.replace("1788136200000", it))
        }
        invalid(FULL_JSON.replace("\"小林\"", "123"))
        invalid(FULL_JSON.replace("[510,615]", "[510,615,700]"))
    }
    @Test fun rejectsInvalidReferencesAndEmptyLists() {
        invalid(FULL_JSON.replace("[0,178813", "[-1,178813"))
        invalid(FULL_JSON.replace("[0,178813", "[1,178813"))
        invalid(FULL_JSON.replace("[\"高等数学\"]", "[]"), ShareError.EMPTY_SCHEDULE)
        invalid(FULL_JSON.replace("[[0,1788136200000,1788142500000,\"A101\"]]", "[]"), ShareError.EMPTY_SCHEDULE)
        invalid(FULL_JSON.replace("[[510,615]]", "[]"))
    }
    @Test fun rejectsBusyCourseLeakAndWrongEventShape() {
        invalid(FULL_JSON.replace("\"full\"", "\"busy\""))
        invalid(FULL_JSON.replace("[0,1788136200000,1788142500000,\"A101\"]", "[1788136200000,1788142500000]"))
    }
    @Test fun rejectsInvalidPeriodsDatesAndDuration() {
        invalid(FULL_JSON.replace("[[510,615]]", "[[615,510]]"))
        invalid(FULL_JSON.replace("[[510,615]]", "[[510,615],[600,700]]"))
        invalid(FULL_JSON.replace("[[510,615]]", "[[510,1441]]"))
        invalid(FULL_JSON.replace("1788142500000", "1788236200001"))
        invalid(FULL_JSON.replace("1788105600000", "946656000000"))
        invalid(FULL_JSON.replace("1798646400000", "4102416000000"))
        invalid(FULL_JSON.replace("1798646400000", "1820160000000"))
        invalid(FULL_JSON.replace("Asia/Shanghai", "UTC"))
    }
    @Test fun rejectsNonStrictJsonAndExcessDepth() {
        listOf(FULL_JSON + " {}", FULL_JSON.replace("\"v\"", "v"), FULL_JSON.replace("\"v\":1", "\"v\":01"), FULL_JSON.replace("\"v\":1", "\"v\":1/*x*/"), FULL_JSON.replace("\"v\":1", "\"v\":1," )).forEach { invalid(it) }
        invalid(FULL_JSON.replace("\"v\":1", "\"v\":[[[[[[[[[1]]]]]]]]]"))
    }
    @Test fun enforcesUnicodeCodePointAndCollectionLimits() {
        assertEquals("😀".repeat(40), codec.decodeJson(FULL_JSON.replace("小林", "😀".repeat(40))).nickname)
        invalid(FULL_JSON.replace("小林", "😀".repeat(41)))
        invalid(FULL_JSON.replace("小林", "  "))
        invalid(FULL_JSON.replace("秋季课表", "字".repeat(81)))
        invalid(FULL_JSON.replace("2026年秋季", "字".repeat(81)))
        invalid(FULL_JSON.replace("高等数学", "字".repeat(121)))
        invalid(FULL_JSON.replace("A101", "字".repeat(121)))
        invalid(FULL_JSON.replace("[\"高等数学\"]", List(513) { "\"课$it\"" }.joinToString(",", "[", "]")))
        invalid(FULL_JSON.replace("[[0,1788136200000,1788142500000,\"A101\"]]", List(4097) { "[0,1788136200000,1788142500000,\"A101\"]" }.joinToString(",", "[", "]")))
        invalid(FULL_JSON.replace("[[510,615]]", List(49) { "[${it * 2},${it * 2 + 1}]" }.joinToString(",", "[", "]")))
    }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    private fun token(bytes: ByteArray) = "HITA1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    @Test fun rejectsExpansionBomb() {
        assertEquals(ShareError.DECOMPRESSED_TOO_LARGE, assertThrows(ShareFormatException::class.java) {
            codec.decodeMessage(token(gzip(ByteArray(1048577) { 32 })))
        }.error)
    }
    @Test fun rejectsCorruptCrcTrailingBytesAndSecondMember() {
        val bytes = gzip(FULL_JSON.toByteArray())
        val badCrc = bytes.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }
        listOf(badCrc, bytes + byteArrayOf(0), bytes + bytes, bytes.copyOf(bytes.size - 1), gzip(byteArrayOf(0xc3.toByte(), 0x28))).forEach {
            assertEquals(ShareError.CORRUPT_DATA, assertThrows(ShareFormatException::class.java) { codec.decodeMessage(token(it)) }.error)
        }
    }
    @Test fun validatesNaturalDayBoundariesInShanghaiRegardlessOfDeviceZone() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val wholeLastDay = FULL_JSON.replace("1788136200000", "1798646400000").replace("1788142500000", "1798732800000")
            assertEquals(1798732800000L, codec.decodeJson(wholeLastDay).occurrences.single().endMillis)
            invalid(wholeLastDay.replace("1798732800000", "1798732800001"))
            invalid(FULL_JSON.replace("1788136200000", "1788105599999"))
            invalid(FULL_JSON.replace("1788136200000", "1798732800000").replace("1788142500000", "1798736400000"))
        } finally { java.util.TimeZone.setDefault(previous) }
    }
    @Test fun acceptsAdjacentPeriodsAndRejectsMalformedUuidAndSurrogates() {
        assertEquals(2, codec.decodeJson(FULL_JSON.replace("[[510,615]]", "[[510,615],[615,700]]")).periods.size)
        invalid(FULL_JSON.replace("11111111-1111-4111-8111-111111111111", "1-1-1-1-1"))
        invalid(FULL_JSON.replace("小林", "\\ud800"))
        invalid(FULL_JSON.replace("小林", "\\udc00"))
    }
}
