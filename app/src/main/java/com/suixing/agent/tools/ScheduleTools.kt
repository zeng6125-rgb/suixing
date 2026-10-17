package com.suixing.agent.tools

import android.content.Context
import com.suixing.agent.data.SCHEDULE_AT
import com.suixing.agent.data.SCHEDULE_DAILY
import com.suixing.agent.data.SCHEDULE_EVERY
import com.suixing.agent.data.Schedule
import com.suixing.agent.data.ScheduleStore
import com.suixing.agent.data.nextTriggerAfter
import com.suixing.agent.data.parseLocalMoment
import com.suixing.agent.data.parseTime
import com.suixing.agent.service.SchedulerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Reminders: a time, and something for the agent to do when it arrives.
 *
 * This is the one part of the app that acts with nobody watching, so the tool that creates
 * a reminder says out loud when it will next fire — a schedule the user cannot picture is a
 * schedule they cannot trust.
 */
class ScheduleTools(
    private val context: Context,
    private val schedules: ScheduleStore,
) : ToolGroup() {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    override val group: String = "定时提醒"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "schedule_create",
            "Create a timed reminder: when it fires, 随行 runs the prompt in a new conversation and " +
                "posts a notification. Give exactly one timing argument: at (a point in time) / " +
                "every_seconds (an interval) / daily (a time of day).",
            Schema.objectOf(
                properties = listOf(
                    "title" to Schema.string("Name of the reminder; also the notification and conversation title"),
                    "prompt" to Schema.string(
                        "What 随行 should do when it fires, e.g. \"read today's to-dos out loud\"",
                    ),
                    "at" to Schema.string("One-shot: local time \"yyyy-MM-dd HH:mm\", e.g. 2026-10-09 07:30"),
                    "every_seconds" to Schema.integer("Repeating: interval in seconds, minimum 60", minimum = 60),
                    "daily" to Schema.string("Daily: local time \"HH:mm\", e.g. 07:30"),
                ),
                required = listOf("title", "prompt"),
            ),
        ),
        ToolSpec(
            "schedule_list",
            "List every timed reminder: id, name, timing and the next trigger time.",
            Schema.objectOf(properties = emptyList()),
        ),
        ToolSpec(
            "schedule_update",
            "Change an existing reminder: the name only, the prompt only, or a new timing argument " +
                "(again exactly one).",
            Schema.objectOf(
                properties = listOf(
                    "id" to Schema.string("The id from schedule_list"),
                    "title" to Schema.string("New name; leave empty to keep the current one"),
                    "prompt" to Schema.string("New instruction; leave empty to keep the current one"),
                    "at" to Schema.string("Switch to a one-shot time \"yyyy-MM-dd HH:mm\""),
                    "every_seconds" to Schema.integer("Switch to a fixed interval, minimum 60", minimum = 60),
                    "daily" to Schema.string("Switch to daily \"HH:mm\""),
                ),
                required = listOf("id"),
            ),
        ),
        ToolSpec(
            "schedule_delete",
            "Delete a timed reminder and cancel its alarm.",
            Schema.objectOf(
                properties = listOf("id" to Schema.string("The id from schedule_list")),
                required = listOf("id"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "schedule_create" -> create(args)
        "schedule_list" -> list()
        "schedule_update" -> update(args)
        "schedule_delete" -> delete(args)
        else -> ToolResult.failure("未知工具：$name")
    }

    private suspend fun create(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val title = args.str("title")?.trim().orEmpty()
        if (title.isEmpty()) return@withContext ToolResult.failure("缺少参数 title")
        val prompt = args.str("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return@withContext ToolResult.failure("缺少参数 prompt（到点要让随行做什么）")

        val timing = readTiming(args) ?: return@withContext timingError(args)
        val now = System.currentTimeMillis()
        val schedule = Schedule(
            id = UUID.randomUUID().toString().substring(0, 8),
            title = title,
            prompt = prompt,
            kind = timing.kind,
            atMillis = timing.atMillis,
            everySeconds = timing.everySeconds,
            timeOfDay = timing.timeOfDay,
        ).let { it.copy(nextAt = it.nextTriggerAfter(now, zone) ?: 0L) }
        if (schedule.nextAt == 0L) {
            return@withContext ToolResult.failure("这个时间已经过去了：${args.str("at").orEmpty()}。请给一个将来的时刻。")
        }
        schedules.upsert(schedule)
        SchedulerService.arm(context)
        ToolResult.success(
            "已建提醒 ${schedule.id}「${schedule.title}」，下一次 ${format(schedule.nextAt)}（${describe(schedule)}）。",
        )
    }

    private suspend fun list(): ToolResult = withContext(Dispatchers.IO) {
        val all = schedules.read()
        if (all.isEmpty()) return@withContext ToolResult.success("还没有定时提醒。")
        val text = all.joinToString("\n") { schedule ->
            val next = schedule.nextAt.takeIf { it > 0L }?.let { format(it) } ?: "不会触发"
            // 一条错过触发时间的一次性提醒会被 ScheduleStore.expireMissed 关掉：只说「不会触发」
            // 会让模型分不清是用户暂停了还是已经过期，所以这里把状态说全。
            val state = when {
                schedule.missedAt > 0L && !schedule.enabled ->
                    " · 已过期（错过了触发时间，需要的话重新建一条）"

                !schedule.enabled -> " · 已暂停"
                else -> " · 下一次 $next"
            }
            "- ${schedule.id}「${schedule.title}」${describe(schedule)}$state · 到点执行：${schedule.prompt}"
        }
        ToolResult.success("共 ${all.size} 个定时提醒：\n$text")
    }

    private suspend fun update(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = args.str("id")?.trim().orEmpty()
        if (id.isEmpty()) return@withContext ToolResult.failure("缺少参数 id")
        val existing = schedules.find(id)
            ?: return@withContext ToolResult.failure("没有这个提醒：$id。先用 schedule_list 看有哪些。")

        val title = args.str("title")?.trim().orEmpty().ifBlank { existing.title }
        val prompt = args.str("prompt")?.trim().orEmpty().ifBlank { existing.prompt }
        // "no timing given" means keep the old one; a timing that was given but is wrong is an
        // error, not a silent no-op — otherwise a typo'd time looks like a successful update.
        val given = TIMING_KEYS.filter { !args.str(it).isNullOrBlank() }
        val timing = readTiming(args)
        if (given.isNotEmpty() && timing == null) return@withContext timingError(args)
        val updated = if (timing == null) {
            existing.copy(title = title, prompt = prompt)
        } else {
            existing.copy(
                title = title,
                prompt = prompt,
                kind = timing.kind,
                atMillis = timing.atMillis,
                everySeconds = timing.everySeconds,
                timeOfDay = timing.timeOfDay,
            )
        }
        val now = System.currentTimeMillis()
        val armed = updated.copy(
            nextAt = updated.nextTriggerAfter(now, zone) ?: 0L,
            // 给新时间 = 重新排上：一条因为错过触发时间而被关掉的提醒，改到未来某点后必须重新生效，
            // 否则回执里的「下一次」是句谎话——它算出了时间却仍然不会响。用户自己暂停的不动。
            enabled = if (timing != null && updated.missedAt > 0L) true else updated.enabled,
            missedAt = if (timing != null && updated.missedAt > 0L) 0L else updated.missedAt,
        )
        if (timing != null && armed.nextAt == 0L) {
            return@withContext ToolResult.failure("这个时间已经过去了，提醒没有改动。")
        }
        schedules.upsert(armed)
        SchedulerService.arm(context)
        ToolResult.success(
            "已更新 ${armed.id}「${armed.title}」，下一次 " +
                (armed.nextAt.takeIf { it > 0L }?.let { format(it) } ?: "不会触发") + "。",
        )
    }

    private suspend fun delete(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = args.str("id")?.trim().orEmpty()
        if (id.isEmpty()) return@withContext ToolResult.failure("缺少参数 id")
        val existing = schedules.find(id)
            ?: return@withContext ToolResult.failure("没有这个提醒：$id。先用 schedule_list 看有哪些。")
        schedules.delete(id)
        SchedulerService.arm(context)
        ToolResult.success("已删除提醒「${existing.title}」（$id）。")
    }

    private class Timing(
        val kind: String,
        val atMillis: Long = 0L,
        val everySeconds: Long = 0L,
        val timeOfDay: String = "",
    )

    /** The one timing parameter that was given, or null when it was not exactly one. */
    private fun readTiming(args: JsonObject): Timing? {
        val given = TIMING_KEYS.filter { !args.str(it).isNullOrBlank() }
        if (given.size != 1) return null
        return when (given.first()) {
            "at" -> {
                val raw = args.str("at").orEmpty()
                val millis = parseLocalMoment(raw, zone) ?: return null
                Timing(SCHEDULE_AT, atMillis = millis)
            }

            "every_seconds" -> {
                val seconds = args.int("every_seconds", 0)
                if (seconds < 60) return null
                Timing(SCHEDULE_EVERY, everySeconds = seconds.toLong())
            }

            else -> {
                val raw = args.str("daily").orEmpty()
                if (parseTime(raw, DAILY_TIME_FORMATS) == null) return null
                Timing(SCHEDULE_DAILY, timeOfDay = raw.trim())
            }
        }
    }

    private fun timingError(args: JsonObject): ToolResult {
        val given = TIMING_KEYS.filter { !args.str(it).isNullOrBlank() }
        return when {
            given.isEmpty() -> ToolResult.failure(
                "缺少时机参数，三选一：at=\"yyyy-MM-dd HH:mm\"、every_seconds=秒数（≥60）、daily=\"HH:mm\"。",
            )

            given.size > 1 -> ToolResult.failure("时机参数只能给一个，现在给了 ${given.joinToString("、")}。")

            else -> ToolResult.failure(
                "${given.first()} 的值不对：「${args.str(given.first())}」。" +
                    "at 要 \"yyyy-MM-dd HH:mm\"，every_seconds 要 ≥60 的秒数，daily 要 \"HH:mm\"。",
            )
        }
    }

    // 同一个提醒在两处说成两句话，用户就得自己对齐它们：定时任务页与工具回执共用下面那两个
    // 顶层函数，这里只把实例的时区递过去。
    private fun describe(schedule: Schedule): String = describeSchedule(schedule, zone)

    private fun format(millis: Long): String = formatScheduleMoment(millis, zone)

    private companion object {
        val TIMING_KEYS = listOf("at", "every_seconds", "daily")

        val DAILY_TIME_FORMATS = listOf(
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm"),
        )
    }
}

/**
 * 「每天 07:30」/「每 300 秒」/「一次性 2026-10-12 07:30」——一行说清这条提醒什么时候响。
 *
 * 定时任务页（[com.suixing.agent.ui.schedules.SchedulesScreen]）和工具回执读的是同一份。
 */
fun describeSchedule(
    schedule: Schedule,
    zone: ZoneId = ZoneId.systemDefault(),
): String = when (schedule.kind) {
    SCHEDULE_AT -> "一次性 ${formatScheduleMoment(schedule.atMillis, zone)}"
    SCHEDULE_EVERY -> "每 ${schedule.everySeconds} 秒"
    SCHEDULE_DAILY -> "每天 ${schedule.timeOfDay}"
    else -> schedule.kind
}

/** 本地时区的「2026-10-12 07:30」。 */
fun formatScheduleMoment(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    MOMENT_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))

private val MOMENT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
