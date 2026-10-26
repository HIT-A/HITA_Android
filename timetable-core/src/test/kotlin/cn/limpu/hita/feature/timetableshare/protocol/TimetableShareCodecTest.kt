package cn.limpu.hita.feature.timetableshare.protocol

import org.junit.Assert.*
import org.junit.Test

class TimetableShareCodecTest {
    @Test fun roundTripFromWholeChatMessage() {
        val codec = TimetableShareCodec()
        val input = fullFixture()
        assertEquals(input, codec.decodeMessage("这是我的课表：\n${codec.encode(input)}\n复制到HITA"))
    }
    @Test fun unsupportedVersionHasSpecificError() {
        val error = assertThrows(ShareFormatException::class.java) {
            TimetableShareCodec().decodeMessage("HITA2:AAAA")
        }
        assertEquals(ShareError.UNSUPPORTED_VERSION, error.error)
    }
    @Test fun orderingDoesNotChangeDigest() {
        val codec = TimetableShareCodec()
        val first = fullFixture().copy(occurrences = listOf(
            SharedOccurrence(1788136200000L, 1788142500000L, 0, "A101"),
            SharedOccurrence(1788741000000L, 1788747300000L, 0, "B202")
        ))
        assertEquals(codec.digest(first), codec.digest(first.copy(occurrences = first.occurrences.reversed())))
    }
    @Test fun invalidInputNeverReturnsPartialData() {
        listOf("", "HITA1:!!!", "HITA1:AAAA HITA1:AAAA", "x".repeat(131073)).forEach {
            assertThrows(ShareFormatException::class.java) { TimetableShareCodec().decodeMessage(it) }
        }
    }
    @Test fun busyMergesOverlapsAndAdjacencyWithoutCourseLeak() {
        val base = fullFixture().copy(mode = ShareMode.BUSY, courses = emptyList(), occurrences = listOf(
            SharedOccurrence(1788136200000L, 1788142500000L),
            SharedOccurrence(1788142500000L, 1788143000000L),
            SharedOccurrence(1788136300000L, 1788142000000L)
        ))
        val decoded = TimetableShareCodec().decodeMessage(TimetableShareCodec().encode(base))
        assertEquals(listOf(SharedOccurrence(1788136200000L, 1788143000000L)), decoded.occurrences)
        assertFalse(TimetableShareCodec().canonicalJson(base).contains("\"c\""))
    }
    @Test fun courseReindexingAndEquivalentGroupsHaveStableDigest() {
        val a = fullFixture().occurrences.single()
        val b = a.copy(startMillis = 1788741000000L, endMillis = 1788747300000L, courseIndex = 1)
        val first = fullFixture().copy(courses = listOf("数学", "英语"), occurrences = listOf(a, b))
        val swapped = first.copy(courses = listOf("英语", "数学"), occurrences = listOf(b.copy(courseIndex = 0), a.copy(courseIndex = 1)))
        assertEquals(TimetableShareCodec().digest(first), TimetableShareCodec().digest(swapped))
        val duplicate = fullFixture().copy(courses = listOf("数学", "数学"), occurrences = listOf(a, a.copy(courseIndex = 1)))
        val merged = fullFixture().copy(courses = listOf("数学"), occurrences = listOf(a, a))
        assertEquals(TimetableShareCodec().digest(merged), TimetableShareCodec().digest(duplicate))
    }
    @Test fun rejectsInvalidCharactersInsteadOfRepairingToken() {
        val valid = TimetableShareCodec().encode(fullFixture())
        listOf(valid + "=", valid + "!garbage", valid.substring(0, 20) + "!" + valid.substring(20)).forEach {
            assertEquals(ShareError.CORRUPT_DATA, assertThrows(ShareFormatException::class.java) { TimetableShareCodec().decodeMessage(it) }.error)
        }
        assertEquals(fullFixture(), TimetableShareCodec().decodeMessage("（$valid），复制吧"))
        assertEquals(fullFixture(), TimetableShareCodec().decodeMessage(valid + "!"))
        assertEquals(fullFixture(), TimetableShareCodec().decodeMessage(valid + "?"))
    }
    @Test fun canonicalNicknameAndNullPlaceAndDigestHex() {
        val codec = TimetableShareCodec()
        val normalized = codec.decodeJson(codec.canonicalJson(fullFixture().copy(nickname = " 小林 ", occurrences = listOf(fullFixture().occurrences.single().copy(place = null)))))
        assertEquals("小林", normalized.nickname)
        assertEquals("", normalized.occurrences.single().place)
        assertTrue(codec.digest(fullFixture()).matches(Regex("[0-9a-f]{64}")))
    }
    @Test fun decodesIndependentFixedV1Sample() { assertEquals(fullFixture(), TimetableShareCodec().decodeMessage(FIXED_TOKEN)) }
    @Test fun sameNamedGroupsAreOrderedByNumericOccurrences() {
        val early = SharedOccurrence(999907200000L, 999910800000L, 1, "早")
        val late = SharedOccurrence(1000080000000L, 1000083600000L, 0, "晚")
        val input = fullFixture().copy(term = SharedTerm("测试", "2001秋季", 999273600000L, 1001779200000L), courses = listOf("数学", "数学"), occurrences = listOf(late, early))
        val decoded = TimetableShareCodec().decodeMessage(TimetableShareCodec().encode(input))
        assertEquals(0, decoded.occurrences.first().courseIndex)
        assertEquals(1, decoded.occurrences.last().courseIndex)
    }
    @Test fun errorCategoriesAndLengthLimitsAreSpecific() {
        val codec = TimetableShareCodec()
        listOf("hi" to ShareError.NO_TOKEN, "HITA1:AAAA HITA2:AAAA" to ShareError.MULTIPLE_TOKENS,
            "x".repeat(131073) to ShareError.TOO_LONG, "HITA1:" + "A".repeat(65531) to ShareError.TOO_LONG,
            "HITA1:AB" to ShareError.CORRUPT_DATA).forEach { (text, error) ->
            assertEquals(error, assertThrows(ShareFormatException::class.java) { codec.decodeMessage(text) }.error)
        }
    }
    @Test fun encodingRejectsOversizedSourceInsteadOfTruncating() {
        val codec = TimetableShareCodec()
        val tooLarge = fullFixture().copy(occurrences = List(4096) { fullFixture().occurrences.single().copy(place = "界".repeat(120)) })
        assertEquals(ShareError.DECOMPRESSED_TOO_LARGE, assertThrows(ShareFormatException::class.java) { codec.encode(tooLarge) }.error)
        val random = java.util.Random(123)
        val tokenTooLarge = fullFixture().copy(occurrences = List(4096) {
            fullFixture().occurrences.single().copy(place = (1..120).map { ('a'.code + random.nextInt(26)).toChar() }.joinToString(""))
        })
        assertEquals(ShareError.TOO_LONG, assertThrows(ShareFormatException::class.java) { codec.encode(tokenTooLarge) }.error)
    }
    @Test fun digestIsSha256OfCanonicalUtf8AndPreservesDuplicateOccurrences() {
        val codec = TimetableShareCodec()
        assertEquals(FULL_JSON, codec.canonicalJson(fullFixture()))
        assertEquals("c0c68cc0c3c94a89e4becefe3d9762379cd410bfe728f683dddbf010f5396d49", codec.digest(fullFixture()))
        val duplicate = fullFixture().copy(occurrences = List(2) { fullFixture().occurrences.single() })
        assertEquals(2, codec.decodeMessage(codec.encode(duplicate)).occurrences.size)
        assertNotEquals(codec.digest(fullFixture()), codec.digest(duplicate))
        assertNotEquals(codec.digest(fullFixture()), codec.digest(fullFixture().copy(nickname = "小陈")))
    }
    @Test fun equivalentGroupCollisionsRemainCanonicalAcrossRepeatedRoundTrips() {
        val codec = TimetableShareCodec()
        val x = fullFixture().occurrences.single()
        val input = fullFixture().copy(courses = listOf("数学", "数学", "数学"), occurrences = listOf(
            x.copy(courseIndex = 0), x.copy(courseIndex = 1), x.copy(courseIndex = 2), x.copy(courseIndex = 2)
        ))
        val decoded = codec.decodeMessage(codec.encode(input))
        assertEquals(4, decoded.occurrences.size)
        assertEquals(codec.canonicalJson(input), codec.canonicalJson(decoded))
        assertEquals(codec.digest(input), codec.digest(decoded))
        val twice = codec.decodeMessage(codec.encode(decoded))
        assertEquals(decoded, twice)
        assertEquals(codec.canonicalJson(decoded), codec.canonicalJson(twice))
        assertEquals(codec.digest(decoded), codec.digest(twice))
    }
}
