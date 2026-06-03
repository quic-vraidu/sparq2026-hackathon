package com.aster.ondevice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.ondevice.data.SettingsDataStore
import com.aster.ondevice.llm.LlmBackend
import com.aster.ondevice.llm.LlamaConfig
import com.aster.ondevice.llm.LlmEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val backend:         LlmBackend = LlmBackend.GENIE,
    val genieConfigPath: String     = "",
    // Phase 4 — QAIC Cloud
    val qaicApiKey:      String     = "qaic_7kxsqY7r_uaG2SEyUvrXIHLf2a23TW4QETRO50P4F",
    val apigeeToken:     String     = "kHAEOnGs3WCSeRt9Si5xRWvdDi4PbWavoDy27jNTuCkBmWgn",
    val qaicBaseUrl:     String     = "https://dev.apigwx-op.qualcomm.com/aips/sparq/api",
    val qaicModel:       String     = "gpt-oss-20b",
    val isLoading:       Boolean    = false,
    val statusMsg:       String     = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsDataStore,
    private val llm:   LlmEngine
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init { viewModelScope.launch { loadSettings() } }

    private suspend fun loadSettings() {
        _state.value = SettingsUiState(
            backend          = store.getLlmBackend(),
            genieConfigPath  = store.getGenieConfigPath(),
            qaicApiKey       = store.getQaicApiKey(),
            apigeeToken      = store.getApigeeToken(),
            qaicBaseUrl      = store.getQaicBaseUrl(),
            qaicModel        = store.getQaicModel(),
        )
    }

    fun setBackend(v: LlmBackend)        { _state.value = _state.value.copy(backend = v) }
    fun setGenieConfigPath(path: String) { _state.value = _state.value.copy(genieConfigPath = path) }
    fun setQaicApiKey(v: String)         { _state.value = _state.value.copy(qaicApiKey = v) }
    fun setApigeeToken(v: String)        { _state.value = _state.value.copy(apigeeToken = v) }
    fun setQaicBaseUrl(v: String)        { _state.value = _state.value.copy(qaicBaseUrl = v) }
    fun setQaicModel(v: String)          { _state.value = _state.value.copy(qaicModel = v) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        store.setLlmBackend(s.backend)
        store.setGenieConfigPath(s.genieConfigPath)
        store.setQaicApiKey(s.qaicApiKey)
        store.setApigeeToken(s.apigeeToken)
        store.setQaicBaseUrl(s.qaicBaseUrl)
        store.setQaicModel(s.qaicModel)
        _state.value = s.copy(statusMsg = "Saved")
    }

    fun loadModel() = viewModelScope.launch {
        val s = _state.value
        _state.value = s.copy(isLoading = true, statusMsg = "Loading…")
        store.setLlmBackend(s.backend)
        store.setGenieConfigPath(s.genieConfigPath)
        store.setQaicApiKey(s.qaicApiKey)
        store.setApigeeToken(s.apigeeToken)
        store.setQaicBaseUrl(s.qaicBaseUrl)
        store.setQaicModel(s.qaicModel)
        val path = when (s.backend) {
            LlmBackend.GENIE      -> s.genieConfigPath
            LlmBackend.QAIC_CLOUD -> ""
        }
        val ok = llm.load(path, LlamaConfig())
        val modelName = when (s.backend) {
            LlmBackend.GENIE      -> java.io.File(s.genieConfigPath).parentFile?.name ?: s.genieConfigPath
            LlmBackend.QAIC_CLOUD -> s.qaicModel
        }
        _state.value = _state.value.copy(
            isLoading = false,
            statusMsg = if (ok) "Model loaded! ($modelName)" else "Load failed"
        )
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        // ── Backend selector ──────────────────────────────────────────────────
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LLM Backend", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LlmBackend.entries.forEach { backend ->
                        FilterChip(
                            selected = state.backend == backend,
                            onClick  = { vm.setBackend(backend) },
                            label    = { Text(backend.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── Model config ──────────────────────────────────────────────────────
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.backend) {
                    LlmBackend.QAIC_CLOUD -> {
                        Text("QAIC Cloud (Qualcomm AI Inference Gateway)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sign in at https://aisuite.qualcomm.com to create a personal API key.\n" +
                            "The Apigee token is shared for all qualcomm-sparq tenant users.\n" +
                            "Save credentials then press Load to connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        var showApiKey by remember { mutableStateOf(false) }
                        var showApigee by remember { mutableStateOf(false) }

                        OutlinedTextField(
                            value         = state.qaicApiKey,
                            onValueChange = { vm.setQaicApiKey(it) },
                            modifier      = Modifier.fillMaxWidth(),
                            label         = { Text("QAIC API key (qaic_…)") },
                            placeholder   = { Text("qaic_<prefix>_<secret>") },
                            singleLine    = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon  = {
                                TextButton(onClick = { showApiKey = !showApiKey }) {
                                    Text(if (showApiKey) "Hide" else "Show")
                                }
                            }
                        )

                        OutlinedTextField(
                            value         = state.apigeeToken,
                            onValueChange = { vm.setApigeeToken(it) },
                            modifier      = Modifier.fillMaxWidth(),
                            label         = { Text("Apigee token (x-apikey)") },
                            singleLine    = true,
                            visualTransformation = if (showApigee) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon  = {
                                TextButton(onClick = { showApigee = !showApigee }) {
                                    Text(if (showApigee) "Hide" else "Show")
                                }
                            }
                        )

                        OutlinedTextField(
                            value         = state.qaicModel,
                            onValueChange = { vm.setQaicModel(it) },
                            modifier      = Modifier.fillMaxWidth(),
                            label         = { Text("Model ID") },
                            placeholder   = { Text("Qwen3-8B") },
                            singleLine    = true
                        )

                        OutlinedTextField(
                            value         = state.qaicBaseUrl,
                            onValueChange = { vm.setQaicBaseUrl(it) },
                            modifier      = Modifier.fillMaxWidth(),
                            label         = { Text("Base URL") },
                            placeholder   = { Text("https://dev.apigwx-op.qualcomm.com/aips/sparq/api") },
                            singleLine    = true
                        )
                    }

                    LlmBackend.GENIE -> {
                        Text("Genie Config (QNN NPU)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Provide the path to your genie_config.json.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value         = state.genieConfigPath,
                            onValueChange = { vm.setGenieConfigPath(it) },
                            modifier      = Modifier.fillMaxWidth(),
                            label         = { Text("genie_config.json path") },
                            placeholder   = { Text("/sdcard/Android/data/com.aster.ondevice/files/llama_3p2_3b/genie_config.json") },
                            singleLine    = true
                        )

                        if (!com.aster.ondevice.llm.GenieEngine.libraryAvailable) {
                            Text(
                                "aster_genie.so not found — add Genie SDK .so files to jniLibs/arm64-v8a/ and rebuild.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.save() }, Modifier.weight(1f)) { Text("Save") }
                    Button(onClick = { vm.loadModel() }, Modifier.weight(1f), enabled = !state.isLoading) {
                        if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp))
                        else Text("Load Model")
                    }
                }

                if (state.statusMsg.isNotBlank()) {
                    Text(
                        state.statusMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.statusMsg.contains("fail", ignoreCase = true))
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
