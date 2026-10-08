package com.suixing.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.suixing.agent.R

/** Keeps the process alive (and visible) while a long agent task is running. */
class AgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "Suixing_agent"
        private const val NOTIFICATION_ID = 1201

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
            runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
        }

        private const val EXTRA_TEXT = "text"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "正在执行任务"
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(text))
        return START_NOT_STICKY
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

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spark)
            .setContentTitle("随行")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
