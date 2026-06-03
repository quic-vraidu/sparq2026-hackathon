package com.aster.ondevice.llm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GenieEngine"

/**
 * Phase 2 LLM engine — wraps Qualcomm Genie SDK via JNI.
 *
 * Prerequisites (must be provided by the developer before building):
 *   1. Genie headers: app/src/main/cpp/genie/GenieCommon.h + GenieDialog.h
 *   2. Pre-built .so libs in app/src/main/jniLibs/arm64-v8a/:
 *        libGenie.so, libQnnHtp.so, libQnnHtpV79Skel.so
 *
 * Model files on device (example for Llama 3.2 3B):
 *   /sdcard/Android/data/com.aster.ondevice/files/llama_3p2_3b/genie_config.json
 *
 * Usage: in Settings, select "Genie" backend and set the path to genie_config.json.
 * The JSON must have correct absolute paths to tokenizer.json and model .bin files.
 *
 * See app/src/main/cpp/genie_jni.cpp for the native implementation.
 */
@Singleton
class GenieEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : LlmEngine {

    private var loaded = false
    private var template: ChatTemplate = ChatTemplate.LLAMA3

    // ── JNI declarations ─────────────────────────────────────────────────────

    /** @param configJson  Full text of the Genie JSON config file.
     *  @param nativeLibDir context.applicationInfo.nativeLibraryDir
     *  @param modelDir   Parent directory of the config JSON (contains model files).
     *  @return 0 on success, -1 on failure. */
    private external fun nativeLoad(configJson: String, nativeLibDir: String, modelDir: String): Int

    /** @param prompt      Full formatted prompt string.
     *  @param maxTokens  Max new tokens to generate.
     *  @param callback   Called per token (streaming): (String) -> Unit.
     *  @return 0 on success, Genie error code otherwise. */
    private external fun nativeInfer(prompt: String, maxTokens: Int, callback: Function1<String, Unit>): Int

    private external fun nativeFree()

    // ── LlmEngine impl ───────────────────────────────────────────────────────

    /**
     * @param modelPath  Absolute path to the genie_config.json file.
     * @param config     Only [LlamaConfig.chatTemplate] and [LlamaConfig.maxNewTokens]
     *                   are used; sampling params are configured inside the JSON.
     */
    override suspend fun load(modelPath: String, config: LlamaConfig): Boolean =
        withContext(Dispatchers.Default) {
            if (!libraryAvailable) {
                Log.e(TAG, "aster_genie.so not available — Genie SDK not installed")
                return@withContext false
            }
            if (loaded) free()

            val configFile = File(modelPath)
            if (!configFile.exists()) {
                Log.e(TAG, "Genie config not found: $modelPath")
                return@withContext false
            }
            if (!modelPath.endsWith(".json", ignoreCase = true)) {
                Log.e(TAG, "Genie expects a .json config file, got: $modelPath")
                return@withContext false
            }
            if (configFile.length() > 1024 * 1024) {  // sanity: JSON config is always < 1 MB
                Log.e(TAG, "File too large to be a config JSON (${configFile.length()} bytes): $modelPath")
                return@withContext false
            }

            val configJson  = configFile.readText()
            val modelDir    = configFile.parent ?: ""
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            Log.i(TAG, "Loading Genie: config=$modelPath  modelDir=$modelDir")
            val result = nativeLoad(configJson, nativeLibDir, modelDir)
            loaded = (result == 0)
            if (loaded) {
                template = config.chatTemplate
                Log.i(TAG, "Genie loaded OK  template=${config.chatTemplate}")
            } else {
                Log.e(TAG, "Genie load failed (result=$result)")
            }
            loaded
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
    ): String {
        check(loaded) { "GenieEngine not loaded" }

        val sb = StringBuilder()
        nativeInfer(prompt, maxNewTokens) { token ->
            sb.append(token)
            onToken(token)
        }

        // Apply stop sequences (Genie stops on EOT token but not custom stops)
        var result = sb.toString()
        for (stop in stopSequences) {
            val idx = result.indexOf(stop)
            if (idx >= 0) { result = result.substring(0, idx); break }
        }
        return result
    }

    override fun isLoaded(): Boolean = loaded && libraryAvailable

    override fun chatTemplate(): ChatTemplate = template
    override fun contextSize(): Int = 4096  // Genie models are compiled at fixed seq-len; 4096 is typical

    override suspend fun healthCheck(): Boolean = withContext(Dispatchers.Default) {
        if (!isLoaded()) return@withContext false
        try {
            val out = generate(prompt = "1+1=", maxNewTokens = 4, temperature = 0f).trim()
            val ok = out.isNotEmpty()
            if (ok) Log.i(TAG, "healthCheck OK: \"$out\"")
            else    Log.w(TAG, "healthCheck FAIL: empty output")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "healthCheck exception", e)
            false
        }
    }

    override fun free() {
        if (loaded) {
            nativeFree()
            loaded = false
        }
    }

    companion object {
        var libraryAvailable = false
            private set

        init {
            try {
                // Load dependency chain in order (Android linker won't auto-resolve
                // from nativeLibraryDir on all API levels).
                // libcdsprpc.so is declared as uses-native-library in manifest (required=true)
                // → loads from vendor namespace, giving Stub access to HIDL deps.
                System.loadLibrary("QnnHtpV81Stub") // libQnnHtpV81Stub.so
                System.loadLibrary("QnnHtp")        // libQnnHtp.so
                System.loadLibrary("Genie")         // libGenie.so
                System.loadLibrary("aster_genie")
                libraryAvailable = true
                Log.i(TAG, "aster_genie.so loaded OK")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Genie SDK load failed: ${e.message}")
                Log.w(TAG, "Ensure libGenie.so + libQnnHtp.so + libQnnHtpV81Stub.so are in jniLibs/arm64-v8a/ and rebuild.")
            }
        }
    }
}
