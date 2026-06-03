package com.aster.ondevice.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.*

private const val TAG = "AsterA11y"

/**
 * Grants screen-reading and gesture injection.
 * Adapted from Aster — same Android API surface.
 */
class AsterAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AsterAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ── Screenshot (Android 11+) ──────────────────────────────────────────────

    fun takeScreenshotBase64(onResult: (String?) -> Unit) {
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bmp = android.graphics.Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace)
                    screenshot.hardwareBuffer.close()
                    if (bmp == null) { onResult(null); return }
                    val sw = bmp.width.coerceAtMost(1280)
                    val sh = (bmp.height * sw.toFloat() / bmp.width).toInt()
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
                    val baos = java.io.ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos)
                    onResult(android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP))
                }
                override fun onFailure(error: Int) { onResult(null) }
            })
    }

    // ── Screen hierarchy ──────────────────────────────────────────────────────

    fun getScreenHierarchy(mode: String = "interactive", searchText: String? = null): JsonObject {
        val root = rootInActiveWindow ?: return buildJsonObject { put("error", "no window") }
        return nodeToJson(root, mode, searchText, 0)
    }

    private fun nodeToJson(node: AccessibilityNodeInfo?, mode: String, search: String?, depth: Int): JsonObject {
        if (node == null) return buildJsonObject {}
        val interactive = node.isClickable || node.isLongClickable || node.isEditable || node.isFocusable
        if (mode == "interactive" && !interactive && depth > 0) return buildJsonObject {}

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (search != null && text.isBlank() && desc.isBlank()) return buildJsonObject {}

        return buildJsonObject {
            put("class", node.className?.toString()?.substringAfterLast('.') ?: "")
            if (text.isNotBlank())   put("text", text)
            if (desc.isNotBlank())   put("desc", desc)
            node.viewIdResourceName?.let { put("id", it) }
            if (node.isClickable)    put("clickable", true)
            if (node.isEditable)     put("editable", true)
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
            if (node.childCount > 0) {
                val children = buildJsonArray {
                    for (i in 0 until node.childCount) {
                        val child = nodeToJson(node.getChild(i), mode, search, depth + 1)
                        if (child.isNotEmpty()) add(child)
                    }
                }
                if (children.isNotEmpty()) put("children", children)
            }
        }
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    fun tap(x: Float, y: Float, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = onDone(true)
            override fun onCancelled(g: GestureDescription?) = onDone(false)
        }, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = onDone(true)
            override fun onCancelled(g: GestureDescription?) = onDone(false)
        }, null)
    }

    fun longPress(x: Float, y: Float, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 800)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = onDone(true)
            override fun onCancelled(g: GestureDescription?) = onDone(false)
        }, null)
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findAndClick(root, text)
    }

    private fun findAndClick(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) && (node.isClickable || node.parent?.isClickable == true)) {
            if (node.isClickable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            if (findAndClick(node.getChild(i), text)) return true
        }
        return false
    }

    fun inputText(text: String): Boolean {
        val root  = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args  = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
