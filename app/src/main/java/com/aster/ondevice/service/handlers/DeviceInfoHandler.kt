package com.aster.ondevice.service.handlers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class DeviceInfoHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("get_device_info", "get_battery", "get_location")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "get_device_info" -> getDeviceInfo()
        "get_battery"     -> getBattery()
        "get_location"    -> getLocation()
        else              -> CommandResult.err("Unknown: ${command.action}")
    }

    private fun getDeviceInfo(): CommandResult {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val stat = StatFs(android.os.Environment.getDataDirectory().path)
        return CommandResult.ok(mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model"        to Build.MODEL,
            "android"      to Build.VERSION.RELEASE,
            "sdk"          to Build.VERSION.SDK_INT,
            "totalRamMB"   to (memInfo.totalMem / 1_048_576),
            "availRamMB"   to (memInfo.availMem / 1_048_576),
            "totalStorageGB" to (stat.totalBytes / 1_073_741_824),
            "freeStorageGB"  to (stat.availableBytes / 1_073_741_824),
            "abis"         to Build.SUPPORTED_ABIS.toList()
        ))
    }

    private fun getBattery(): CommandResult {
        // ACTION_BATTERY_CHANGED is a sticky broadcast — registerReceiver with null
        // receiver returns the last broadcast immediately, no listener needed.
        // This matches exactly what Android's system UI shows.
        // NOTE: getIntProperty(BATTERY_PROPERTY_CAPACITY) reads the raw hardware
        // fuel-gauge sysfs value which can differ from the calibrated system level.
        val intent  = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level   = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale   = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct     = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status  = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        Log.i("DeviceInfoHandler", "battery: level=$level scale=$scale pct=$pct status=$status charging=$charging")
        return CommandResult.ok(mapOf("level" to pct, "charging" to charging))
    }

    private suspend fun getLocation(): CommandResult = suspendCancellableCoroutine { cont ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) cont.resume(CommandResult.ok(mapOf("lat" to loc.latitude, "lng" to loc.longitude, "accuracy" to loc.accuracy)))
                else cont.resume(CommandResult.err("Location unavailable"))
            }.addOnFailureListener { e -> cont.resume(CommandResult.err(e.message ?: "Location failed")) }
        } catch (e: SecurityException) {
            cont.resume(CommandResult.err("Location permission not granted"))
        }
    }
}
