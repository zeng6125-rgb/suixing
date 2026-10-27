package com.suixing.agent.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.suixing.agent.MainActivity
import com.suixing.agent.R
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.core.Core
import com.suixing.agent.data.Roles
import com.suixing.agent.data.Schedule
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.nextTriggerAfter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Reminders: the only part of the app that starts work with nobody watching.
 *
 * There is one alarm at a time — the nearest one on the board — and it re-arms the next when
 * it fires. One alarm cannot drift out of step with `schedules.json` the way a fleet of them,
 * each holding its own copy of the list, eventually would.
 *
 * The alarm is delivered to this service rather than to a broadcast receiver because the work
 * it starts (a model run) needs a process that stays alive, and since Android 12 an app in the
 * background may not *start* a foreground service — an inexact alarm is not one of the
 * exemptions. Going foreground here first makes starting the agent's own foreground service
 * legal again, because foreground → foreground is allowed.
 */
class SchedulerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
        if (intent?.action == ACTION_FIRE && id.isNotEmpty()) {
            // Runs are keyed by conversation now, so a reminder always gets its own run in
            // its own fresh conversation: nothing else on the engine can make it busy.
            startForeground(REMINDER_ID, buildNotification(id))
            Core.appScope.launch {
                // A run that fails throws: the engine publishes Failed and rethrows, and this
                // scope has no exception handler, so an unguarded call would kill the process
                // before the reminder was put back on the board — one bad reminder would then
                // silence every later one until the app was opened again.
                runCatching { fire(id) }
                // Deliberately outside the guard above: these two have to run even after a
                // failure, or a foreground notification would be left with nobody to take it
                // down and the reminder would keep its foreground flag forever.
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        } else {
            Core.appScope.launch {
                arm(this@SchedulerService)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun fire(id: String) {
        val schedule = Core.schedules.find(id) ?: return
        getSystemService(NotificationManager::class.java)?.notify(REMINDER_ID, buildNotification(id))
        val question = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = Roles.USER,
            text = schedule.prompt,
        )
        // The conversation is created before the run, and saved even when the run is skipped:
        // a reminder that fired is something the user should be able to see and act on.
        val conversation = Core.chats.create(schedule.title)
        Core.chats.save(conversation.copy(messages = listOf(question)))

        AgentForegroundService.start(this, "定时任务：${schedule.title}")
        // A reminder has no chat screen to collect the run's events, and a run stopped from the
        // console never returns a transcript: the messages the engine emits on its way out are
        // the only copy of the answer there is.
        val emitted = mutableListOf<StoredMessage>()
        try {
            val produced = Core.engine.startRun(
                chatId = conversation.id,
                title = schedule.title,
                history = listOf(question),
                settings = Core.settings.current(),
                registry = Core.tools,
                capabilities = Core.capabilities,
                skills = Core.skills,
                memory = Core.memory,
                profile = Core.profile,
                gate = Core.gate,
                onEvent = { event ->
                    when (event) {
                        is AgentEvent.AssistantMessage -> emitted += event.message
                        is AgentEvent.ToolMessage -> emitted += event.message
                        else -> Unit
                    }
                },
            )
            // Invariant: `emitted` is `produced` in the same order, plus the messages the engine
            // emits while ending a stopped run — each `produced +=` in AgentEngine is followed
            // immediately by its event. A new `produced +=` without an event would break that,
            // so a short `emitted` falls back to `produced` instead of saving a transcript with
            // the wrong messages in it.
            val transcript = if (emitted.size >= produced.size) {
                produced + emitted.drop(produced.size)
            } else {
                produced
            }
            // NonCancellable because a run stopped from the floating console cancels this
            // coroutine, and a cancelled coroutine refuses to suspend: without it the save
            // would be skipped and the reminder's conversation would keep only the question —
            // the answer the user waited for would be gone with nothing to show it existed.
            withContext(NonCancellable) {
                Core.chats.save(conversation.copy(messages = listOf(question) + transcript))
            }
        } catch (cancellation: CancellationException) {
            // The engine rethrows when the coroutine itself was cancelled — a stop that landed
            // while a tool was running — so `produced` was never assigned and what it emitted
            // on the way out is all there is. Saved under NonCancellable for the same reason as
            // above, then rethrown: swallowing the cancellation here would turn a stop into a
            // normal ending and change what the caller's finally is allowed to assume.
            withContext(NonCancellable) {
                Core.chats.save(conversation.copy(messages = listOf(question) + emitted))
            }
            throw cancellation
        } finally {
            // A reminder that fired is done whether or not its run worked: rescheduling in a
            // finally is what keeps one failure from taking the whole board down with it. A
            // one-shot is deleted, a repeating one is due again from now.
            val next = schedule.nextTriggerAfter(System.currentTimeMillis())
            if (next == null) Core.schedules.delete(schedule.id) else Core.schedules.upsert(schedule.copy(nextAt = next))
            arm(this)
            // The agent's foreground service is not tied to this one, so it has to be taken
            // down here as well — otherwise its ongoing notification would outlive the run.
            // It declines while another conversation is still running.
            AgentForegroundService.stop(this)
        }
    }

    private fun buildNotification(id: String): Notification {
        val schedule: Schedule? = Core.schedules.peek(id)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "随行正在执行这条提醒"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spark)
            .setContentTitle(schedule?.title ?: "定时提醒")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "定时提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "到点提醒，以及随行执行提醒内容时的进展"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_FIRE = "com.suixing.agent.action.SCHEDULE_FIRE"
        private const val ACTION_ARM = "com.suixing.agent.action.SCHEDULE_ARM"
        private const val EXTRA_ID = "id"
        private const val CHANNEL_ID = "Suixing_schedule"
        private const val REMINDER_ID = 1301
        private const val FIRE_REQUEST = 1302

        /**
         * Points the single alarm at whatever is due soonest.
         *
         * Called after every change to the board, and once when the app starts. It reads the
         * file rather than trusting a cached time, because the file is the record the user
         * edited.
         *
         * `setAndAllowWhileIdle` is the baseline rather than an exact alarm on purpose: an
         * exact alarm needs `SCHEDULE_EXACT_ALARM`, which the user has to grant in system
         * settings, and a reminder that refuses to exist until they do is worse than one that
         * may arrive a minute late. When the app is allowed exact alarms it uses them, so the
         * usual case is precise; otherwise the system batches the wake-up, which is why the
         * window is described as ±minutes.
         */
        fun arm(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val now = System.currentTimeMillis()
            // Swept before the alarm is aimed, and in the one place every path already goes
            // through (boot, clock change, reinstall, every edit, and after each fire): a one-shot
            // whose moment passed while the phone was off stops claiming to be armed, and no
            // separate cleanup pass has to remember to look for it.
            val next = Core.schedules.expireMissed(now)
                .filter { it.enabled }
                .mapNotNull { schedule -> schedule.nextTriggerAfter(now)?.let { it to schedule } }
                .minByOrNull { it.first }
            val fire = firePending(context, next?.second?.id.orEmpty())
            // Same request code and same component: this replaces the previous alarm instead
            // of leaving a second one behind, and cancelling it is what clears the board.
            alarm.cancel(fire)
            if (next == null) return
            if (canScheduleExact(alarm)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.first, fire)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.first, fire)
            }
        }

        private fun firePending(context: Context, id: String): PendingIntent = PendingIntent.getForegroundService(
            context,
            FIRE_REQUEST,
            Intent(context, SchedulerService::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun canScheduleExact(alarm: AlarmManager): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()

        /** Asks the service to re-read the board, e.g. after the app was reinstalled or rebooted. */
        fun refresh(context: Context) {
            context.startService(Intent(context, SchedulerService::class.java).setAction(ACTION_ARM))
        }
    }
}
