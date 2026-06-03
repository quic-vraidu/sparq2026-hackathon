package com.aster.ondevice.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.ondevice.agent.OnDeviceAgent
import com.aster.ondevice.llm.LlmEngine
import com.aster.ondevice.service.AsterService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    val agent: OnDeviceAgent,
    val llm:   LlmEngine
) : ViewModel() {

    var healthState   by mutableStateOf<Boolean?>(null) ; private set
    var healthRunning by mutableStateOf(false)          ; private set

    fun runHealthCheck() {
        if (healthRunning) return
        viewModelScope.launch {
            healthRunning = true
            healthState   = llm.healthCheck()
            healthRunning = false
        }
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val agentState by vm.agent.state.collectAsState()
    val modelLoaded = vm.llm.isLoaded()
    val modelLabel  = vm.llm.loadedModelLabel().ifBlank { if (modelLoaded) "Loaded" else "Not loaded" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Aster On-Device AI", style = MaterialTheme.typography.headlineMedium)

        // Status card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                StatusRow("LLM Model",     modelLabel, modelLoaded)
                StatusRow("Agent",         if (agentState.isRunning) "Running" else "Idle", agentState.isRunning)
                StatusRow("Accessibility", "Enable in Settings → Accessibility", false)
                StatusRow("Notifications", "Enable in Settings → Notifications", false)

                // LLM health check
                Divider()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val (label, ok) = when {
                        vm.healthRunning        -> "Testing…"     to null
                        vm.healthState == true  -> "LLM OK ✓"    to true
                        vm.healthState == false -> "LLM FAIL ✗"  to false
                        else                    -> "LLM not tested" to null
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (ok) {
                            true  -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            else  -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    OutlinedButton(
                        onClick  = { vm.runHealthCheck() },
                        enabled  = modelLoaded && !vm.healthRunning,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("Test", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Quick actions
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quick Actions", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    context.startForegroundService(Intent(context, AsterService::class.java))
                }, Modifier.fillMaxWidth()) { Text("Start AI Service") }
                OutlinedButton(onClick = {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }, Modifier.fillMaxWidth()) { Text("Enable Accessibility Service") }
                OutlinedButton(onClick = {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }, Modifier.fillMaxWidth()) { Text("Enable Notification Listener") }
            }
        }

        // Recent activity
        if (agentState.messages.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Recent Activity", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    agentState.messages.takeLast(4).forEach { msg ->
                        Text(msg.toString().take(80), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
