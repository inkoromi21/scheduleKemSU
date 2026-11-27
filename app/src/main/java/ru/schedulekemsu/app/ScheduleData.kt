package ru.schedulekemsu.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
internal enum class ScheduleKind(val title: String) {
    @SerialName("group") GROUP("Группа"),
    @SerialName("teacher") TEACHER("Преподаватель"),
    @SerialName("room") ROOM("Аудитория")
}

internal data class Choice(val id: Int, val name: String, val kind: ScheduleKind?) {
    val key get() = checkNotNull(kind) to id
    val title get() = "${kind?.title ?: "Факультет"}: $name"
}

private val nameSeparator = Regex("[^\\p{L}\\p{N}]+")

private fun String.nameParts() =
    lowercase().replace('ё', 'е').split(nameSeparator).filter(String::isNotEmpty)

internal fun Choice.matchesNameQuery(query: String): Boolean {
    val words = query.nameParts()
    if (words.isEmpty()) return true
    val fullName = name.nameParts()
    return words.all { word ->
        fullName.any { part -> if (word.length == 1) part.startsWith(word) else word in part }
    }
}

@Serializable
internal enum class Week {
    @SerialName("odd") ODD,
    @SerialName("even") EVEN,
    @SerialName("both") BOTH
}

internal data class Lesson(
    val number: Int,
    val time: String,
    val week: Week,
    val subject: String,
    val room: String? = null,
    val details: String = "",
    val weeks: IntRange? = null
)

internal data class ScheduleDay(val name: String, val lessons: List<Lesson>, val number: Int)

internal class TimeTable(
    val choice: Choice,
    body: JsonObject,
    val week: Week?,
    savedAt: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT).format(Date()),
    personal: Boolean = false,
    referenceDay: Int? = null,
    referenceWeek: Int? = null,
    semesterWeeks: List<Int> = emptyList()
) {
    private val kind = checkNotNull(choice.kind)
    val days = when (kind) {
        ScheduleKind.GROUP -> groupSchedule(body)
        ScheduleKind.TEACHER -> teacherSchedule(body)
        ScheduleKind.ROOM -> roomSchedule(body)
    }
    val snapshot = ScheduleSnapshot(
        choice.id, choice.name, kind, week, body, savedAt, personal, referenceDay, referenceWeek, semesterWeeks
    )
}

@Serializable
internal data class ScheduleSnapshot(
    val id: Int,
    val name: String,
    val kind: ScheduleKind,
    val week: Week?,
    val body: JsonObject,
    val savedAt: String,
    val personal: Boolean = false,
    val referenceDay: Int? = null,
    val referenceWeek: Int? = null,
    val semesterWeeks: List<Int> = emptyList()
) {
    fun table() = TimeTable(Choice(id, name, kind), body, week, savedAt, personal, referenceDay, referenceWeek, semesterWeeks)
}

internal fun decodeScheduleCache(encoded: String?): Map<Pair<ScheduleKind, Int>, TimeTable> =
    encoded?.let { Json.decodeFromString<List<ScheduleSnapshot>>(it) }.orEmpty()
        .sortedByDescending { it.savedAt }
        .map { it.table() }
        .associateBy { it.choice.key }

internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: invalid(key)

internal fun JsonObject.optionalObject(key: String): JsonObject? =
    if (this[key] == null || this[key] == JsonNull) null else obj(key)

internal fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray ?: invalid(key)).map { it as? JsonObject ?: invalid(key) }

