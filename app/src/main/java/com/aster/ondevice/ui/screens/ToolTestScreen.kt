package com.aster.ondevice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.ondevice.agent.ToolDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject

// ── Default args for each tool (for quick testing) ──────────────────────────
private val TOOL_TESTS: List<Pair<String, JsonObject>> = listOf(
    // Device & Status
    "get_battery"           to buildJsonObject {},
    "get_device_info"       to buildJsonObject {},
    "get_location"          to buildJsonObject {},
    "is_screen_on"          to buildJsonObject {},
    "wake_screen"           to buildJsonObject { put("holdSeconds", 5) },
    "list_packages"         to buildJsonObject { put("includeSystem", false) },

    // Notifications
    "read_notifications"    to buildJsonObject {},
    "post_notification"     to buildJsonObject { put("title", "Test"); put("body", "Tool test") },
    "dismiss_notification"  to buildJsonObject { put("key", "all") },

    // Screen / Accessibility
    "take_screenshot"       to buildJsonObject {},
    "get_screen_hierarchy"  to buildJsonObject { put("mode", "summary") },
    "global_action"         to buildJsonObject { put("action", "HOME") },
    "show_toast"            to buildJsonObject { put("message", "Tool test toast") },

    // Audio
    "get_volume"            to buildJsonObject {},
    "speak_tts"             to buildJsonObject { put("text", "Hello from tool test") },
    "vibrate"               to buildJsonObject { put("pattern", "[0,300,100,200]") },

    // Alarms & Clipboard
    "get_alarms"            to buildJsonObject {},
    "set_alarm"             to buildJsonObject { put("hour", 8); put("minute", 0); put("message", "Test alarm") },
    "get_clipboard"         to buildJsonObject {},
    "set_clipboard"         to buildJsonObject { put("text", "tool test clipboard") },

    // Files
    "list_files"            to buildJsonObject { put("path", "/sdcard/Android/data/com.aster.ondevice/files/models") },

    // SMS / Contacts
    "read_sms"              to buildJsonObject { put("limit", 3) },
    "search_contacts"       to buildJsonObject { put("name", "a") },

    // Shell
    "execute_shell"         to buildJsonObject { put("command", "date") },
)

// ── ViewModel ─────────────────────────────────────────────────────────────────
data class ToolTestEntry(
    val tool:     String,
    val args:     JsonObject,
    val argsText: String  = "",   // editable JSON args string
    val result:   String  = "",
    val running:  Boolean = false
)

@HiltViewModel
class ToolTestViewModel @Inject constructor(
    private val dispatcher: ToolDispatcher
) : ViewModel() {

    private val _entries = MutableStateFlow(TOOL_TESTS.map { (t, a) -> ToolTestEntry(t, a, a.toString()) })
    val entries: StateFlow<List<ToolTestEntry>> = _entries

    fun updateArgs(index: Int, text: String) {
        _entries.value = _entries.value.toMutableList().also {
            it[index] = it[index].copy(argsText = text)
        }
    }

    fun run(index: Int) {
        val entry = _entries.value[index]
        // Parse edited args text back to JsonObject, fall back to original args on error
        val args = try {
            Json.parseToJsonElement(entry.argsText).jsonObject
        } catch (e: Exception) {
            entry.args
        }
        _entries.value = _entries.value.toMutableList().also {
            it[index] = entry.copy(running = true, result = "running…")
        }
        viewModelScope.launch {
            val result = try {
                dispatcher.execute(entry.tool, args)
            } catch (e: Exception) {
                """{"error":"${e.message}"}"""
            }
            _entries.value = _entries.value.toMutableList().also {
                it[index] = entry.copy(running = false, result = result)
            }
        }
    }

    fun runAll() {
        TOOL_TESTS.indices.forEach { run(it) }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun ToolTestScreen(vm: ToolTestViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Tool Test", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { vm.runAll() }) { Text("Run All") }
        }

        HorizontalDivider()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(entries.indices.toList()) { i ->
                ToolCard(entries[i], onRun = { vm.run(i) }, onArgsChange = { vm.updateArgs(i, it) })
            }
        }
    }
}

@Composable
private fun ToolCard(entry: ToolTestEntry, onRun: () -> Unit, onArgsChange: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.tool, style = MaterialTheme.typography.titleSmall)
                Button(onClick = onRun, enabled = !entry.running) {
                    if (entry.running) CircularProgressIndicator(Modifier.size(16.dp))
                    else Text("Run")
                }
            }
            OutlinedTextField(
                value = entry.argsText,
                onValueChange = onArgsChange,
                label = { Text("args (JSON)") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                singleLine = false,
                minLines = 1,
            )
            if (entry.result.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        entry.result,
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
