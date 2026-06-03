package com.aster.ondevice.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

private const val TAG = "AsterNotifService"

class AsterNotificationService : NotificationListenerService() {

    companion object {
        var instance: AsterNotificationService? = null
    }

    private val deduplicator = EventDeduplicator()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg   = sbn.packageName ?: return

        // Never react to our own notifications — prevents feedback loops
        // where agent-posted notifications re-trigger the agent.
        if (pkg == packageName) return

        val extra = sbn.notification.extras
        val title = extra.getCharSequence("android.title")?.toString() ?: ""
        val text  = extra.getCharSequence("android.text")?.toString()  ?: ""

        if (title.isBlank() && text.isBlank()) return
        if (sbn.isOngoing) return
        if (!deduplicator.shouldProcessNotification(pkg, title, text)) return

        Log.i(TAG, "Notification: $pkg — $title: $text")
        AsterService.onNotificationReceived(applicationContext, pkg, title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
