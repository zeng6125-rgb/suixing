package com.suixing.agent.service

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
import com.suixing.agent.R
import com.suixing.agent.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive (and visible) while a long agent task is running. */
class AgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "Suixing_agent"
        private const val NOTIFICATION_ID = 1201
        private const val GRANT_REQUEST = 1203

        /** Shown instead of the usual line when the console could not start for lack of the grant. */
        private const val CONSOLE_BLOCKED_TEXT = "悬浮窗权限未开启，悬浮球无法显示：点此开启"

        fun start(context: Context, text: String = "正在执行任务") {
            val intent = Intent(context, AgentForegroundService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            // Two conversations can run at once and each one stops the service when it ends, so
            // the stop is declined while any run is still live: otherwise the first run to
            // finish would take the notification, the process's foreground protection and the
            // console down while the other was still working.
            if (Core.engine.runsFlow.value.isNotEmpty()) return
            runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
        }

        private const val EXTRA_TEXT = "text"
    }

    /**
     * Settings live in DataStore, which only reads suspendingly, and `onStartCommand`
     * is a synchronous callback — so the console's gate is read on this scope. The
     * answer arrives milliseconds later, long before the run has anything to show.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "正在执行任务"
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(text))
        // Started after startForeground, not in onCreate: a process that is already
        // running a foreground service may start another one on Android 12+, and one
        // that is merely about to is not.
        // The console appears when either switch could show it: 常驻, or 「运行时显示」, which is
        // the one that needs it most — the drawer switch is off, the user is in another app, and
        // this run is the only reason a ball should be on their screen at all. With both off a run
        // is silent, as before.
        scope.launch {
            val settings = Core.settings.current()
            if (!settings.floatingConsole && !settings.consoleWhileRunning) return@launch
            // The console declines to start without the overlay grant, and a run that quietly
            // shows no ball is indistinguishable from a broken switch: say why in the
            // notification the run already has, with the one tap that fixes it. Any other
            // reason a start failed leaves the notification alone.
            if (FloatConsoleService.start(this@AgentForegroundService) ||
                FloatConsoleService.canDrawOverlays(this@AgentForegroundService)
            ) {
                return@launch
            }
            startForeground(NOTIFICATION_ID, buildNotification(CONSOLE_BLOCKED_TEXT, overlayGrantIntent()))
        }
        return START_NOT_STICKY
    }

    /**
     * The console lives exactly as long as the run does.
     *
     * A run is the only thing it can control, so it appears when one starts and goes
     * away with it — the user never has to remember to close it. [FloatConsoleService.start]
     * declines when the overlay permission has not been granted, and then there is
     * simply no console.
     */
    override fun onDestroy() {
        scope.cancel()
        FloatConsoleService.stop(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "任务执行", NotificationManager.IMPORTANCE_LOW).apply {
            description = "随行执行任务时的常驻通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, open: PendingIntent? = null): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spark)
            .setContentTitle("随行")
            .setContentText(text)
            .apply { open?.let { setContentIntent(it) } }
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** The system's overlay-grant page: the one tap that turns the switch back into a ball. */
    private fun overlayGrantIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        GRANT_REQUEST,
        FloatConsoleService.overlayPermissionIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
