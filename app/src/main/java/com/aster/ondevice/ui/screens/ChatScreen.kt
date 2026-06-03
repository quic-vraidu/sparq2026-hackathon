package com.aster.ondevice.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.ondevice.agent.AgentMessage
import com.aster.ondevice.agent.OnDeviceAgent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agent: OnDeviceAgent
) : ViewModel() {
    val state = agent.state

    fun send(input: String) {
        viewModelScope.launch {
            try {
                agent.process(input)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "agent.process() threw uncaught exception", e)
            }
        }
    }
    fun clear() = agent.clearHistory()
}

@Composable
fun ChatScreen(vm: ChatViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = { vm.clear() },
                enabled = state.messages.isNotEmpty() && !state.isRunning
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Clear chat")
            }
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.messages) { msg ->
                MessageBubble(msg)
            }
        }

        if (state.isRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask me anything…") },
                singleLine = false,
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { if (input.isNotBlank()) { vm.send(input); input = "" } },
                enabled = !state.isRunning && input.isNotBlank()
            ) {
                Icon(Icons.Default.Send, "Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: AgentMessage) {
    val (label, text, color) = when (msg) {
        is AgentMessage.User       -> Triple("You",        msg.text,   MaterialTheme.colorScheme.primaryContainer)
        is AgentMessage.Status     -> Triple("▸",          msg.text,   MaterialTheme.colorScheme.surface)
        is AgentMessage.Thinking   -> Triple("Thinking",   msg.text,   MaterialTheme.colorScheme.secondaryContainer)
        is AgentMessage.ToolCall   -> Triple("Tool ▶ ${msg.name}", msg.args, MaterialTheme.colorScheme.tertiaryContainer)
        is AgentMessage.ToolResult -> Triple("Result ◀ ${msg.name}", msg.result, MaterialTheme.colorScheme.surfaceVariant)
        is AgentMessage.Assistant  -> Triple("Aster AI",   msg.text,   MaterialTheme.colorScheme.inverseSurface)
        is AgentMessage.Error      -> Triple("Error",      msg.text,   MaterialTheme.colorScheme.errorContainer)
    }
    if (msg is AgentMessage.Status) {
        // Render as a compact inline log line, no card background
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "▸ $text",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
            Text(text.take(600), style = MaterialTheme.typography.bodySmall)
        }
    }
}
