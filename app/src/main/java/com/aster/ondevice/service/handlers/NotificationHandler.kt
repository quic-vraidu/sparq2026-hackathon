package com.aster.ondevice.service.handlers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.aster.ondevice.R
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.AsterNotificationService
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    private val CHANNEL_ID = "aster_notif"

    override fun supportedActions() = listOf("read_notifications", "post_notification", "dismiss_notification")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "read_notifications"    -> readNotifications(command)
        "post_notification"     -> postNotification(command)
        "dismiss_notification"  -> dismissNotification(command)
        else -> CommandResult.err("Unknown: ${command.action}")
    }

    private fun dismissNotification(cmd: Command): CommandResult {
        val svc = AsterNotificationService.instance
            ?: return CommandResult.err("NotificationListenerService not connected.")
        val key = cmd.params["key"]?.jsonPrimitive?.content
        return if (key == null || key == "all") {
            svc.cancelAllNotifications()
            CommandResult.ok(mapOf("dismissed" to "all"))
        } else {
            svc.cancelNotification(key)
            CommandResult.ok(mapOf("dismissed" to key))
        }
    }

    private fun readNotifications(cmd: Command): CommandResult {
        val svc = AsterNotificationService.instance
            ?: return CommandResult.err("NotificationListenerService not connected. Enable in Accessibility settings.")
        val limit = cmd.params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        val notifs = svc.getActiveNotifications()?.take(limit)?.map { sbn ->
            mapOf(
                "key"   to sbn.key,
                "pkg"   to sbn.packageName,
                "title" to (sbn.notification.extras.getString("android.title") ?: ""),
                "text"  to (sbn.notification.extras.getString("android.text") ?: "")
            )
        } ?: emptyList()
        return CommandResult.ok(notifs)
    }

    private fun postNotification(cmd: Command): CommandResult {
        val title = cmd.params["title"]?.jsonPrimitive?.content ?: "Aster"
        val body  = cmd.params["body"]?.jsonPrimitive?.content  ?: ""
        val nm    = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Aster", NotificationManager.IMPORTANCE_DEFAULT))
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title).setContentText(body)
            .setSmallIcon(R.drawable.ic_notification).setAutoCancel(true).build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
        return CommandResult.ok(mapOf("posted" to true))
    }
}

