package com.aster.ondevice.service.handlers

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

// AOSP Clock content provider — works on stock Android / Qualcomm reference devices.
// Falls back gracefully if the provider is absent (Samsung etc. use different URIs).
private const val AOSP_ALARM_URI = "content://com.android.deskclock/alarms"

@Singleton
class AlarmHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("get_alarms", "set_alarm", "dismiss_alarm", "delete_alarm")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "set_alarm" -> {
            val hour   = command.params["hour"]?.jsonPrimitive?.intOrNull   ?: return CommandResult.err("Missing hour")
            val minute = command.params["minute"]?.jsonPrimitive?.intOrNull ?: return CommandResult.err("Missing minute")
            val label  = command.params["message"]?.jsonPrimitive?.content ?: "Aster alarm"
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR,    hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                CommandResult.ok(mapOf("alarmSet" to true, "time" to "$hour:$minute"))
            } catch (e: Exception) { CommandResult.err("Set alarm failed: ${e.message}") }
        }
        "get_alarms" -> {
            try {
                val uri = android.net.Uri.parse(AOSP_ALARM_URI)
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor == null) {
                    CommandResult.ok(mapOf("note" to "Alarm provider not available on this device."))
                } else {
                    val alarms = mutableListOf<Map<String, Any>>()
                    cursor.use {
                        while (it.moveToNext()) {
                            val id      = it.getLong(it.getColumnIndexOrThrow("_id"))
                            val hour    = it.getInt(it.getColumnIndexOrThrow("hour"))
                            val minutes = it.getInt(it.getColumnIndexOrThrow("minutes"))
                            val enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) != 0
                            val label   = runCatching { it.getString(it.getColumnIndexOrThrow("label")) }.getOrNull() ?: ""
                            alarms.add(mapOf("id" to id, "hour" to hour, "minute" to minutes, "enabled" to enabled, "label" to label))
                        }
                    }
                    CommandResult.ok(mapOf("alarms" to alarms, "count" to alarms.size))
                }
            } catch (e: Exception) {
                CommandResult.ok(mapOf("note" to "Could not read alarms: ${e.message}"))
            }
        }
        "dismiss_alarm" -> CommandResult.ok(mapOf("note" to "Dismiss requires AlarmClock intent when alarm is ringing."))
        "delete_alarm"  -> {
            val alarmId = command.params["alarmId"]?.jsonPrimitive?.content
            if (alarmId == "all") {
                try {
                    val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    CommandResult.ok(mapOf("deleted" to "all"))
                } catch (e: Exception) { CommandResult.err("Delete all alarms failed: ${e.message}") }
            } else if (alarmId != null) {
                try {
                    val uri = android.net.Uri.parse("$AOSP_ALARM_URI/$alarmId")
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) CommandResult.ok(mapOf("deleted" to alarmId))
                    else CommandResult.err("Alarm $alarmId not found")
                } catch (e: Exception) { CommandResult.err("Delete alarm failed: ${e.message}") }
            } else {
                CommandResult.err("Missing alarmId. Use alarmId='all' to delete all alarms.")
            }
        }
        else -> CommandResult.err("Unknown: ${command.action}")
    }
}