private fun JsonObject.optionalList(key: String): List<JsonObject> =
    if (this[key] == null || this[key] == JsonNull) emptyList() else objects(key)

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.flag(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

internal fun JsonObject.number(key: String): Int = numberOrNull(key) ?: invalid(key)

private fun JsonObject.numberOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

internal fun JsonObject.requiredText(key: String): String = text(key) ?: invalid(key)

internal fun JsonObject.strictOptionalText(key: String): String? =
    if (this[key] == null || this[key] == JsonNull) null else requiredText(key)

private fun invalid(field: String): Nothing = throw SerializationException("Invalid EIOS field: $field")

private val roomBuckets = linkedMapOf("coupleAll" to Week.BOTH, "coupleOdd" to Week.ODD, "coupleEven" to Week.EVEN)
private val teacherBuckets = linkedMapOf("uneven" to Week.ODD, "even" to Week.EVEN)

private fun lessons(
    period: JsonObject,
    slot: JsonObject,
    buckets: Map<String, Week>,
    fields: List<String>,
    firstWeek: String,
    lastWeek: String
): List<Lesson> = buckets.flatMap { (key, week) ->
    slot.optionalList(key).mapNotNull { entry ->
        val values = fields.associateWith { field -> entry.text(field)?.takeIf(String::isNotBlank) }
        val first = entry.numberOrNull(firstWeek)
        val last = entry.numberOrNull(lastWeek)
        val weeks = if (first != null && last != null && first > 0 && last >= first) first..last else null
        if (values.values.all { it == null } && weeks == null) null else Lesson(
            period.number("Num"), period.requiredText("Description"), week,
            subject = values[fields.first()] ?: "Занятие",
            room = values["AuditoryName"] ?: values["auditoryName"],
            details = values.filterKeys { it != fields.first() && !it.equals("AuditoryName", ignoreCase = true) }
                .values.filterNotNull().joinToString(" · "),
            weeks = weeks
        )
    }
}

internal fun groupSchedule(body: JsonObject): List<ScheduleDay> {
    val result = body.obj("result")
    val table = result.obj("Table")
    val days = result.optionalList("WeekList").sortedBy { it.number("DayNum") }
    val periods = result.optionalList("CoupleList").sortedBy { it.number("Num") }
    val fields = listOf("DiscName", "lessonType", "PrepName", "AuditoryName", "periodTypeName")
    return days.map { day ->
        val entries = periods.flatMap { period ->
            val number = period.number("Num")
            val buckets = roomBuckets.mapKeys { (key, _) -> "$key$number" }
            val slot = table.obj("weekDay${day.number("DayNum")}")
            lessons(period, slot, buckets, fields, "minWeekNum", "maxWeekNum")
        }
        ScheduleDay(day.requiredText("DayName"), entries, day.number("DayNum"))
    }
}

internal fun teacherSchedule(body: JsonObject): List<ScheduleDay> {
    val days = body.obj("result").optionalList("prepScheduleTable").sortedBy { it.obj("weekDay").number("DayNum") }
    val fields = listOf("discName", "groupName", "lessonType", "auditoryName")
    return days.map { day ->
        val entries = day.optionalList("ceilList").sortedBy { it.obj("couple").number("Num") }.flatMap { slot ->
            lessons(slot.obj("couple"), slot.obj("ceil"), teacherBuckets, fields, "startWeekNum", "endWeekNum")
        }
        val weekDay = day.obj("weekDay")
        ScheduleDay(weekDay.requiredText("DayName"), entries, weekDay.number("DayNum"))
    }
}

internal fun roomSchedule(body: JsonObject): List<ScheduleDay> {
    val days = body.optionalList("weekDayList")
    val periods = body.optionalList("coupleList")
    val table = body["table"] as? JsonArray ?: invalid("table")
    val fields = listOf("discName", "groupName", "lessonType", "prepName")
    val dayOrder = days.indices.sortedBy { days[it].number("DayNum") }
    val periodOrder = periods.indices.sortedBy { periods[it].number("Num") }
    return dayOrder.map { dayIndex ->
        val entries = periodOrder.flatMap { periodIndex ->
            val row = table[periodIndex] as? JsonArray ?: invalid("table")
            val slot = row[dayIndex] as? JsonObject ?: invalid("table")
            lessons(periods[periodIndex], slot, roomBuckets, fields, "startWeekNum", "endWeekNum")
        }
        val day = days[dayIndex]
        ScheduleDay(day.requiredText("DayName"), entries, day.number("DayNum"))
    }
}
