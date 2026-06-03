package com.aster.ondevice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aster.ondevice.service.AsterService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "Boot completed — starting AsterService")
        val svc = Intent(context, AsterService::class.java)
        context.startForegroundService(svc)
    }
}
