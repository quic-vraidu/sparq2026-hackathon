package com.aster.ondevice.llm

/**
 * LLM backend selection.
 * GENIE      — Phase 2: NPU inference via Qualcomm Genie SDK + serialized .bin model.
 * QAIC_CLOUD — Phase 4: cloud inference via QAIC Inference Gateway (OpenAI-compatible REST API).
 */
enum class LlmBackend {
    GENIE,
    /** Phase 4: cloud inference via QAIC Inference Gateway (OpenAI-compatible REST API). */
    QAIC_CLOUD,
}

/**
 * Chat template format used by the loaded GGUF model.
 * Each model family uses a different tokenization/template format for chat turns.
 */
enum class ChatTemplate {
    /**
     * Llama 3 / Llama 3.1 / Llama 3.2 format:
     *   <|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n...<|eot_id|>
     *   <|start_header_id|>user<|end_header_id|>\n\n...<|eot_id|>
     *   <|start_header_id|>assistant<|end_header_id|>\n\n
     */
    LLAMA3,

    /**
     * Gemma / Gemma 2 format:
     *   <start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n
     */
    GEMMA,

    /**
     * OpenAI messages format for cloud inference (QAIC gateway / any OpenAI-compatible API).
     * formatPrompt() serialises the conversation as a JSON array:
     *   [{"role":"system","content":"..."},{"role":"user","content":"..."},...]
     * QaicEngine.generate() deserialises this and sends it as the messages[] field.
     */
    OPENAI,
}

data class LlamaConfig(
    /** Context window size in tokens. 4096 suits most 3B models. */
    val nCtx: Int = 4096,
    /** Inference threads. Use (CPU cores / 2) for best latency on ARM big.LITTLE. */
    val nThreads: Int = 4,
    /** Layers to offload to GPU/NPU. 0 = CPU-only (Phase 1 default). */
    val nGpuLayers: Int = 0,
    /** Max new tokens per generation call. */
    val maxNewTokens: Int = 512,
    /** Chat template format — must match the loaded model. */
    val chatTemplate: ChatTemplate = ChatTemplate.LLAMA3,
    /**
     * Sampling temperature. 0.0 = greedy (deterministic), 0.7 = typical creative,
     * 1.0 = full distribution. Values > 1.2 tend to degrade coherence.
     */
    val temperature: Float = 1.0f,
    /** Top-p (nucleus) sampling. 0.9 keeps the 90% probability mass. 1.0 = disabled. */
    val topP: Float = 0.95f,
    /** Top-k sampling. 40 = consider only top 40 tokens. 0 = disabled. */
    val topK: Int = 64,
    /** Repetition penalty. 1.0 = disabled, 1.1 = light penalty, 1.3 = strong. */
    val repeatPenalty: Float = 1.1f,
)
