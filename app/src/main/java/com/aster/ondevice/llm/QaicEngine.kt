package com.aster.ondevice.llm

import android.util.Log
import com.aster.ondevice.data.SettingsDataStore
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "QaicEngine"

/**
 * Phase 4 LLM backend — routes inference to the QAIC Inference Gateway
 * (Qualcomm AI Cloud, OpenAI-compatible REST API).
 *
 * Requires:
 *   - QAIC API key (personal, generated at https://aisuite.qualcomm.com)
 *   - Apigee token (shared for the qualcomm-sparq tenant)
 *
 * [chatTemplate] returns [ChatTemplate.OPENAI] so [SystemPromptBuilder.formatPrompt]
 * serialises the conversation as a JSON messages array instead of an on-device
 * template string.  [generate] deserialises that array and sends it as messages[]
 * to the /v1/chat/completions endpoint with streaming enabled.
 *
 * Also implements [NativeToolCallingEngine] so that [OnDeviceAgent.processCloud]
 * can use the API's native function-calling support (tools + tool_choice) instead
 * of the text-based JSON schema embedded in the system prompt.
 */
@Singleton
class QaicEngine @Inject constructor(
    private val settings: SettingsDataStore
) : LlmEngine, NativeToolCallingEngine {

    private val client = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private var loaded = false
    private var apiKey = ""
    private var apigeeToken = ""
    private var baseUrl = ""
    private var model = ""

    override suspend fun load(modelPath: String, config: LlamaConfig): Boolean {
        apiKey      = settings.getQaicApiKey()
        apigeeToken = settings.getApigeeToken()
        baseUrl     = settings.getQaicBaseUrl().trimEnd('/')
        model       = settings.getQaicModel()
        loaded      = apiKey.isNotBlank() && apigeeToken.isNotBlank()
        if (loaded) Log.i(TAG, "QAIC cloud engine ready (model=$model, url=$baseUrl)")
        else Log.w(TAG, "QAIC cloud engine: missing credentials — set API key and Apigee token in Settings")
        return loaded
    }

    /**
     * Send the conversation to the QAIC gateway and stream tokens back.
     *
     * [prompt] is expected to be a JSON array string produced by
     * [SystemPromptBuilder.formatOpenAI], e.g.:
     *   [{"role":"system","content":"..."},{"role":"user","content":"..."}]
     *
     * Tokens are delivered via [onToken] as they arrive over SSE, and the
     * complete generated string is returned when the stream ends.
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
        if (!loaded) {
            Log.w(TAG, "generate() called before load() — returning empty")
            return ""
        }

        val messages = try {
            Json.parseToJsonElement(prompt).jsonArray
        } catch (e: Exception) {
            Log.e(TAG, "generate() prompt is not a JSON array — chatTemplate wrong? prompt.take(200)=${prompt.take(200)}", e)
            return "[QAIC error: prompt format error — ${e.message}]"
        }

        // Always block <think> tokens so Qwen3 can't enter thinking mode even if
        // /no_think in the user message is insufficient.
        val effectiveStops = (stopSequences + "<think>").distinct()

        val bodyJson = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxNewTokens)
            put("top_p", topP.toDouble())
            put("stream", true)
            put("stop", buildJsonArray { effectiveStops.forEach { add(it) } })
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("x-apikey", apigeeToken)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val sb = StringBuilder()
        val reasoningBuf = StringBuilder()
        var reasoningTokens = 0
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "no body"
                    Log.e(TAG, "QAIC API error: ${response.code} $errorBody")
                    return "[QAIC error ${response.code}: $errorBody]"
                }
                val source = response.body?.source() ?: return ""
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val chunk = Json.parseToJsonElement(data).jsonObject
                        val delta = chunk["choices"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")?.jsonObject ?: continue
                        // Capture reasoning_content (Qwen3 thinking mode)
                        delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { reasoningTokens++; reasoningBuf.append(it) }
                        val token = delta["content"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (token.isEmpty()) continue
                        sb.append(token)
                        onToken(token)
                    } catch (_: Exception) {
                        // malformed SSE chunk, skip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generate() failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return "[QAIC network error: ${e.javaClass.simpleName}: ${e.message}]"
        }
        // Fallback: if the model routed all output to reasoning_content (thinking mode
        // active despite enable_thinking=false and /no_think), use reasoning as response.
        if (sb.isEmpty() && reasoningBuf.isNotEmpty()) {
            Log.w(TAG, "generate() content empty but reasoning=$reasoningTokens tokens — using reasoning as fallback")
            return reasoningBuf.toString()
        }
        if (sb.isEmpty()) Log.w(TAG, "generate() returned empty (reasoningTokens=$reasoningTokens)")
        return sb.toString()
    }

    /**
     * Native tool-calling inference — uses the OpenAI function-calling API
     * (tools + tool_choice:"auto") instead of text-based JSON schemas.
     *
     * Uses stream:false so that tool_calls can be parsed from a single complete
     * response object rather than accumulating partial JSON across SSE chunks.
     *
     * The returned [CloudNativeResult.rawAssistantMessage] must be appended to
     * the caller's message history before adding tool-result messages, so the
     * model sees a well-formed conversation on the next turn.
     */
    override fun generateNativeTools(
        messages: JsonArray,
        tools: JsonArray,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float
    ): CloudNativeResult {
        val errorResult = { msg: String ->
            CloudNativeResult(
                content = msg,
                toolCalls = null,
                rawAssistantMessage = buildJsonObject {
                    put("role", "assistant")
                    put("content", msg)
                }
            )
        }

        if (!loaded) {
            Log.w(TAG, "generateNativeTools() called before load()")
            return errorResult("[QAIC error: engine not loaded]")
        }

        val bodyJson = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("tools", tools)
            put("tool_choice", "auto")
            put("temperature", temperature.toDouble())
            put("max_tokens", maxNewTokens)
            put("top_p", topP.toDouble())
            put("stream", false)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("x-apikey", apigeeToken)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: "no body"
                    Log.e(TAG, "generateNativeTools() API error: ${response.code} $body")
                    return errorResult("[QAIC error ${response.code}: $body]")
                }
                val bodyStr = response.body?.string() ?: return errorResult("[QAIC error: empty body]")
                val root = Json.parseToJsonElement(bodyStr).jsonObject
                val message = root["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?: return errorResult("[QAIC error: malformed response]")

                Log.d(TAG, "generateNativeTools() message=$message")

                val toolCallsJson = message["tool_calls"]?.jsonArray
                val content = message["content"]?.jsonPrimitive?.contentOrNull

                val toolCalls = toolCallsJson?.mapNotNull { tc ->
                    try {
                        val tcObj = tc.jsonObject
                        val fn = tcObj["function"]!!.jsonObject
                        val argsStr = fn["arguments"]!!.jsonPrimitive.content
                        val args = runCatching {
                            Json.parseToJsonElement(argsStr).jsonObject
                        }.getOrElse { buildJsonObject {} }
                        CloudToolCall(
                            id        = tcObj["id"]!!.jsonPrimitive.content,
                            name      = fn["name"]!!.jsonPrimitive.content,
                            arguments = args
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "generateNativeTools() failed to parse tool_call entry: $tc", e)
                        null
                    }
                }?.takeIf { it.isNotEmpty() }

                CloudNativeResult(
                    content             = content,
                    toolCalls           = toolCalls,
                    rawAssistantMessage = message
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateNativeTools() failed: ${e.message}", e)
            errorResult("[QAIC network error: ${e.javaClass.simpleName}: ${e.message}]")
        }
    }

    override fun isLoaded(): Boolean = loaded

    /** Always OPENAI — tells SystemPromptBuilder to serialise as JSON messages array. */
    override fun chatTemplate(): ChatTemplate = ChatTemplate.OPENAI

    /** Context window for gpt-oss-20b on the QAIC gateway is 2048 tokens. */
    override fun contextSize(): Int = 4096

    override fun free() {
        loaded = false
    }

    override suspend fun healthCheck(): Boolean {
        if (!loaded) return false
        return try {
            val request = Request.Builder()
                .url("$baseUrl/v1/health")
                .header("x-apikey", apigeeToken)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "healthCheck failed: ${e.message}")
            false
        }
    }
}
