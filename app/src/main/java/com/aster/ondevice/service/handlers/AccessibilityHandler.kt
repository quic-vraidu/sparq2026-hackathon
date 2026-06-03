package com.aster.ondevice.service.handlers

import android.content.Context
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.AsterAccessibilityService
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilityHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {

    override fun supportedActions() = listOf(
        "take_screenshot", "get_screen_hierarchy", "input_gesture",
        "input_text", "find_element", "click_by_text", "click_by_view_id", "global_action"
    )

    override suspend fun handle(command: Command): CommandResult {
        val svc = AsterAccessibilityService.instance
            ?: return CommandResult.err("Accessibility service not connected. Enable in Settings.")
        return when (command.action) {
            "take_screenshot" -> suspendCancellableCoroutine { cont ->
                svc.takeScreenshotBase64 { b64 ->
                    if (b64 != null) cont.resume(CommandResult.ok(mapOf("base64" to b64, "format" to "jpeg")))
                    else cont.resume(CommandResult.err("Screenshot failed"))
                }
            }
            "get_screen_hierarchy" -> {
                val mode   = command.params["mode"]?.jsonPrimitive?.content ?: "interactive"
                val search = command.params["searchText"]?.jsonPrimitive?.content
                CommandResult.ok(svc.getScreenHierarchy(mode, search))
            }
            "input_gesture" -> {
                val type = command.params["gestureType"]?.jsonPrimitive?.content ?: "TAP"
                val x    = command.params["x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: 0f
                val y    = command.params["y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: 0f
                val x2   = command.params["x2"]?.jsonPrimitive?.intOrNull?.toFloat() ?: x
                val y2   = command.params["y2"]?.jsonPrimitive?.intOrNull?.toFloat() ?: y
                suspendCancellableCoroutine { cont ->
                    when (type.uppercase()) {
                        "SWIPE"      -> svc.swipe(x, y, x2, y2) { cont.resume(CommandResult.ok(mapOf("done" to it))) }
                        "LONG_PRESS" -> svc.longPress(x, y)      { cont.resume(CommandResult.ok(mapOf("done" to it))) }
                        else         -> svc.tap(x, y)            { cont.resume(CommandResult.ok(mapOf("done" to it))) }
                    }
                }
            }
            "input_text" -> {
                val text = command.params["text"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing text")
                CommandResult.ok(mapOf("done" to svc.inputText(text)))
            }
            "click_by_text" -> {
                val text = command.params["text"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing text")
                CommandResult.ok(mapOf("clicked" to svc.clickByText(text)))
            }
            "find_element"      -> CommandResult.ok(svc.getScreenHierarchy("interactive", command.params["text"]?.jsonPrimitive?.content))
            "click_by_view_id"  -> CommandResult.ok(mapOf("note" to "Tap at element bounds after finding via get_screen_hierarchy"))
            "global_action" -> {
                val action = when (command.params["action"]?.jsonPrimitive?.content?.uppercase()) {
                    "BACK"          -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    "HOME"          -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    "RECENTS"       -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                    "NOTIFICATIONS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "LOCK_SCREEN"   -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    else            -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                }
                CommandResult.ok(mapOf("done" to svc.performGlobalAction(action)))
            }
            else -> CommandResult.err("Unknown: ${command.action}")
        }
    }
}
