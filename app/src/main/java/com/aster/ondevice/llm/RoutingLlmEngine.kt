package com.aster.ondevice.llm

import android.util.Log
import com.aster.ondevice.data.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RoutingLlmEngine"

/**
 * Delegates all [LlmEngine] calls to either [GenieEngine] (QNN NPU via Genie SDK)
 * or [QaicEngine] (QAIC cloud REST API), based on the backend stored in settings.
 *
 * The active engine is chosen at [load] time.  All subsequent calls go to
 * whichever engine was last successfully loaded.
 *
 * Also implements [NativeToolCallingEngine] by forwarding to the active engine
 * if it supports native tool calling (i.e. [QaicEngine] when QAIC_CLOUD is active).
 * Returns an error result for on-device backends that do not implement the interface.
 *
 * Phase 2: default backend = GENIE — set Genie config JSON path in Settings → Load
 * Phase 4: user selects QAIC_CLOUD in Settings → set API key + Apigee token → Load
 */
@Singleton
class RoutingLlmEngine @Inject constructor(
    private val genie:    GenieEngine,
    private val qaic:     QaicEngine,
    private val settings: SettingsDataStore,
) : LlmEngine, NativeToolCallingEngine {

    private var active: LlmEngine = genie
    private var modelLabel: String = ""

    override suspend fun load(modelPath: String, config: LlamaConfig): Boolean {
        val backend = withContext(Dispatchers.IO) { settings.getLlmBackend() }
        Log.i(TAG, "load() backend=$backend  path=$modelPath")

        active.free()
        active = when (backend) {
            LlmBackend.GENIE       -> genie
            LlmBackend.QAIC_CLOUD  -> qaic
        }
        val ok = active.load(modelPath, config)
        modelLabel = if (ok) when (backend) {
            LlmBackend.GENIE      -> "Genie · ${java.io.File(modelPath).parentFile?.name ?: modelPath}"
            LlmBackend.QAIC_CLOUD -> "QAIC · ${withContext(Dispatchers.IO) { settings.getQaicModel() }}"
        } else ""
        return ok
    }

    override fun generate(
        prompt: String,
        maxNewTokens: Int,
        stopSequences: List<String>,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        onToken: (String) -> Unit
    ): String = active.generate(prompt, maxNewTokens, stopSequences, temperature, topP, topK, repeatPenalty, onToken)

    /**
     * Delegate native tool-calling to the active engine if it supports it.
     * On-device engines (Genie, LiteRT) don't implement [NativeToolCallingEngine],
     * so they get a descriptive error result instead of crashing.
     */
    override fun generateNativeTools(
        messages: JsonArray,
        tools: JsonArray,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float
    ): CloudNativeResult {
        val cloudEngine = active as? NativeToolCallingEngine
        if (cloudEngine == null) {
            Log.w(TAG, "generateNativeTools() called on non-cloud backend ${active::class.simpleName}")
            val msg = "[error: native tool calling is only supported on QAIC_CLOUD backend]"
            return CloudNativeResult(
                content = msg,
                toolCalls = null,
                rawAssistantMessage = buildJsonObject { put("role", "assistant"); put("content", msg) }
            )
        }
        return cloudEngine.generateNativeTools(messages, tools, maxNewTokens, temperature, topP)
    }

    override fun isLoaded():            Boolean       = active.isLoaded()
    override fun loadedModelLabel():    String        = modelLabel
    override fun chatTemplate():        ChatTemplate  = active.chatTemplate()
    override fun contextSize():         Int           = active.contextSize()
    override suspend fun healthCheck(): Boolean       = active.healthCheck()
    override fun free()                               { active.free() }
}
