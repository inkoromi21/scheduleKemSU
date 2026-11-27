package ru.schedulekemsu.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val MILLIS_PER_DAY = 86_400_000L
private val kemerovoTime = TimeZone.getTimeZone("Asia/Novokuznetsk")
private val utc = TimeZone.getTimeZone("UTC")
private val russian = Locale.forLanguageTag("ru")

internal fun kemerovoDay(now: Long = System.currentTimeMillis()): Int =
    Math.floorDiv(now + kemerovoTime.getOffset(now), MILLIS_PER_DAY).toInt()

internal fun weekdayIndex(day: Int): Int = Math.floorMod(day + 3, 7)

internal fun mondayOf(day: Int): Int = day - weekdayIndex(day)

internal fun formatScheduleDay(day: Int, pattern: String): String =
    SimpleDateFormat(pattern, russian).apply { timeZone = utc }.format(Date(day * MILLIS_PER_DAY))

// Older snapshots only store the date they were fetched.
private fun ScheduleSnapshot.anchorDay(): Int? {
    referenceDay?.let { return it }
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = utc
        isLenient = false
    }
    return runCatching { format.parse(savedAt.take(10)) }
        .getOrNull()?.let { Math.floorDiv(it.time, MILLIS_PER_DAY).toInt() }
}

// Null means the cached data does not establish the schedule for the selected week.
internal fun TimeTable.lessonsOn(day: Int): List<Lesson>? {
    val anchor = snapshot.anchorDay()
    val offset = anchor?.let { (mondayOf(day) - mondayOf(it)) / 7 } ?: 0
    val weekNumber = snapshot.referenceWeek?.let { it + offset }
    if (weekNumber != null &&
        (weekNumber < 1 || (snapshot.semesterWeeks.isNotEmpty() && weekNumber !in snapshot.semesterWeeks))
    ) return null
    val parity = when {
        week == null && weekNumber != null -> if (weekNumber % 2 == 0) Week.EVEN else Week.ODD
        offset % 2 == 0 -> week
        week == Week.ODD -> Week.EVEN
        week == Week.EVEN -> Week.ODD
        else -> week
    }
    val lessons = days.firstOrNull { it.number == weekdayIndex(day) + 1 }?.lessons.orEmpty()
    return lessons.filter {
        (parity == null || it.week == Week.BOTH || it.week == parity) &&
            (weekNumber == null || it.weeks == null || weekNumber in it.weeks)
    }
}
