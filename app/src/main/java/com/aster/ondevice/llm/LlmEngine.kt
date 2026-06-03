package com.aster.ondevice.llm

/** Swappable LLM engine interface.
 *  Phase 1: LlamaEngine (llama.cpp JNI)
 *  Phase 2: QnnEngine   (Qualcomm QNN SDK)
 */
interface LlmEngine {
    /** Load model from file. Returns true on success. */
    suspend fun load(modelPath: String, config: LlamaConfig): Boolean

    /** Generate text. [onToken] called for each new token (streaming). */
    fun generate(
        prompt: String,
        maxNewTokens: Int,
        stopSequences: List<String> = emptyList(),
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        onToken: (String) -> Unit = {}
    ): String

    fun isLoaded(): Boolean
    fun free()

    /** Human-readable label for the currently loaded model (backend + model name). */
    fun loadedModelLabel(): String = ""

    /** Return the ChatTemplate of the currently loaded model config. */
    fun chatTemplate(): ChatTemplate

    /**
     * Return the context window size in tokens for the loaded model.
     * Used by OnDeviceAgent to compute the prompt truncation cap dynamically.
     * On-device engines return their nCtx; cloud engines return the server-side limit.
     */
    fun contextSize(): Int

    /**
     * Run a minimal inference (4 tokens, greedy) to verify the model is
     * functional and producing output.  Returns true if at least one token
     * was generated without throwing.
     */
    suspend fun healthCheck(): Boolean
}
