package com.aster.ondevice.service.handlers

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG          = "ScreenHandler"
private const val DEFAULT_HOLD = 30_000L   // hold wake lock 30 s by default

/**
 * Turns the screen on from a background service so accessibility tools can
 * interact with foreground apps (e.g. send a WhatsApp message) without the
 * user having to manually unlock the phone first.
 *
 * Two tools exposed:
 *   wake_screen   — turn screen on, optionally dismiss keyguard (no-PIN devices)
 *   is_screen_on  — check whether screen is currently interactive
 */
@Singleton
class ScreenHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun supportedActions() = listOf("wake_screen", "is_screen_on")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "wake_screen"  -> wakeScreen(command)
        "is_screen_on" -> isScreenOn()
        else           -> CommandResult.err("Unknown: ${command.action}")
    }

    // ── wake_screen ───────────────────────────────────────────────────────────

    private fun wakeScreen(cmd: Command): CommandResult {
        val holdSeconds = cmd.params["holdSeconds"]?.jsonPrimitive?.intOrNull ?: 30
        val holdMs      = holdSeconds.coerceIn(1, 300) * 1_000L

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        val wasOn = pm.isInteractive

        if (!wasOn) {
            // SCREEN_BRIGHT_WAKE_LOCK is deprecated since API 17 but remains the only
            // reliable way to turn the screen on from a background service.
            // ACQUIRE_CAUSES_WAKEUP is the flag that actually powers the display on.
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Aster::ScreenWake"
            )
            wl.setReferenceCounted(false)
            wl.acquire(holdMs)

            // Release old lock if any, then track the new one
            releaseLock()
            wakeLock = wl

            Log.i(TAG, "Screen woken, holding for ${holdMs / 1000}s")
        } else {
            // Screen already on — just extend / refresh the hold
            releaseLock()
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "Aster::ScreenKeepOn"
            )
            wl.setReferenceCounted(false)
            wl.acquire(holdMs)
            wakeLock = wl
            Log.i(TAG, "Screen already on, extended hold to ${holdMs / 1000}s")
        }

        // Try to dismiss the keyguard if it's not PIN/pattern protected
        val keyguardLocked   = km.isKeyguardLocked
        val keyguardSecure   = km.isKeyguardSecure   // true = PIN/pattern/biometric set
        var keyguardDismissed = false

        if (keyguardLocked && !keyguardSecure) {
            try {
                @Suppress("DEPRECATION")
                km.newKeyguardLock("Aster::KeyguardLock").disableKeyguard()
                keyguardDismissed = true
                Log.i(TAG, "Keyguard dismissed (no PIN)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not dismiss keyguard: ${e.message}")
            }
        }

        return CommandResult.ok(mapOf(
            "screenWasOn"       to wasOn,
            "screenNowOn"       to true,
            "holdSeconds"       to holdSeconds,
            "keyguardLocked"    to keyguardLocked,
            "keyguardSecure"    to keyguardSecure,
            "keyguardDismissed" to keyguardDismissed,
            "note"              to if (keyguardLocked && keyguardSecure)
                "Screen is on but PIN/pattern lock is active — user must unlock manually"
            else
                "Screen is on and ready for UI interaction"
        ))
    }

    // ── is_screen_on ──────────────────────────────────────────────────────────

    private fun isScreenOn(): CommandResult {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return CommandResult.ok(mapOf(
            "interactive"    to pm.isInteractive,
            "keyguardLocked" to km.isKeyguardLocked,
            "keyguardSecure" to km.isKeyguardSecure
        ))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Explicitly release the wake lock early (e.g. after the UI task is done).
     * Called automatically when a new wake_screen request comes in or from AsterService.
     */
    fun releaseLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
}
