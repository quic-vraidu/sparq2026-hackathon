package com.aster.ondevice.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiteRtEngine"

/**
 * LLM engine backed by Google MediaPipe LiteRT-LM (LlmInference API).
 *
 * Targets Qualcomm Adreno GPU (Vulkan) via [LlmInference.Backend.GPU].
 * For true HTP/NPU acceleration use the NPU-compiled .task variant from
 * Kaggle — the QNN-embedded graph is used automatically when the device
 * supports it.
 *
 * Model format:  Gemma 4 4B INT4 .task file.
 * Recommended paths:
 *   GPU (Adreno Vulkan):  gemma-4-4b-it-gpu-int4.task
 *   CPU fallback:         gemma-4-4b-it-cpu-int4.task
 *
 * Copy the .task file to:
 *   /sdcard/Android/data/com.aster.ondevice/files/models/
 *
 * Dependency (app/build.gradle.kts):
 *   implementation("com.google.mediapipe:tasks-genai:<version>")
 */
@Singleton
class LiteRtEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : LlmEngine {

    private var llm: LlmInference? = null
    private var loadedConfig: LlamaConfig? = null

    // ── LlmEngine impl ────────────────────────────────────────────────────────

    override suspend fun load(modelPath: String, config: LlamaConfig): Boolean =
        withContext(Dispatchers.IO) {
            if (llm != null) free()

            if (modelPath.isBlank()) {
                Log.e(TAG, "Model path is empty")
                return@withContext false
            }
            if (!modelPath.endsWith(".task", ignoreCase = true)) {
                Log.e(TAG, "LiteRT expects a .task model file, got: $modelPath")
                return@withContext false
            }
            if (!File(modelPath).exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                return@withContext false
            }

            try {
                Log.i(TAG, "Loading LiteRT model: $modelPath")
                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    // maxNewTokens floor of 256 to avoid MediaPipe rejecting tiny values
                    .setMaxTokens(config.maxNewTokens.coerceAtLeast(256))
                    // maxTopK caps what sessions can request; 40 covers greedy to diverse
                    .setMaxTopK(config.topK.coerceIn(1, 100))
                    // GPU backend → Adreno Vulkan; if the .task file embeds a
                    // QNN-compiled graph (npu variant) the runtime selects HTP
                    // automatically on supported Snapdragon devices.
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .build()

                llm = LlmInference.createFromOptions(context, options)
                loadedConfig = config
                Log.i(TAG, "LiteRT model loaded OK")
                true
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT load failed", e)
                false
            }
        }

    /**
     * Synchronous blocking generate; bridges MediaPipe's async callback API
     * using a [CountDownLatch].  [onToken] is called on MediaPipe's internal
     * thread — safe to post to Compose state from there.
     *
     * In tasks-genai 0.10.22+, per-inference sampling parameters (topK,
     * temperature, randomSeed) are set on [LlmInferenceSessionOptions].
     * A new session is created for each call and closed when done.
     */
    override fun generate(
        prompt: String,
        maxNewTokens: Int,
        stopSequences: List<String>,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        onToken: (String) -> Unit
    ): String {
        val engine = checkNotNull(llm) { "LiteRtEngine not loaded" }

        val sessionOptions = LlmInferenceSessionOptions.builder()
            .setTopK(topK.coerceIn(1, 100))
            .setTopP(topP)
            .setTemperature(temperature)
            .setRandomSeed(42)
            .build()

        val latch = CountDownLatch(1)
        val sb    = StringBuilder()

        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
        try {
            session.addQueryChunk(prompt)
            session.generateResponseAsync { partial: String, done: Boolean ->
                if (partial.isNotEmpty()) {
                    sb.append(partial)
                    onToken(partial)
                }
                if (done) latch.countDown()
            }

            // 5-minute timeout — long SMS-analysis prompts can take a while on first run
            if (!latch.await(300, TimeUnit.SECONDS)) {
                Log.w(TAG, "generate() timed out after 300 s")
            }
        } finally {
            session.close()
        }

        // Apply stop sequences (MediaPipe stops on EOS / end_of_turn but not
        // arbitrary custom sequences the agent injects)
        var result = sb.toString()
        for (stop in stopSequences) {
            val idx = result.indexOf(stop)
            if (idx >= 0) { result = result.substring(0, idx); break }
        }
        return result.trim()
    }

    override fun isLoaded(): Boolean = llm != null

    /** Gemma 4 uses the Gemma chat template (<start_of_turn> / <end_of_turn>). */
    override fun chatTemplate(): ChatTemplate = ChatTemplate.GEMMA
    override fun contextSize(): Int = loadedConfig?.nCtx ?: 4096

    override suspend fun healthCheck(): Boolean = withContext(Dispatchers.Default) {
        if (!isLoaded()) return@withContext false
        try {
            val out = generate(prompt = "1+1=", maxNewTokens = 4, temperature = 0f).trim()
            val ok  = out.isNotEmpty()
            if (ok) Log.i(TAG, "healthCheck OK: \"$out\"")
            else    Log.w(TAG, "healthCheck FAIL: empty output")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "healthCheck exception", e)
            false
        }
    }

    override fun free() {
        llm?.close()
        llm = null
        loadedConfig = null
        Log.i(TAG, "LiteRtEngine freed")
    }
}
