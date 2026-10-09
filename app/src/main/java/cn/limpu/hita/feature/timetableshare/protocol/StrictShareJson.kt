package cn.limpu.hita.feature.timetableshare.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Token reading preserves duplicate-key and numeric-type checks before any tree mapping. */
internal object StrictShareJson {
    fun decode(json: String): SharedTimetable {
        if (json.toByteArray(Charsets.UTF_8).size > ShareLimits.JSON_BYTES) throw ShareFormatException(ShareError.DECOMPRESSED_TOO_LARGE)
        val root = try {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                val value = read(reader, 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) invalidFields()
                value
            }
        } catch (e: ShareFormatException) { throw e } catch (_: java.io.IOException) { invalidFields() } catch (_: IllegalStateException) { invalidFields() }
        if (!root.isJsonObject) invalidFields()
        val obj = root.asJsonObject
        val mode = when (string(obj.get("m"))) { "full" -> ShareMode.FULL; "busy" -> ShareMode.BUSY; else -> invalidFields() }
        val required = setOf("v", "id", "n", "m", "t", "p", "e") + if (mode == ShareMode.FULL) setOf("c") else emptySet()
        if (obj.keySet() != required || number(obj.get("v")) != 1L) invalidFields()
        val term = array(obj.get("t"), 5)
        val periods = list(obj.get("p"), ShareLimits.PERIODS).map { value ->
            val a = array(value, 2)
            SharedPeriod(integer(a[0]), integer(a[1]))
        }
        val courses = if (mode == ShareMode.FULL) list(obj.get("c"), ShareLimits.COURSES).map(::string) else emptyList()
        val occurrences = list(obj.get("e"), ShareLimits.OCCURRENCES).map { value ->
            if (mode == ShareMode.FULL) {
                val a = array(value, 4)
                SharedOccurrence(number(a[1]), number(a[2]), integer(a[0]), string(a[3]))
            } else {
                val a = array(value, 2)
                SharedOccurrence(number(a[0]), number(a[1]))
            }
        }
        return validate(SharedTimetable(string(obj.get("id")), string(obj.get("n")), mode,
            SharedTerm(string(term[0]), string(term[1]), number(term[2]), number(term[3]), string(term[4])), periods, courses, occurrences))
    }

    private fun read(reader: JsonReader, depth: Int): JsonElement {
        if (depth > ShareLimits.JSON_DEPTH) invalidFields()
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                reader.beginObject()
                val names = HashSet<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!names.add(name)) invalidFields()
                    obj.add(name, read(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                reader.beginArray()
                while (reader.hasNext()) {
                    if (array.size() >= ShareLimits.OCCURRENCES) invalidFields()
                    array.add(read(reader, depth + 1))
                }
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                if (!raw.matches(Regex("-?(0|[1-9][0-9]*)"))) invalidFields()
                JsonPrimitive(raw.toLongOrNull() ?: invalidFields())
            }
            else -> invalidFields()
        }
    }
    private fun string(value: JsonElement?): String {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalidFields()
        return value.asString
    }
    private fun number(value: JsonElement?): Long {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) invalidFields()
        return value.asLong
    }
    private fun integer(value: JsonElement?): Int {
        val n = number(value)
        if (n !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalidFields()
        return n.toInt()
    }
    private fun list(value: JsonElement?, max: Int): List<JsonElement> {
        if (value == null || !value.isJsonArray || value.asJsonArray.size() > max) invalidFields()
        return value.asJsonArray.toList()
    }
    private fun array(value: JsonElement?, size: Int): List<JsonElement> = list(value, size).also { if (it.size != size) invalidFields() }

    fun validate(input: SharedTimetable): SharedTimetable {
        fun text(s: String, max: Int, nonempty: Boolean = true) {
            val size = s.codePointCount(0, s.length)
            if (size > max || (nonempty && s.isBlank())) invalidFields()
            // Reject unpaired UTF-16 surrogates rather than serializing replacement characters.
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (Character.isHighSurrogate(c)) {
                    if (i == s.length || !Character.isLowSurrogate(s[i++])) invalidFields()
                } else if (Character.isLowSurrogate(c)) invalidFields()
            }
        }
        if (!input.shareId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) invalidFields()
        val nickname = input.nickname.trim()
        text(nickname, 40)
        val t = input.term
        text(t.timetableName, 80); text(t.termName, 80)
        if (t.zoneId != "Asia/Shanghai" || t.startMillis !in ShareLimits.MIN_TIME until ShareLimits.MAX_TIME || t.endMillis !in ShareLimits.MIN_TIME until ShareLimits.MAX_TIME || t.endMillis <= t.startMillis) invalidFields()
        val startDate = Instant.ofEpochMilli(t.startMillis).atZone(ShareLimits.ZONE).toLocalDate()
        val endDate = Instant.ofEpochMilli(t.endMillis).atZone(ShareLimits.ZONE).toLocalDate()
        if (ChronoUnit.DAYS.between(startDate, endDate) > 370 || t.endMillis - t.startMillis > 370L * 86400000L) invalidFields()
        val lower = startDate.atStartOfDay(ShareLimits.ZONE).toInstant().toEpochMilli()
        val upper = endDate.plusDays(1).atStartOfDay(ShareLimits.ZONE).toInstant().toEpochMilli()
        if (input.periods.isEmpty() || input.periods.size > ShareLimits.PERIODS) invalidFields()
        var previousEnd = -1
        input.periods.forEach {
            if (it.startMinute !in 0..1440 || it.endMinute !in 0..1440 || it.startMinute >= it.endMinute || it.startMinute < previousEnd) invalidFields()
            previousEnd = it.endMinute
        }
        if (input.courses.size > ShareLimits.COURSES || input.occurrences.size > ShareLimits.OCCURRENCES) invalidFields()
        if (input.occurrences.isEmpty() || (input.mode == ShareMode.FULL && input.courses.isEmpty())) throw ShareFormatException(ShareError.EMPTY_SCHEDULE)
        if (input.mode == ShareMode.BUSY && input.courses.isNotEmpty()) invalidFields()
        input.courses.forEach { text(it, 120) }
        input.occurrences.forEach {
            if (it.startMillis !in ShareLimits.MIN_TIME until ShareLimits.MAX_TIME || it.endMillis !in ShareLimits.MIN_TIME until ShareLimits.MAX_TIME || it.endMillis <= it.startMillis || it.endMillis - it.startMillis > 86400000L || it.startMillis < lower || it.startMillis >= upper || it.endMillis > upper) invalidFields()
            if (input.mode == ShareMode.FULL) {
                if (it.courseIndex == null || it.courseIndex !in input.courses.indices) invalidFields()
                text(it.place ?: "", 120, false)
            } else if (it.courseIndex != null || it.place != null) invalidFields()
        }
        return input.copy(nickname = nickname, occurrences = input.occurrences.map { if (input.mode == ShareMode.FULL) it.copy(place = it.place ?: "") else it })
    }
}
