package com.aster.ondevice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.aster.ondevice.service.AsterService

class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val sender = messages[0].displayOriginatingAddress ?: "unknown"
        val body   = messages.joinToString("") { it.messageBody }
        Log.i("SmsBroadcastReceiver", "SMS from $sender: $body")
        AsterService.onSmsReceived(context, sender, body)
    }
}
