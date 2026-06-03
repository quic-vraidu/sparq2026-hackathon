package com.aster.ondevice.agent

import kotlinx.serialization.json.*

/**
 * All tool schemas the agent can call.
 * Each ToolDef is serialised to JSON in the system prompt so the LLM knows
 * what it can do and what parameters each tool expects.
 */
data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = true)
data class ToolDef(val name: String, val description: String, val params: List<ToolParam>)

object ToolDefinitions {

    val all: List<ToolDef> = listOf(
        // ── Device & Status ──────────────────────────────────────────────────
        ToolDef("get_device_info",    "Device model, OS, RAM, storage info", emptyList()),
        ToolDef("get_battery",        "Battery level, charging status",      emptyList()),
        ToolDef("get_location",       "Current GPS location (lat/lng/city)", emptyList()),
        ToolDef("list_packages",      "List installed apps",
            listOf(ToolParam("includeSystem", "boolean", "Include system apps", required = false))),

        // ── Communication ────────────────────────────────────────────────────
        ToolDef("read_sms", "Read SMS inbox",
            listOf(
                ToolParam("limit",     "integer", "Max messages",    required = false),
                ToolParam("threadId",  "string",  "Thread ID filter",required = false),
                ToolParam("sinceDate", "integer", "Unix ms cutoff",  required = false)
            )),
        ToolDef("send_sms", "Send SMS",
            listOf(
                ToolParam("number",  "string", "Phone number"),
                ToolParam("message", "string", "Message text")
            )),
        ToolDef("delete_sms", "Delete an SMS by ID. Call read_sms first to get IDs.",
            listOf(ToolParam("id", "string", "SMS _id from read_sms"))),
        ToolDef("make_call", "Make a phone call",
            listOf(ToolParam("number", "string", "Phone number"))),
        ToolDef("make_call_with_voice", "Call and speak TTS on answer",
            listOf(
                ToolParam("number",      "string",  "Phone number"),
                ToolParam("text",        "string",  "Text to speak"),
                ToolParam("waitSeconds", "integer", "Wait before speaking", required = false)
            )),
        ToolDef("search_contacts", "Search contacts",
            listOf(
                ToolParam("name",   "string", "Name query",   required = false),
                ToolParam("number", "string", "Number query", required = false)
            )),

        // ── Notifications ────────────────────────────────────────────────────
        ToolDef("read_notifications", "Read active notifications",
            listOf(ToolParam("limit", "integer", "Max results", required = false))),
        ToolDef("post_notification", "Post a notification",
            listOf(
                ToolParam("title", "string", "Title"),
                ToolParam("body",  "string", "Body")
            )),
        ToolDef("dismiss_notification", "Dismiss notifications. Use key='all' to dismiss all at once (no prior read needed). For specific keys, call read_notifications first.",
            listOf(ToolParam("key", "string", "'all' to dismiss all, or exact key from read_notifications"))),

        // ── Screen Control ───────────────────────────────────────────────────
        ToolDef("take_screenshot",     "Capture a screenshot of the current display using the accessibility service (no camera required)", emptyList()),
        ToolDef("get_screen_hierarchy","Get UI accessibility tree",
            listOf(
                ToolParam("mode",       "string", "full|interactive|summary", required = false),
                ToolParam("searchText", "string", "Filter by text",           required = false)
            )),
        ToolDef("input_gesture", "Touch gesture on screen",
            listOf(
                ToolParam("gestureType", "string",  "TAP|SWIPE|LONG_PRESS"),
                ToolParam("x",           "integer", "X coordinate"),
                ToolParam("y",           "integer", "Y coordinate"),
                ToolParam("x2",          "integer", "End X (SWIPE)", required = false),
                ToolParam("y2",          "integer", "End Y (SWIPE)", required = false)
            )),
        ToolDef("input_text", "Type text into focused field",
            listOf(ToolParam("text", "string", "Text to type"))),
        ToolDef("find_element", "Find UI element by text",
            listOf(ToolParam("text", "string", "Search text"))),
        ToolDef("click_by_text", "Click UI element by text",
            listOf(ToolParam("text", "string", "Element text"))),
        ToolDef("click_by_view_id", "Click UI element by view ID",
            listOf(ToolParam("viewId", "string", "View resource ID"))),
        ToolDef("global_action", "System action",
            listOf(ToolParam("action", "string", "BACK|HOME|RECENTS|NOTIFICATIONS|LOCK_SCREEN"))),
        ToolDef("launch_intent", "Open app or fire intent",
            listOf(
                ToolParam("packageName", "string", "Package name",  required = false),
                ToolParam("action",      "string", "Intent action", required = false),
                ToolParam("data",        "string", "Data URI",      required = false)
            )),

        // ── Files & Storage ──────────────────────────────────────────────────
        ToolDef("list_files", "List files in directory",
            listOf(ToolParam("path", "string", "Directory path"))),
        ToolDef("read_file", "Read file content",
            listOf(ToolParam("path", "string", "File path"))),
        ToolDef("write_file", "Write content to file",
            listOf(
                ToolParam("path",    "string", "File path"),
                ToolParam("content", "string", "Content")
            )),
        ToolDef("delete_file", "Delete file or directory",
            listOf(ToolParam("path", "string", "Path"))),
        ToolDef("analyze_storage", "Disk usage breakdown",
            listOf(ToolParam("path", "string", "Root path", required = false))),
        ToolDef("find_large_files", "Find files above size threshold",
            listOf(
                ToolParam("minSizeMB", "integer", "Min size MB"),
                ToolParam("path",      "string",  "Search path", required = false)
            )),

        // ── Camera & Media ───────────────────────────────────────────────────
        ToolDef("take_photo", "Take a photo",
            listOf(
                ToolParam("camera",  "string",  "front|back",     required = false),
                ToolParam("quality", "integer", "JPEG quality",   required = false)
            )),
        ToolDef("record_video", "Record video (max 8s)",
            listOf(
                ToolParam("camera",      "string",  "front|back",  required = false),
                ToolParam("maxDuration", "integer", "Max seconds", required = false)
            )),
        ToolDef("search_media", "Search photos/videos",
            listOf(
                ToolParam("query",    "string", "Natural language query", required = false),
                ToolParam("dateFrom", "string", "Start YYYY-MM-DD",       required = false),
                ToolParam("dateTo",   "string", "End YYYY-MM-DD",         required = false)
            )),
        ToolDef("index_media_metadata", "Extract EXIF from photos/videos",
            listOf(ToolParam("path", "string", "Directory", required = false))),

        // ── Audio & Feedback ─────────────────────────────────────────────────
        ToolDef("speak_tts", "Speak text via TTS",
            listOf(ToolParam("text", "string", "Text to speak"))),
        ToolDef("play_audio", "Play audio from URL or base64",
            listOf(ToolParam("source", "string", "URL or base64"))),
        ToolDef("stop_audio",  "Stop audio playback", emptyList()),
        ToolDef("get_volume",  "Get volume levels",   emptyList()),
        ToolDef("set_volume", "Set or mute audio stream",
            listOf(
                ToolParam("stream", "string",  "media|ring|notification|alarm|call|system"),
                ToolParam("level",  "integer", "Volume level", required = false),
                ToolParam("mute",   "boolean", "true to mute", required = false)
            )),
        ToolDef("vibrate", "Vibrate device",
            listOf(ToolParam("pattern", "string", "JSON ms array e.g. [0,300,100]"))),
        ToolDef("show_toast", "Show toast message",
            listOf(ToolParam("message", "string", "Toast text"))),

        // ── Alarms & Clipboard ───────────────────────────────────────────────
        ToolDef("get_alarms",  "List all alarms with their IDs, time, label, and enabled state", emptyList()),
        ToolDef("set_alarm", "Create alarm",
            listOf(
                ToolParam("hour",    "integer", "Hour 0-23"),
                ToolParam("minute",  "integer", "Minute 0-59"),
                ToolParam("message", "string",  "Label", required = false)
            )),
        ToolDef("dismiss_alarm", "Dismiss ringing alarm", emptyList()),
        ToolDef("delete_alarm", "Delete alarm. Use alarmId='all' to delete all alarms at once.",
            listOf(ToolParam("alarmId", "string", "'all' to delete all, or specific alarm ID"))),
        ToolDef("get_clipboard", "Read clipboard", emptyList()),
        ToolDef("set_clipboard", "Copy text to clipboard",
            listOf(ToolParam("text", "string", "Text to copy"))),

        // ── Screen Power ─────────────────────────────────────────────────────
        ToolDef("wake_screen", "Turn screen on",
            listOf(ToolParam("holdSeconds", "integer", "Hold on seconds", required = false))),
        ToolDef("is_screen_on", "Check if screen is on", emptyList()),

        // ── Overlay & Shell ──────────────────────────────────────────────────
        ToolDef("show_overlay", "Show floating web overlay",
            listOf(
                ToolParam("html",    "string",  "HTML content", required = false),
                ToolParam("url",     "string",  "URL to load",  required = false),
                ToolParam("timeout", "integer", "Auto-dismiss seconds", required = false)
            )),
        ToolDef("execute_shell", "Run shell command",
            listOf(ToolParam("command", "string", "Shell command")))
    )

