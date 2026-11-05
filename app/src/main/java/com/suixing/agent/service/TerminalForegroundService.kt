package com.suixing.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.suixing.agent.MainActivity
import com.suixing.agent.R
import com.suixing.agent.core.Core
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** User-visible interactive sessions; no dataSync time-limit workaround or automatic restart. */
class TerminalForegroundService : Service() {
    companion object {
        private const val CHANNEL = "Suixing_terminal"
        private const val ID = 1210
        private const val STOP = "com.suixing.agent.STOP_TERMINALS"
        const val OPEN_TERMINAL = "open_terminal"
        internal fun ensure(context: Context): String? = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TerminalForegroundService::class.java))
            null
        }.getOrElse { "后台保护未启动，离开应用后可能被系统停止：" + it.message }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        Core.init(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "交互式终端", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        startForeground(ID, notification(1))
        scope.launch {
            Core.terminals.entries.map { entries -> entries.count { it.info.phase == "running" } }
                .distinctUntilChanged().collect { count ->
                    if (count == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else getSystemService(NotificationManager::class.java).notify(ID, notification(count))
                }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) scope.launch { Core.terminals.stopAll() }
        return START_NOT_STICKY
    }
    private fun notification(count: Int): android.app.Notification {
        val open = PendingIntent.getActivity(this, ID,
            Intent(this, MainActivity::class.java).putExtra(OPEN_TERMINAL, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, ID,
            Intent(this, TerminalForegroundService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_spark)
            .setContentTitle("终端运行中").setContentText("$count 个会话 · 点击返回终端")
            .setContentIntent(open).addAction(0, "停止全部", stop).setOngoing(true).setSilent(true).build()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
