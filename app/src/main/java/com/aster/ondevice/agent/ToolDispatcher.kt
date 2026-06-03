package com.aster.ondevice.agent

import android.content.Context
import android.util.Log
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.service.handlers.*import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ToolDispatcher"

/**
 * Routes tool call requests from the agent loop to the concrete Android handlers.
 * Adding a new tool = add one entry to the when() block.
 */
@Singleton
class ToolDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsHandler:           SmsHandler,
    private val cameraHandler:         CameraHandler,
    private val accessibilityHandler:  AccessibilityHandler,
    private val fileSystemHandler:     FileSystemHandler,
    private val notificationHandler:   NotificationHandler,
    private val shellHandler:          ShellHandler,
    private val deviceInfoHandler:     DeviceInfoHandler,
    private val mediaHandler:          MediaHandler,
    private val volumeHandler:         VolumeHandler,
    private val alarmHandler:          AlarmHandler,
    private val contactHandler:        ContactHandler,
    private val clipboardHandler:      ClipboardHandler,
    private val overlayHandler:        OverlayHandler,
    private val packageHandler:        PackageHandler,
    private val storageHandler:        StorageHandler,
    private val intentHandler:         IntentHandler,
    private val screenHandler:         ScreenHandler,
) {
    /**
     * Execute a tool by name with a map of arguments.
     * Returns JSON string of the result (success or error).
     */
    suspend fun execute(toolName: String, args: Map<String, JsonElement>): String {
        Log.i(TAG, "execute: $toolName  args=$args")
        return try {
            val cmd = Command(type = "command", id = "agent", action = toolName, params = args)
            val result = when (toolName) {
                // Communication
                "read_sms"              -> smsHandler.handle(cmd)
                "send_sms"              -> smsHandler.handle(cmd)
                "make_call"             -> smsHandler.handle(cmd)
                "make_call_with_voice"  -> smsHandler.handle(cmd)
                "delete_sms"            -> smsHandler.handle(cmd)
                "search_contacts"       -> contactHandler.handle(cmd)

                // Notifications
                "read_notifications"    -> notificationHandler.handle(cmd)
                "post_notification"     -> notificationHandler.handle(cmd)
                "dismiss_notification"  -> notificationHandler.handle(cmd)

                // Screen
                "take_screenshot"       -> accessibilityHandler.handle(cmd)
                "get_screen_hierarchy"  -> accessibilityHandler.handle(cmd)
                "input_gesture"         -> accessibilityHandler.handle(cmd)
                "input_text"            -> accessibilityHandler.handle(cmd)
                "find_element"          -> accessibilityHandler.handle(cmd)
                "click_by_text"         -> accessibilityHandler.handle(cmd)
                "click_by_view_id"      -> accessibilityHandler.handle(cmd)
                "global_action"         -> accessibilityHandler.handle(cmd)
                "launch_intent"         -> intentHandler.handle(cmd)

                // Files
                "list_files"            -> fileSystemHandler.handle(cmd)
                "read_file"             -> fileSystemHandler.handle(cmd)
                "write_file"            -> fileSystemHandler.handle(cmd)
                "delete_file"           -> fileSystemHandler.handle(cmd)
                "analyze_storage"       -> storageHandler.handle(cmd)
                "find_large_files"      -> storageHandler.handle(cmd)

                // Camera / media
                "take_photo"            -> cameraHandler.handle(cmd)
                "record_video"          -> cameraHandler.handle(cmd)
                "search_media"          -> storageHandler.handle(cmd)
                "index_media_metadata"  -> storageHandler.handle(cmd)
                "speak_tts"             -> mediaHandler.handle(cmd)
                "play_audio"            -> mediaHandler.handle(cmd)
                "stop_audio"            -> mediaHandler.handle(cmd)
                "vibrate"               -> mediaHandler.handle(cmd)

                // Volume, device
                "get_volume"            -> volumeHandler.handle(cmd)
                "set_volume"            -> volumeHandler.handle(cmd)
                "get_device_info"       -> deviceInfoHandler.handle(cmd)
                "get_battery"           -> deviceInfoHandler.handle(cmd)
                "get_location"          -> deviceInfoHandler.handle(cmd)

                // Alarms, clipboard, overlay
                "get_alarms"            -> alarmHandler.handle(cmd)
                "set_alarm"             -> alarmHandler.handle(cmd)
                "dismiss_alarm"         -> alarmHandler.handle(cmd)
                "delete_alarm"          -> alarmHandler.handle(cmd)
                "get_clipboard"         -> clipboardHandler.handle(cmd)
                "set_clipboard"         -> clipboardHandler.handle(cmd)
                "show_overlay"          -> overlayHandler.handle(cmd)
                "show_toast"            -> overlayHandler.handle(cmd)

                // Screen power
                "wake_screen"           -> screenHandler.handle(cmd)
                "is_screen_on"          -> screenHandler.handle(cmd)

                // Shell & packages
                "execute_shell"         -> shellHandler.handle(cmd)
                "list_packages"         -> packageHandler.handle(cmd)

                else -> {
                    val valid = ToolDefinitions.all.joinToString(", ") { it.name }
                    return """{"error":"unknown tool '$toolName'. Valid tools: $valid"}"""
                }
            }

            if (result.success) {
                val data = result.data
                when (data) {
                    null       -> """{"success":true}"""
                    is Map<*, *> -> buildJsonObject {
                        data.forEach { (k, v) ->
                            when (v) {
                                is Boolean -> put(k.toString(), v)
                                is Number  -> put(k.toString(), v)
                                is String  -> put(k.toString(), v)
                                is List<*> -> put(k.toString(), buildJsonArray { v.forEach { item -> add(item.toString()) } })
                                else       -> put(k.toString(), v.toString())
                            }
                        }
                    }.toString()
                    is List<*>   -> buildJsonArray {
                        data.forEach { item ->
                            when (item) {
                                is Map<*, *> -> add(buildJsonObject {
                                    item.forEach { (k, v) ->
                                        when (v) {
                                            is Boolean -> put(k.toString(), v)
                                            is Number  -> put(k.toString(), v)
                                            else       -> put(k.toString(), v.toString())
                                        }
                                    }
                                })
                                else -> add(item.toString())
                            }
                        }
                    }.toString()
                    else       -> data.toString()
                }
            } else {
                """{"error":"${result.error?.replace("\"", "\\\"")}"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool $toolName threw: ${e.message}", e)
            """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }
}
