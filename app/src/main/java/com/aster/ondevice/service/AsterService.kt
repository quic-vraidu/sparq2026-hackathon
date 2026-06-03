package com.aster.ondevice.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aster.ondevice.R
import com.aster.ondevice.agent.OnDeviceAgent
import com.aster.ondevice.data.SettingsDataStore
import com.aster.ondevice.llm.LlamaConfig
import com.aster.ondevice.llm.LlmEngine
import com.aster.ondevice.service.handlers.ScreenHandler
import com.aster.ondevice.service.handlers.SmsHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

private const val TAG          = "AsterService"
private const val CHANNEL_ID   = "aster_service"
private const val NOTIF_ID     = 1001
private const val WAKE_TIMEOUT = 10 * 60 * 1000L   // 10 min

@AndroidEntryPoint
class AsterService : Service() {

    @Inject lateinit var llm:           LlmEngine
    @Inject lateinit var agent:         OnDeviceAgent
    @Inject lateinit var settings:      SettingsDataStore
    @Inject lateinit var screenHandler: ScreenHandler
    @Inject lateinit var smsHandler:    SmsHandler

    private val scope       = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock:   PowerManager.WakeLock?  = null
    private var wifiLock:   WifiManager.WifiLock?   = null

    // Called by SmsBroadcastReceiver and AsterNotificationService
    companion object {
        var instance: AsterService? = null

        fun onSmsReceived(context: Context, sender: String, body: String) {
            val service = instance ?: return
            if (SmsPromoDetector.isPromo(sender, body)) {
                // Delete silently — wait 1.5s for the default SMS app to store it first
                service.scope.launch {
                    kotlinx.coroutines.delay(1500)
                    val deleted = service.smsHandler.findAndDeleteBySender(sender, windowMs = 10_000)
                    Log.i(TAG, "Auto-deleted promo SMS from $sender (rows=$deleted)")
                }
                return  // don't pass promo to agent
            }
            service.handleEvent("SMS from $sender: $body")
        }
        fun onNotificationReceived(context: Context, app: String, title: String, text: String) {
            instance?.handleEvent("Notification from $app — $title: $text")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        acquireLocks()
        scope.launch { loadModel() }
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        llm.free()
        screenHandler.releaseLock()
        releaseLocks()
        scope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private suspend fun loadModel() {
        val path = settings.getModelPath()
        if (path.isNullOrBlank()) {
            updateNotification("No model selected — open app to configure")
            return
        }
        updateNotification("Loading model…")
        val ok = llm.load(path, LlamaConfig(
            nCtx          = settings.getNCtx(),
            nThreads      = settings.getNThreads(),
            nGpuLayers    = settings.getNGpuLayers(),
            chatTemplate  = settings.getChatTemplate(),
            temperature   = settings.getTemperature(),
            topP          = settings.getTopP(),
            topK          = settings.getTopK(),
            repeatPenalty = settings.getRepeatPenalty(),
        ))
        updateNotification(if (ok) "Ready — model loaded" else "Model load failed")
    }

    // ── Event handling (from notification/SMS listeners) ─────────────────────

    fun handleEvent(event: String) {
        if (!llm.isLoaded()) { Log.w(TAG, "Model not loaded, skipping event"); return }
        scope.launch {
            Log.i(TAG, "Handling event: ${event.take(80)}")

            // Wake the screen so accessibility tools can interact with foreground apps.
            // The agent may later call wake_screen itself with a custom hold time,
            // but this ensures the screen is on before the first tool call.
            ensureScreenOn()

            agent.process(event)
        }
    }

    /**
     * Wake the screen if it is currently off.
     * Uses PARTIAL_WAKE_LOCK (CPU only) for background processing — the
     * SCREEN_BRIGHT_WAKE_LOCK (display on) is in ScreenHandler and kept
     * for the duration of UI interaction.
     */
    private fun ensureScreenOn() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return   // already on — nothing to do

        Log.i(TAG, "Screen is off — waking for event processing")
        try {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Aster::EventWake"
            )
            wl.acquire(60_000L)   // hold up to 60 s; ScreenHandler extends if needed
            // Release the event-wake lock after 60 s automatically via timeout above.
            // ScreenHandler's wake lock will take over if the agent calls wake_screen.
        } catch (e: Exception) {
            Log.w(TAG, "ensureScreenOn failed: ${e.message}")
        }

        // Also attempt keyguard dismiss for non-secured devices
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked && !km.isKeyguardSecure) {
                @Suppress("DEPRECATION")
                km.newKeyguardLock("Aster::EventKeyguard").disableKeyguard()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keyguard dismiss failed: ${e.message}")
        }
    }

    // ── Locks ─────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aster::ServiceLock").apply {
            acquire(WAKE_TIMEOUT)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Aster::WifiLock").apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, "Aster AI", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aster On-Device AI")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