    /**
     * Convert all tools to the OpenAI function-calling format used by
     * [NativeToolCallingEngine.generateNativeTools].
     *
     * Produces a JsonArray of objects shaped as:
     *   {"type":"function","function":{"name":"...","description":"...","parameters":{...}}}
     *
     * Used exclusively for the QAIC Cloud path — on-device engines receive
     * the compact text representation via [toCompactText] in the system prompt.
     */
    fun toOpenAIFunctionsJson(): JsonArray = buildJsonArray {
        for (tool in all) {
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            for (p in tool.params) {
                                putJsonObject(p.name) {
                                    // Map our type strings to JSON Schema types
                                    put("type", when (p.type) {
                                        "boolean" -> "boolean"
                                        "integer" -> "integer"
                                        "number"  -> "number"
                                        else      -> "string"
                                    })
                                    put("description", p.description)
                                }
                            }
                        }
                        put("required", buildJsonArray {
                            tool.params.filter { it.required }.forEach { add(it.name) }
                        })
                    }
                }
            }
        }
    }

    /**
     * Compact text tool list for the system prompt.
     * Each tool: name(params) — description
     * No-param tools grouped on one line without descriptions to save space.
     */
    fun toCompactText(): String = buildString {
        val noParam   = all.filter { it.params.isEmpty() }
        val withParam = all.filter { it.params.isNotEmpty() }

        if (noParam.isNotEmpty()) {
            append("No-arg tools: ")
            append(noParam.joinToString("  ") { it.name })
            append("\n")
        }
        for (t in withParam) {
            append(t.name)
            append("(")
            append(t.params.joinToString(",") { p -> if (p.required) p.name else "${p.name}?" })
            append(") — ${t.description}\n")
        }
    }
}
