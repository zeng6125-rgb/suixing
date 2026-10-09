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
import com.suixing.agent.agent.RunState
import com.suixing.agent.core.Core
import com.suixing.agent.data.Roles
import com.suixing.agent.data.Schedule
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.nextTriggerAfter
import kotlinx.coroutines.launch
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
                fire(id)
                // The reminder stays in the shade as an ordinary, dismissible notification;
                // only the foreground flag goes away.
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
        val produced = Core.engine.startRun(
            chatId = conversation.id,
            title = schedule.title,
            history = listOf(question),
            settings = Core.settings.current(),
            registry = Core.tools,
            capabilities = Core.capabilities,
            skills = Core.skills,
            memory = Core.memory,
            gate = Core.gate,
            onEvent = {},
        )
        Core.chats.save(conversation.copy(messages = listOf(question) + produced))

        // A one-shot reminder that has fired is done; a repeating one is due again from now.
        val next = schedule.nextTriggerAfter(System.currentTimeMillis())
        if (next == null) Core.schedules.delete(schedule.id) else Core.schedules.upsert(schedule.copy(nextAt = next))
        arm(this)
    }

    private fun buildNotification(id: String): Notification {
        val schedule: Schedule? = Core.schedules.find(id)
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
            val next = Core.schedules.read()
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
