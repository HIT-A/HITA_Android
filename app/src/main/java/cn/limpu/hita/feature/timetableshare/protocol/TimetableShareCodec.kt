package cn.limpu.hita.feature.timetableshare.protocol

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/** Pure JVM v1 protocol. Callers perform this bounded work off the UI thread. */
class TimetableShareCodec {
    fun encode(snapshot: SharedTimetable): String {
        val bytes = canonicalJson(snapshot).toByteArray(Charsets.UTF_8)
        if (bytes.size > ShareLimits.JSON_BYTES) throw ShareFormatException(ShareError.DECOMPRESSED_TOO_LARGE)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        val token = "HITA1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        if (token.length > ShareLimits.TOKEN_CHARS) throw ShareFormatException(ShareError.TOO_LONG)
        return token
    }

    fun decodeMessage(message: String): SharedTimetable {
        if (message.length > ShareLimits.MESSAGE_CHARS) throw ShareFormatException(ShareError.TOO_LONG)
        val candidates = Regex("HITA([0-9]+):").findAll(message).toList()
        if (candidates.isEmpty()) throw ShareFormatException(ShareError.NO_TOKEN)
        if (candidates.size != 1) throw ShareFormatException(ShareError.MULTIPLE_TOKENS)
        val match = candidates.single()
        var end = match.range.last + 1
        while (end < message.length && !message[end].isWhitespace() && !terminator(message[end])) end++
        // Punctuation inside ASCII payload is corruption, not permission to truncate and repair.
        if (end < message.length && terminator(message[end]) && end + 1 < message.length && base64Char(message[end + 1])) {
            throw ShareFormatException(ShareError.CORRUPT_DATA)
        }
        val token = message.substring(match.range.first, end)
        if (token.length > ShareLimits.TOKEN_CHARS) throw ShareFormatException(ShareError.TOO_LONG)
        val payload = message.substring(match.range.last + 1, end)
        if (payload.isEmpty() || payload.any { !base64Char(it) } || payload.length % 4 == 1) throw ShareFormatException(ShareError.CORRUPT_DATA)
        if (match.groupValues[1] != "1") throw ShareFormatException(ShareError.UNSUPPORTED_VERSION)
        val bytes = try { Base64.getUrlDecoder().decode(payload) } catch (_: IllegalArgumentException) { throw ShareFormatException(ShareError.CORRUPT_DATA) }
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != payload) throw ShareFormatException(ShareError.CORRUPT_DATA)
        val plain = inflate(bytes)
        val json = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw ShareFormatException(ShareError.CORRUPT_DATA) }
        return decodeJson(json)
    }

    fun decodeJson(json: String): SharedTimetable = normalize(StrictShareJson.decode(json))

    fun canonicalJson(snapshot: SharedTimetable): String {
        val s = normalize(StrictShareJson.validate(snapshot))
        val root = JsonObject()
        root.addProperty("v", 1); root.addProperty("id", s.shareId.lowercase()); root.addProperty("n", s.nickname)
        root.addProperty("m", if (s.mode == ShareMode.FULL) "full" else "busy")
        root.add("t", JsonArray().apply { add(s.term.timetableName); add(s.term.termName); add(s.term.startMillis); add(s.term.endMillis); add(s.term.zoneId) })
        root.add("p", JsonArray().apply { s.periods.forEach { add(JsonArray().apply { add(it.startMinute); add(it.endMinute) }) } })
        if (s.mode == ShareMode.FULL) root.add("c", JsonArray().apply { s.courses.forEach { add(it) } })
        root.add("e", JsonArray().apply {
            s.occurrences.forEach { e -> add(JsonArray().apply {
                if (s.mode == ShareMode.FULL) add(e.courseIndex)
                add(e.startMillis); add(e.endMillis)
                if (s.mode == ShareMode.FULL) add(e.place ?: "")
            }) }
        })
        val json = GsonBuilder().disableHtmlEscaping().create().toJson(root)
        if (json.toByteArray(Charsets.UTF_8).size > ShareLimits.JSON_BYTES) throw ShareFormatException(ShareError.DECOMPRESSED_TOO_LARGE)
        return json
    }

    fun digest(snapshot: SharedTimetable): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalJson(snapshot).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun normalize(s: SharedTimetable): SharedTimetable {
        val eventOrder = compareBy<SharedOccurrence>({ it.startMillis }, { it.endMillis }, { it.courseIndex ?: -1 }, { it.place ?: "" })
        if (s.mode == ShareMode.BUSY) {
            val merged = mutableListOf<SharedOccurrence>()
            s.occurrences.sortedWith(eventOrder).forEach { next ->
                val prev = merged.lastOrNull()
                val sameDay = prev != null && date(prev.startMillis) == date(next.startMillis)
                if (prev != null && sameDay && next.startMillis <= prev.endMillis && maxOf(prev.endMillis, next.endMillis) - prev.startMillis <= 86400000L) {
                    merged[merged.lastIndex] = prev.copy(endMillis = maxOf(prev.endMillis, next.endMillis))
                } else merged.add(next)
            }
            return s.copy(shareId = s.shareId.lowercase(), occurrences = merged)
        }
        data class Group(val name: String, val events: List<SharedOccurrence>)
        var groups = s.courses.indices.map { index -> Group(s.courses[index], s.occurrences.filter { it.courseIndex == index }.map { it.copy(courseIndex = null) }.sortedWith(eventOrder)) }
        // Aggregating equal groups can make their result equal to another existing group.
        // Each merging pass decreases the group count (bounded by COURSES). Reach a fixed
        // point before assigning indices while preserving every occurrence in the multiset.
        while (true) {
            val equivalent = groups.groupBy { it }
            if (equivalent.size == groups.size) break
            groups = equivalent.map { (group, copies) ->
                Group(group.name, copies.flatMap { it.events }.sortedWith(eventOrder))
            }
        }
        val groupOrder = Comparator<Group> { left, right ->
            var result = left.name.compareTo(right.name)
            if (result == 0) {
                for (i in 0 until minOf(left.events.size, right.events.size)) {
                    result = eventOrder.compare(left.events[i], right.events[i])
                    if (result != 0) break
                }
                if (result == 0) result = left.events.size.compareTo(right.events.size)
            }
            result
        }
        val sorted = groups.sortedWith(groupOrder)
        val events = sorted.flatMapIndexed { index, group -> group.events.map { it.copy(courseIndex = index) } }.sortedWith(eventOrder)
        return s.copy(shareId = s.shareId.lowercase(), courses = sorted.map { it.name }, occurrences = events)
    }
    private fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(ShareLimits.ZONE).toLocalDate()
    private fun base64Char(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'
    private fun terminator(c: Char) = c in "，。；：！？、（）【】《》“”‘’,.!?;:()[]<>\"'"

    /** Parses one RFC1952 member explicitly; JDK GZIPInputStream accepts concatenated members. */
    private fun inflate(bytes: ByteArray): ByteArray {
        fun corrupt(): Nothing = throw ShareFormatException(ShareError.CORRUPT_DATA)
        fun u(i: Int): Int = if (i in bytes.indices) bytes[i].toInt() and 255 else corrupt()
        fun le(i: Int): Long = (0..3).fold(0L) { value, n -> value or (u(i + n).toLong() shl (n * 8)) }
        if (bytes.size < 18 || u(0) != 31 || u(1) != 139 || u(2) != 8) corrupt()
        val flags = u(3)
        if (flags and 0xe0 != 0) corrupt()
        var offset = 10
        if (flags and 4 != 0) {
            val length = u(offset) or (u(offset + 1) shl 8)
            offset += 2 + length
            if (offset > bytes.size - 8) corrupt()
        }
        for (flag in listOf(8, 16)) if (flags and flag != 0) {
            while (u(offset++) != 0) { if (offset >= bytes.size - 8) corrupt() }
        }
        if (flags and 2 != 0) {
            val crc = CRC32().apply { update(bytes, 0, offset) }.value.toInt() and 65535
            if ((u(offset) or (u(offset + 1) shl 8)) != crc) corrupt()
            offset += 2
        }
        if (offset >= bytes.size - 8) corrupt()
        val inflater = Inflater(true)
        val out = ByteArrayOutputStream()
        val crc = CRC32()
        try {
            inflater.setInput(bytes, offset, bytes.size - offset)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer, 0, minOf(buffer.size, ShareLimits.JSON_BYTES - out.size() + 1))
                if (out.size() + count > ShareLimits.JSON_BYTES) throw ShareFormatException(ShareError.DECOMPRESSED_TOO_LARGE)
                if (count == 0 && !inflater.finished()) corrupt()
                out.write(buffer, 0, count); crc.update(buffer, 0, count)
            }
            val trailer = bytes.size - inflater.remaining
            if (trailer + 8 != bytes.size || le(trailer) != crc.value || le(trailer + 4) != out.size().toLong()) corrupt()
        } catch (_: java.util.zip.DataFormatException) { corrupt() } finally { inflater.end() }
        return out.toByteArray()
    }
}
