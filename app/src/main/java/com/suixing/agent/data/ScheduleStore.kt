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
    /**
     * When a one-shot was found to have missed its moment (0 = never missed).
     *
     * A phone that was off at the trigger time cannot fire it late without startling the user,
     * so [nextTriggerAfter] correctly refuses — but the board must not keep saying 「已启用」for
     * a reminder that will never ring again. See [ScheduleStore.expireMissed].
     */
    val missedAt: Long = 0L,
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

    /**
     * The scheduler service and any conversation can touch the board at the same moment, and
     * every write is the whole list: without the lock the later read-modify-write silently
     * drops the other one's reminder. A plain monitor rather than a coroutine mutex keeps
     * `read`/`write` non-suspending for their existing callers.
     */
    private val lock = Any()
    @Volatile private var snapshot: List<Schedule>? = null

    /** Notification construction must never cold-read JSON on the main thread. */
    fun peek(id: String): Schedule? = snapshot?.firstOrNull { it.id == id }

    fun read(): List<Schedule> = synchronized(lock) {
        snapshot?.let { return@synchronized it }
        if (!file.exists()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<Schedule>>(file.readText()) }.getOrElse {
                // A corrupt board must not read back as "no reminders at all": keep the bad file
                // next to the original name, or the next write would bury it for good.
                file.renameTo(File(file.parentFile, "schedules.corrupt.json"))
                emptyList()
            }
        }.also { snapshot = it }
    }

    fun write(items: List<Schedule>): List<Schedule> = synchronized(lock) {
        file.parentFile?.mkdirs()
        // Temp file then rename, as in ChatRepository: a kill mid-write would otherwise leave
        // a truncated board, and a truncated board reads back as "no reminders at all".
        val text = json.encodeToString(items)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            try {
                file.writeText(text)
            } finally {
                temp.delete()
            }
        }
        items.toList().also { snapshot = it }
    }

    fun find(id: String): Schedule? = read().firstOrNull { it.id == id }

    /**
     * Switches off one-shots whose moment passed while nobody was looking, stamping [Schedule.missedAt].
     *
     * They are the one case where `enabled = true` is a lie: the alarm never fired (the phone was
     * off, or the app was force-stopped) and [nextTriggerAfter] will not fire it now, so without
     * this the list would show a reminder that is permanently "已启用" and never rings. Firing it
     * late is deliberately not an option — a reminder that pops up hours after its moment is worse
     * than one that admits it was missed. Repeating kinds are untouched: they always have a next
     * moment, so they simply roll forward.
     */
    fun expireMissed(now: Long): List<Schedule> = synchronized(lock) {
        val board = read()
        var changed = false
        val next = board.map { schedule ->
            if (schedule.enabled && schedule.kind == SCHEDULE_AT && schedule.atMillis <= now) {
                changed = true
                schedule.copy(enabled = false, missedAt = now)
            } else {
                schedule
            }
        }
        if (changed) write(next) else board
    }

    /** Adds or replaces [schedule] by id, and returns the whole board. */
    fun upsert(schedule: Schedule): List<Schedule> = synchronized(lock) {
        write(read().filterNot { it.id == schedule.id } + schedule)
    }

    fun delete(id: String): List<Schedule> = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    /**
     * The soonest moment anything on the board is due, or 0 when nothing is.
     *
     * The scheduler needs exactly one alarm at a time — the nearest one — because a single
     * alarm that re-arms itself cannot drift out of step with the file the way several
     * alarms, each with its own idea of the list, can.
     *
     * 只有测试在用（ScheduleStoreTest）：调度服务自己走 `nextTriggerAfter` + `minByOrNull`
     * （SchedulerService.kt:211-215），因为它还要拿到 schedule 本体，好把 id 放进 PendingIntent。
     * 保留这个只返回时刻的入口，给将来只关心"下一次什么时候响"的调用方。
     */
    fun nextDueAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        read().filter { it.enabled }
            .mapNotNull { it.nextTriggerAfter(now, zone) }
            .minOrNull()
            ?: 0L
}
