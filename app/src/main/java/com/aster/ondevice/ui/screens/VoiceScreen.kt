package com.aster.ondevice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.ondevice.agent.OnDeviceAgent
import com.aster.ondevice.asr.AndroidAsrEngine
import com.aster.ondevice.tts.TtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceState(val isListening: Boolean = false, val transcript: String = "", val response: String = "", val isProcessing: Boolean = false)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val asr:   AndroidAsrEngine,
    private val tts:   TtsEngine,
    private val agent: OnDeviceAgent
) : ViewModel() {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    init {
        asr.warmUp()
    }

    fun startListening() {
        _state.value = VoiceState(isListening = true)
        asr.startListening(
            onResult = { text ->
                _state.value = _state.value.copy(isListening = false, transcript = text, isProcessing = true)
                viewModelScope.launch {
                    val response = agent.process(text)
                    _state.value = _state.value.copy(response = response, isProcessing = false)
                    tts.speak(response)
                }
            },
            onError = { err ->
                _state.value = VoiceState(response = "ASR error: $err")
            }
        )
    }

    fun stopListening() {
        asr.stopListening()
        _state.value = _state.value.copy(isListening = false)
    }

    fun clear() {
        _state.value = VoiceState()
        agent.clearHistory()
    }
}

@Composable
fun VoiceScreen(vm: VoiceViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Voice Mode", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(32.dp))

            // Mic button
            FloatingActionButton(
                onClick = { if (state.isListening) vm.stopListening() else vm.startListening() },
                containerColor = if (state.isListening) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(100.dp)
            ) {
                Icon(
                    if (state.isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (state.isListening) "Stop" else "Speak",
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            when {
                state.isListening    -> Text("Listening…", style = MaterialTheme.typography.bodyLarge)
                state.isProcessing   -> { Text("Processing…", style = MaterialTheme.typography.bodyLarge); LinearProgressIndicator() }
                state.response.isNotBlank() -> {
                    Text(state.response, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text("Tap the mic and speak", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Clear button — top-right corner, only visible when there is something to clear
        if (state.response.isNotBlank()) {
            IconButton(
                onClick = { vm.clear() },
                enabled = !state.isListening && !state.isProcessing,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Clear")
            }
        }
    }
}
