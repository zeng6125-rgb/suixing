package com.suixing.agent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val SCHEDULE_AT = "at"
const val SCHEDULE_EVERY = "every_seconds"
const val SCHEDULE_DAILY = "daily"

/** How the user wrote the time in `at` / `daily`: local wall clock, seconds optional. */
private val TIME_FORMATS = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
)

private val DAILY_FORMATS = listOf(
    DateTimeFormatter.ofPattern("HH:mm:ss"),
    DateTimeFormatter.ofPattern("HH:mm"),
)

/**
 * One reminder.
 *
 * [nextAt] is stored rather than recomputed on every launch because it is the answer to
 * 「下一次什么时候响」, and it has to survive the app being killed: the alarm itself lives in
 * the system's AlarmManager, and this is the record of what was asked of it.
 */
@Serializable
data class Schedule(
    val id: String,
    val title: String,
    val prompt: String,
    val kind: String,
    /** kind=at: the wall-clock moment it should fire, in epoch millis. */
    val atMillis: Long = 0L,
    /** kind=every_seconds: the interval. */
    val everySeconds: Long = 0L,
    /** kind=daily: local "HH:mm" or "HH:mm:ss". */
    val timeOfDay: String = "",
    val nextAt: Long = 0L,
    val enabled: Boolean = true,
)

/**
 * When this schedule should fire next, given [now]; null when it never will again.
 *
 * Pure on purpose: the arithmetic is the part that is easy to get wrong and impossible to
 * watch on a phone, so it is a function of the clock and nothing else.
 *
 * `every_seconds` counts from [now] — the moment the alarm actually fired — rather than
 * from a fixed grid. An alarm that arrives late (which `setAndAllowWhileIdle` explicitly
 * allows) therefore resets the cadence instead of immediately firing again to catch up.
 */
fun Schedule.nextTriggerAfter(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = when (kind) {
    SCHEDULE_AT -> atMillis.takeIf { it > now }

    SCHEDULE_EVERY -> if (everySeconds > 0L) now + everySeconds * 1000L else null

    SCHEDULE_DAILY -> runCatching {
        val time = parseTime(timeOfDay, DAILY_FORMATS) ?: return@runCatching null
        val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val today = moment.toLocalDate().atTime(time)
        val next = if (today.isAfter(moment)) today else today.plusDays(1)
        next.atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    else -> null
}

/** Parses a local time the user wrote, in either of [formats]; null when it is neither. */
internal fun parseTime(text: String, formats: List<DateTimeFormatter>): LocalTime? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    for (format in formats) {
        val parsed = runCatching { LocalTime.parse(trimmed, format) }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

/** Parses "yyyy-MM-dd HH:mm[:ss]" as a local moment; null when it is not that. */
internal fun parseLocalMoment(text: String, zone: ZoneId): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    for (format in TIME_FORMATS) {
        val parsed = runCatching { LocalDateTime.parse(trimmed, format) }.getOrNull()
        if (parsed != null) return parsed.atZone(zone).toInstant().toEpochMilli()
    }
    return null
}

/** `filesDir/schedules.json`: every reminder the user has set up. */
class ScheduleStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun read(): List<Schedule> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<Schedule>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun write(items: List<Schedule>): List<Schedule> {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(items))
        return items
    }

    fun find(id: String): Schedule? = read().firstOrNull { it.id == id }

    /** Adds or replaces [schedule] by id, and returns the whole board. */
    fun upsert(schedule: Schedule): List<Schedule> {
        val current = read().filterNot { it.id == schedule.id }
        return write(current + schedule)
    }

    fun delete(id: String): List<Schedule> = write(read().filterNot { it.id == id })

    /**
     * The soonest moment anything on the board is due, or 0 when nothing is.
     *
     * The scheduler needs exactly one alarm at a time — the nearest one — because a single
     * alarm that re-arms itself cannot drift out of step with the file the way several
     * alarms, each with its own idea of the list, can.
     */
    fun nextDueAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        read().filter { it.enabled }
            .mapNotNull { it.nextTriggerAfter(now, zone) }
            .minOrNull()
            ?: 0L
}
