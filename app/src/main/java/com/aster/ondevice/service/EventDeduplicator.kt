package com.aster.ondevice.service

import java.util.concurrent.ConcurrentHashMap

/** Prevents duplicate processing of SMS + notification pairs for the same message. */
class EventDeduplicator {
    private val recentSms   = ConcurrentHashMap<String, Long>()
    private val recentNotif = ConcurrentHashMap<String, Long>()
    private val TTL = 30_000L   // 30 second dedup window

    fun recordSms(sender: String, body: String) {
        val key = "$sender:${body.take(40)}"
        recentSms[key] = System.currentTimeMillis()
    }

    fun shouldProcessNotification(pkg: String, title: String, text: String): Boolean {
        val key   = "$title:${text.take(40)}"
        val smsKey = "$title:${text.take(40)}"
        val now   = System.currentTimeMillis()
        // Drop if a matching SMS event fired within TTL
        recentSms.entries.removeIf { now - it.value > TTL }
        if (recentSms.keys.any { it.endsWith(key) }) return false
        // Dedup repeated notif
        recentNotif.entries.removeIf { now - it.value > TTL }
        val dup = recentNotif.containsKey("$pkg:$key")
        recentNotif["$pkg:$key"] = now
        return !dup
    }
}
