package com.suixing.agent.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.suixing.agent.MainActivity
import com.suixing.agent.R
import kotlinx.coroutines.flow.MutableStateFlow

/** Result notifications use their own channel so ending a foreground service cannot remove them. */
internal object ChatCompletionNotifications {
    private const val CHANNEL_ID = "Suixing_completion"
    private const val NOTIFICATION_ID = 1401
    const val OPEN_CONVERSATION = "com.suixing.agent.extra.OPEN_CONVERSATION"
    val openRequested = MutableStateFlow<String?>(null)

    fun show(context: Context, chatId: String, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "对话完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "离开对话后，提醒回复已完成"
                },
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                data = Uri.fromParts("jagent-completion", chatId, null)
                putExtra(OPEN_CONVERSATION, chatId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spark)
            .setContentTitle(title.ifBlank { "JAgent" })
            .setContentText("回复已完成，点此查看")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(chatId, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked after the check above.
        }
    }

    fun cancel(context: Context, chatId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(chatId, NOTIFICATION_ID)
    }

    fun consumeIntent(intent: Intent) {
        val chatId = intent.getStringExtra(OPEN_CONVERSATION) ?: return
        intent.removeExtra(OPEN_CONVERSATION)
        openRequested.value = chatId
    }
}
