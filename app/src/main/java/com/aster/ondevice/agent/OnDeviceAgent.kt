package com.aster.ondevice.agent

import android.util.Log
import com.aster.ondevice.llm.ChatTemplate
import com.aster.ondevice.llm.LlmEngine
import com.aster.ondevice.llm.NativeToolCallingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG       = "OnDeviceAgent"
private const val MAX_STEPS = 10
private const val LOG_DIR   = "/storage/emulated/0/Download/aster"

// Rough token estimate: ~4 chars per token for English/JSON text.
// Keep prompt under 75% of contextSize() to leave room for generation.
// Computed dynamically in process() via llm.contextSize().
private const val MAX_TOOL_RESULT_CHARS     = 800    // cap per tool result in turns
private const val MAX_SMS_TOOL_RESULT_CHARS = 5000   // higher cap for read_sms (monthly/full inbox)

/** One turn in the conversation. */
sealed class AgentMessage {
    data class User(val text: String)                          : AgentMessage()
    data class Status(val text: String)                        : AgentMessage()
    data class Thinking(val text: String)                      : AgentMessage()
    data class ToolCall(val name: String, val args: String)    : AgentMessage()
    data class ToolResult(val name: String, val result: String): AgentMessage()
    data class Assistant(val text: String)                     : AgentMessage()
    data class Error(val text: String)                         : AgentMessage()
}

/** Observable state for the UI. */
data class AgentState(
    val isRunning: Boolean = false,
    val messages: List<AgentMessage> = emptyList()
)

/**
 * Writes one log file per agent request to LOG_DIR.
 * Captures: user input, each step's full prompt + raw generated output,
 * each raw tool result (untruncated), and the final response.
 */
private class AgentLogger(userInput: String) {
    private val file: File? = try {
        val dir = File(LOG_DIR).also { it.mkdirs() }
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val f   = File(dir, "aster_$ts.txt")
        f.writeText("=== ASTER AGENT LOG ===\nTimestamp: $ts\n\n")
        f.appendText("=== USER INPUT ===\n$userInput\n\n")
        f
    } catch (e: Exception) {
        Log.e("AgentLogger", "Failed to create log file: ${e.message}")
        null
    }

    fun logPrompt(step: Int, prompt: String) = append("=== STEP $step — PROMPT TO MODEL ===\n$prompt\n\n")
    fun logGenerated(step: Int, output: String) = append("=== STEP $step — MODEL OUTPUT ===\n$output\n\n")
    fun logToolResult(step: Int, toolName: String, rawResult: String) =
        append("=== STEP $step — TOOL RESULT: $toolName (${rawResult.length} chars, untruncated) ===\n$rawResult\n\n")
    fun logFinalResponse(response: String) = append("=== FINAL RESPONSE ===\n$response\n")

    private fun append(text: String) {
        try { file?.appendText(text) }
        catch (e: Exception) { Log.e("AgentLogger", "Write failed: ${e.message}") }
    }

    fun path(): String = file?.absolutePath ?: "(log disabled)"
}

/**
 * On-device ReAct agent loop.
 *
 * Flow per event/message:
 *   1. Build prompt using the model's chat template
 *   2. Call LlmEngine.generate() with sampling params from config
 *   3. Parse output:
 *      - THOUGHT: line      → emit Thinking message
 *      - {"tool":..}        → call ToolDispatcher, append result, loop
 *      - {"done":..}        → emit Assistant message, stop
 *      - anything else      → emit Assistant message, stop
 *   4. Repeat up to MAX_STEPS
 */
@Singleton
class OnDeviceAgent @Inject constructor(
    private val llm:                 LlmEngine,
    private val dispatcher:          ToolDispatcher,
    private val systemPromptBuilder: SystemPromptBuilder,
) {
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    /** Append a Status log bubble and publish it immediately. */
    private fun MutableList<AgentMessage>.emitStatus(text: String) {
        Log.i(TAG, "[STATUS] $text")
        add(AgentMessage.Status(text))
        _state.value = _state.value.copy(messages = toList())
    }

    /** Process a user message or a proactive event (e.g. SMS received). */
    suspend fun process(userInput: String): String = withContext(Dispatchers.Default) {
        Log.i(TAG, "=======================================")
        Log.i(TAG, "process() START  input=${userInput.take(120)}")

        if (!llm.isLoaded()) {
            Log.e(TAG, "process() ABORT — model not loaded")
            return@withContext "Model not loaded. Please set model path in Settings."
        }

        // Route to native tool-calling path for cloud backends (QAIC_CLOUD).
        // On-device backends use the text-based ReAct loop below.
        val cloudEngine = llm as? NativeToolCallingEngine
        if (llm.chatTemplate() == ChatTemplate.OPENAI && cloudEngine != null) {
            return@withContext processCloud(userInput, cloudEngine)
        }

        // Conversation turns: (role, content)
        val turns = mutableListOf<Pair<String, String>>()
        val uiMessages = _state.value.messages.toMutableList()

        uiMessages += AgentMessage.User(userInput)
        _state.value = AgentState(isRunning = true, messages = uiMessages)

        turns += "user" to userInput

        val systemPrompt   = systemPromptBuilder.build()
        val stopSequences  = stopSequencesForTemplate(llm.chatTemplate())
        // 75% of context window in chars (4 chars ≈ 1 token), leaving 25% for generation.
        val maxPromptChars = (llm.contextSize() * 0.75 * 4).toInt()
        Log.d(TAG, "systemPrompt chars=${systemPrompt.length}  template=${llm.chatTemplate()}  contextSize=${llm.contextSize()}  maxPromptChars=$maxPromptChars")
        Log.d(TAG, "stopSequences=$stopSequences")

        uiMessages.emitStatus("Model ready · template=${llm.chatTemplate()} · system prompt ${systemPrompt.length} chars")

        var finalResponse = ""
        val requestStart = System.currentTimeMillis()
        val logger = AgentLogger(userInput)
        Log.i(TAG, "AgentLogger writing to: ${logger.path()}")

        for (step in 0 until MAX_STEPS) {
            Log.i(TAG, "--- Step $step/${MAX_STEPS - 1} ----------------------------")

            val s = step + 1  // 1-based label for display

            // ── Build prompt ───────────────────────────────────────────────
            uiMessages.emitStatus("Step $s [build]: formatting prompt…")
            val prompt = systemPromptBuilder.formatPrompt(systemPrompt, turns)
            val estimatedTokens = prompt.length / 4
            Log.i(TAG, "prompt chars=${prompt.length}  ~tokens=$estimatedTokens")

            if (prompt.length > maxPromptChars) {
                Log.w(TAG, "prompt too long (${prompt.length} > $maxPromptChars chars) — trimming oldest tool results")
                uiMessages.emitStatus("Step $s [build]: prompt too long (~$estimatedTokens tokens) — trimming oldest tool results…")
                trimOldestToolResults(turns)
                val trimmedPrompt = systemPromptBuilder.formatPrompt(systemPrompt, turns)
                Log.i(TAG, "after trim: prompt chars=${trimmedPrompt.length}")
            }

            val finalPrompt = systemPromptBuilder.formatPrompt(systemPrompt, turns)
            val finalTokens = finalPrompt.length / 4
            Log.d(TAG, "finalPrompt full (${finalPrompt.length} chars):\n$finalPrompt")
            uiMessages.emitStatus("Step $s [build]: prompt ready · ~$finalTokens input tokens")
            logger.logPrompt(s, finalPrompt)

            // ── LLM inference ──────────────────────────────────────────────
            uiMessages.emitStatus("Step $s [infer]: running LLM…")
            Log.i(TAG, "calling llm.generate() maxNewTokens=512")
            val inferStart = System.currentTimeMillis()
            val generated = llm.generate(
                prompt        = finalPrompt,
                maxNewTokens  = 512,
                stopSequences = stopSequences,
                onToken       = { /* streaming — could update UI here */ }
            ).trim()
            val inferMs = System.currentTimeMillis() - inferStart

            val inputTokens  = finalPrompt.length / 4
            val outputTokens = generated.length / 4
            val tps = if (inferMs > 0 && outputTokens > 0) outputTokens * 1000f / inferMs else 0f
            Log.i(TAG, "llm.generate() done  output chars=${generated.length}  " +
                "inferMs=$inferMs  ~inputTokens=$inputTokens  ~outputTokens=$outputTokens  " +
                "tps=${"%.1f".format(tps)}")
            Log.d(TAG, "generated output:\n$generated")
            uiMessages.emitStatus("Step $s [infer]: ${inferMs}ms · ~$inputTokens→$outputTokens tokens · ${"%.1f".format(tps)} tok/s")
            logger.logGenerated(s, generated)

            // ── Parse THOUGHT line ─────────────────────────────────────────
            val lines   = generated.lines().filter { it.isNotBlank() }
            val thought = lines.firstOrNull { it.startsWith("THOUGHT:", ignoreCase = true) }
            if (thought != null) {
                val thoughtText = thought.removePrefix("THOUGHT:").trim()
                Log.i(TAG, "THOUGHT: $thoughtText")
                uiMessages += AgentMessage.Thinking(thoughtText)
                _state.value = _state.value.copy(messages = uiMessages)
            }

            // ── Find JSON block ────────────────────────────────────────────
            // Strip any prefix text (e.g. "Step 1 output: ") before the opening brace
            val jsonLine = lines.firstNotNullOfOrNull { l ->
                val idx = l.indexOf('{')
                if (idx >= 0 && l.trimEnd().endsWith("}")) l.substring(idx) else null
            }

            // Try to parse, with repair fallback, then done-pattern fallback
            val json: JsonObject? = when {
                jsonLine != null -> {
                    val parsed = repairJson(jsonLine)
                    if (parsed == null) Log.w(TAG, "JSON repair FAILED for: $jsonLine")
                    else if (parsed !== runCatching { Json.parseToJsonElement(jsonLine).jsonObject }.getOrNull())
                        Log.i(TAG, "JSON repaired: $jsonLine")
                    parsed
                }
                else -> {
                    // No JSON-shaped line found — try to extract done pattern from raw text
                    parseDonePattern(generated).also { p ->
                        if (p != null) Log.i(TAG, "Parsed done from plain text: $generated")
                    }
                }
            }

            if (json == null) {
                Log.w(TAG, "JSON parse FAILED, raw: ${(jsonLine ?: generated).take(120)}")
                uiMessages.emitStatus("Step $s [parse]: JSON parse FAILED · raw: ${(jsonLine ?: generated).take(120)}")
                finalResponse = generated
                uiMessages += AgentMessage.Assistant(generated)
                turns += "assistant" to generated
                break
            }

            // ── Done? ──────────────────────────────────────────────────────
            if (json["done"]?.jsonPrimitive?.booleanOrNull == true) {
                finalResponse = json["message"]?.jsonPrimitive?.content ?: "Done."
                Log.i(TAG, "DONE: $finalResponse")
                uiMessages.emitStatus("Step $s [parse]: done=true · finished in $s step(s)")
                uiMessages += AgentMessage.Assistant(finalResponse)
                turns += "assistant" to (jsonLine ?: generated)
                break
            }

            // ── Tool call ─────────────────────────────────────────────────
            val toolName = json["tool"]?.jsonPrimitive?.content
                ?.let { resolveToolName(it) }
            val toolArgs = json["args"]?.jsonObject ?: buildJsonObject {}

            if (toolName == null) {
                Log.w(TAG, "JSON has no 'tool' key and no 'done' key: ${(jsonLine ?: generated).take(120)}")
                uiMessages.emitStatus("Step $s [parse]: JSON missing 'tool'/'done' · raw: ${(jsonLine ?: generated).take(120)}")
                finalResponse = generated
                uiMessages += AgentMessage.Assistant(generated)
                turns += "assistant" to generated
                break
            }

            Log.i(TAG, "TOOL CALL: $toolName  args=$toolArgs")
            uiMessages.emitStatus("Step $s [tool]: calling '$toolName'…")
            uiMessages += AgentMessage.ToolCall(toolName, toolArgs.toString())
            _state.value = _state.value.copy(messages = uiMessages)

            turns += "assistant" to (jsonLine ?: generated)

            // Execute tool
            val rawResult = dispatcher.execute(toolName, toolArgs)
            Log.i(TAG, "TOOL RESULT ($toolName) raw chars=${rawResult.length}")
            Log.d(TAG, "TOOL RESULT ($toolName) content=${rawResult.take(300)}")
            logger.logToolResult(s, toolName, rawResult)

            // Truncate large results (e.g. screenshot base64) before adding to turns
            val truncatedResult = truncateToolResult(toolName, rawResult)
            if (truncatedResult.length < rawResult.length) {
                Log.w(TAG, "TOOL RESULT truncated from ${rawResult.length} to ${truncatedResult.length} chars")
                uiMessages.emitStatus("Step $s [tool]: '$toolName' result truncated ${rawResult.length}→${truncatedResult.length} chars for prompt")
            } else {
                uiMessages.emitStatus("Step $s [tool]: '$toolName' returned ${rawResult.length} chars")
            }

            uiMessages += AgentMessage.ToolResult(toolName, rawResult) // show full in UI
            _state.value = _state.value.copy(messages = uiMessages)

            turns += "tool_result" to truncatedResult  // only truncated goes into prompt

            if (step == MAX_STEPS - 1) {
                finalResponse = "Reached max steps ($MAX_STEPS). Last result: ${rawResult.take(200)}"
                Log.w(TAG, "MAX STEPS reached")
                uiMessages.emitStatus("Reached max steps ($MAX_STEPS) — stopping")
                uiMessages += AgentMessage.Assistant(finalResponse)
            }
        }

        val totalMs    = System.currentTimeMillis() - requestStart
        val totalSec   = totalMs / 1000f
        val stepsUsed  = if (finalResponse.isEmpty()) MAX_STEPS else
            uiMessages.filterIsInstance<AgentMessage.Status>()
                .lastOrNull { it.text.contains("done=true") }
                ?.text?.substringAfter("finished in ")?.substringBefore(" step")
                ?.toIntOrNull() ?: MAX_STEPS
        val duration = if (totalMs < 60_000) {
            "${"%.1f".format(totalSec)}s"
        } else {
            val m = (totalMs / 60_000).toInt()
            val s = ((totalMs % 60_000) / 1000f)
            "${m}m ${"%.1f".format(s)}s"
        }
        val timingLine = "Completed in $duration · $stepsUsed step(s)"
        Log.i(TAG, "TIMING: $timingLine  (totalMs=$totalMs)")
        uiMessages.emitStatus(timingLine)

        _state.value = AgentState(isRunning = false, messages = uiMessages)
        Log.i(TAG, "process() END  response=${finalResponse.take(120)}")
        Log.i(TAG, "=======================================")
        logger.logFinalResponse(finalResponse)
        finalResponse
    }

    /**
     * Cloud-native tool-calling ReAct loop for the QAIC Cloud backend.
     *
     * Differences from the on-device [process] path:
     *  - System prompt via [SystemPromptBuilder.buildCloudPrompt] — no inline JSON
     *    output rules, no tool list; both are handled by the API natively.
     *  - Tools sent as OpenAI function definitions via [ToolDefinitions.toOpenAIFunctionsJson].
     *  - LLM response parsed from [CloudNativeResult.toolCalls] (structured objects),
     *    not from free-form JSON text.
     *  - Tool results added as role:"tool" messages with tool_call_id, not "tool_result"
     *    plain-text turns.
     *  - The assistant message with its tool_calls array is preserved verbatim in the
     *    message history so the model sees a well-formed multi-turn conversation.
     */
    private suspend fun processCloud(
        userInput: String,
        cloudEngine: NativeToolCallingEngine
    ): String {
        Log.i(TAG, "processCloud() START  input=${userInput.take(120)}")

        val uiMessages = _state.value.messages.toMutableList()
        uiMessages += AgentMessage.User(userInput)
        _state.value = AgentState(isRunning = true, messages = uiMessages)

        val cloudPrompt = systemPromptBuilder.buildCloudPrompt()
        val tools = ToolDefinitions.toOpenAIFunctionsJson()
        val logger = AgentLogger(userInput)

        // Message history as a mutable list of JsonObjects; wrapped to JsonArray per call.
        val history = mutableListOf(
            buildJsonObject { put("role", "system"); put("content", cloudPrompt) },
            buildJsonObject { put("role", "user");   put("content", userInput)   }
        )

        Log.d(TAG, "processCloud() cloudPrompt chars=${cloudPrompt.length}  tools=${tools.size}")
        uiMessages.emitStatus("Cloud · QAIC native tool-calling · ${tools.size} tools available")

        var finalResponse = ""
        val requestStart = System.currentTimeMillis()

        for (step in 0 until MAX_STEPS) {
            val s = step + 1
            Log.i(TAG, "--- Cloud Step $s/${MAX_STEPS} ---")

            uiMessages.emitStatus("Cloud step $s [infer]: calling QAIC…")
            val inferStart = System.currentTimeMillis()
            logger.logPrompt(s, history.toString())

            val result = cloudEngine.generateNativeTools(
                messages    = JsonArray(history),
                tools       = tools,
                maxNewTokens = 512,
                temperature  = 0.7f,
                topP         = 0.95f
            )

            val inferMs = System.currentTimeMillis() - inferStart
            Log.i(TAG, "processCloud() step $s done in ${inferMs}ms  toolCalls=${result.toolCalls?.size ?: 0}  content=${result.content?.take(80)}")
            logger.logGenerated(s, "toolCalls=${result.toolCalls}  content=${result.content}")

            // ── No tool calls → final answer ──────────────────────────────
            if (result.toolCalls.isNullOrEmpty()) {
                finalResponse = result.content?.trim() ?: "Done."
                uiMessages.emitStatus("Cloud step $s [infer]: ${inferMs}ms · done")
                uiMessages += AgentMessage.Assistant(finalResponse)
                // Append assistant message to history for completeness
                history.add(result.rawAssistantMessage)
                _state.value = _state.value.copy(messages = uiMessages)
                break
            }

            // ── Tool calls → execute each, append messages ────────────────
            uiMessages.emitStatus("Cloud step $s [infer]: ${inferMs}ms · ${result.toolCalls.size} tool call(s)")

            // Add the assistant message (with tool_calls) to history BEFORE tool results
            history.add(result.rawAssistantMessage)

            for (tc in result.toolCalls) {
                val resolvedName = resolveToolName(tc.name) ?: tc.name
                Log.i(TAG, "CLOUD TOOL CALL: $resolvedName  args=${tc.arguments}")
                uiMessages.emitStatus("Cloud step $s [tool]: calling '$resolvedName'…")
                uiMessages += AgentMessage.ToolCall(resolvedName, tc.arguments.toString())
                _state.value = _state.value.copy(messages = uiMessages)

                val rawResult = dispatcher.execute(resolvedName, tc.arguments)
                Log.i(TAG, "CLOUD TOOL RESULT ($resolvedName) raw chars=${rawResult.length}")
                logger.logToolResult(s, resolvedName, rawResult)

                val truncated = truncateToolResult(resolvedName, rawResult)
                if (truncated.length < rawResult.length) {
                    uiMessages.emitStatus("Cloud step $s [tool]: '$resolvedName' result truncated ${rawResult.length}→${truncated.length} chars")
                } else {
                    uiMessages.emitStatus("Cloud step $s [tool]: '$resolvedName' returned ${rawResult.length} chars")
                }

                uiMessages += AgentMessage.ToolResult(resolvedName, rawResult)
                _state.value = _state.value.copy(messages = uiMessages)

                // role:"tool" message with tool_call_id for next turn
                history.add(buildJsonObject {
                    put("role",         "tool")
                    put("tool_call_id", tc.id)
                    put("content",      truncated)
                })
            }

            if (step == MAX_STEPS - 1) {
                finalResponse = "Reached max steps ($MAX_STEPS)."
                Log.w(TAG, "processCloud() MAX STEPS reached")
                uiMessages.emitStatus("Reached max steps ($MAX_STEPS) — stopping")
                uiMessages += AgentMessage.Assistant(finalResponse)
            }
        }

        val totalMs  = System.currentTimeMillis() - requestStart
        val duration = if (totalMs < 60_000) "${"%.1f".format(totalMs / 1000f)}s"
                       else "${(totalMs / 60_000).toInt()}m ${"%.1f".format((totalMs % 60_000) / 1000f)}s"
        uiMessages.emitStatus("Completed in $duration (cloud)")
        _state.value = AgentState(isRunning = false, messages = uiMessages)
        logger.logFinalResponse(finalResponse)
        Log.i(TAG, "processCloud() END  response=${finalResponse.take(120)}")
        return finalResponse
    }

    fun clearHistory() {
        _state.value = AgentState()
        Log.i(TAG, "history cleared")
    }

    /**
     * Single-shot inference with NO tool schema and NO ReAct loop.
     * Use for analysis tasks (e.g. SMS spending) where the data is already in the
     * prompt and the model just needs to produce a plain-text answer.
     *
     * The prompt is wrapped in the correct chat template for the loaded model,
     * but the system prompt is a simple "helpful assistant" instruction — no tools,
     * no JSON-only rules — so the model can reply in any format (e.g. CHUNK_TOTAL=...).
     *
     * Updates _state.isRunning so Chat and other observers know the model is busy.
     * Does NOT add messages to _state so Chat history stays clean.
     */
    suspend fun generateDirect(userPrompt: String, maxNewTokens: Int = 256): String = withContext(Dispatchers.Default) {
        if (!llm.isLoaded()) return@withContext "Model not loaded."

        _state.value = _state.value.copy(isRunning = true)
        try {
            val sys    = "You are a helpful assistant. Answer concisely and follow the output format instructions exactly."
            val turns  = listOf("user" to userPrompt)
            val prompt = systemPromptBuilder.formatPrompt(sys, turns)
            val stops  = stopSequencesForTemplate(llm.chatTemplate())
            Log.i(TAG, "generateDirect() prompt chars=${prompt.length}  maxNewTokens=$maxNewTokens")
            val result = llm.generate(
                prompt        = prompt,
                maxNewTokens  = maxNewTokens,
                stopSequences = stops,
            ).trim()
            Log.i(TAG, "generateDirect() result=${result.take(120)}")
            result
        } finally {
            _state.value = _state.value.copy(isRunning = false)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Truncate a tool result before it enters the LLM prompt.
     * Screenshot base64 is never useful as LLM input — replace with a short summary.
     * Other large results (file contents, UI hierarchies) are capped.
     */
    private fun truncateToolResult(toolName: String, result: String): String {
        // Screenshot returns base64 — completely useless as text tokens
        if (toolName == "take_screenshot") {
            return if (result.startsWith("{") || result.startsWith("error", ignoreCase = true))
                result.take(MAX_TOOL_RESULT_CHARS)
            else
                "[screenshot captured, ${result.length} chars base64 — not included in prompt]"
        }
        // SMS results need a larger window so monthly/inbox analysis isn't crippled
        val limit = if (toolName == "read_sms") MAX_SMS_TOOL_RESULT_CHARS else MAX_TOOL_RESULT_CHARS
        return if (result.length > limit) truncateJsonArray(result, limit) else result
    }

    /**
     * Truncate a JSON array result at a clean object boundary so the model never
     * sees a half-written JSON object in its context. Finds the last complete
     * "}," or "}" before the char limit and closes the array there.
     */
    private fun truncateJsonArray(result: String, limit: Int): String {
        val cut = result.take(limit)
        // Find the last complete object boundary ("},") within the cut
        val lastClose = cut.lastIndexOf("},")
        val truncated = if (lastClose > 0) cut.take(lastClose + 1) + "]" else cut
        return "$truncated\n...[truncated ${result.length - limit} chars]"
    }

    /**
     * When the prompt is too long, drop the oldest tool_result turns (largest contributors).
     * Always keeps the first user turn and the most recent turns.
     */
    private fun trimOldestToolResults(turns: MutableList<Pair<String, String>>) {
        val idx = turns.indexOfFirst { it.first == "tool_result" }
        if (idx >= 0) {
            Log.w(TAG, "dropping tool_result at index $idx to reduce prompt size")
            turns.removeAt(idx)
            // Also drop the preceding assistant (tool call) turn if present
            if (idx > 0 && turns.getOrNull(idx - 1)?.first == "assistant") {
                turns.removeAt(idx - 1)
            }
        }
    }

    private fun stopSequencesForTemplate(template: ChatTemplate): List<String> = when (template) {
        ChatTemplate.LLAMA3 -> listOf("<|eot_id|>", "<|start_header_id|>")
        ChatTemplate.GEMMA  -> listOf("<end_of_turn>", "<start_of_turn>")
        ChatTemplate.OPENAI -> emptyList()   // stop sequences sent as API param by QaicEngine
    }

    /**
     * Resolve a possibly-truncated tool name to a known tool.
     * Exact match wins; otherwise returns the single tool whose name starts with [name].
     * If ambiguous (multiple prefix matches) returns null so the call fails explicitly.
     */
    private fun resolveToolName(name: String): String? {
        val known = ToolDefinitions.all.map { it.name }
        if (name in known) return name
        val prefixMatches = known.filter { it.startsWith(name) }
        if (prefixMatches.size == 1) {
            Log.w(TAG, "fuzzy tool name: '$name' resolved to '${prefixMatches[0]}'")
            return prefixMatches[0]
        }
        return name  // let dispatcher report unknown tool
    }

    /**
     *  1. As-is (fast path)
     *  2. Fix missing closing quote on string KEY: "key:"value" → "key":"value"
     *  3. Fix missing closing quote on string VALUE before comma+key: :"val,"next" → :"val","next"
     *  4. Fix missing outer closing brace
     *  5. Replace single quotes with double quotes
     */
    private fun repairJson(raw: String): JsonObject? {
        fun tryParse(s: String) = runCatching { Json.parseToJsonElement(s).jsonObject }.getOrNull()

        // 0. Model restarted mid-output — e.g. {"done":true,"msg":"foo{"done":true,"msg":"bar"}
        //    Extract the last top-level JSON object (last occurrence of {"done" or {"tool")
        val lastRestart = Regex("""(\{"(?:done|tool|message)"[^{]*)$""").find(raw)
        if (lastRestart != null && lastRestart.value != raw) {
            tryParse(lastRestart.value)?.let { return it }
        }

        // 1. as-is
        tryParse(raw)?.let { return it }

        var s = raw

        // 2. "word:" inside a string → "word": (LLM drops the closing quote on the key)
        s = s.replace(Regex("\"(\\w+):\"")) { "\"${it.groupValues[1]}\":\"" }
        tryParse(s)?.let { return it }

        // 3. missing closing quote on a string value before the next key or comma:
        //    :"somevalue,"nextKey"  →  :"somevalue","nextKey"
        s = s.replace(Regex(""":\s*"([^"]*?),"(\w)""")) { """:"${it.groupValues[1]}","${it.groupValues[2]}""" }
        tryParse(s)?.let { return it }

        // 4. missing outer closing brace
        if (!s.trimEnd().endsWith("}")) {
            tryParse("$s}")?.let { return it }
            tryParse("$s}}")?.let { return it }
        }

        // 5. unquoted keys/values: {tool:list_packages} → {"tool":"list_packages"}
        //    Only quote bare words (letters/digits/underscore) not already inside quotes
        val fix3 = s.replace(Regex("""(?<!["\w])(\w+)(?=\s*:)"""), "\"$1\"")   // unquoted keys
                    .replace(Regex(""":\s*([a-zA-Z_]\w*)(?=[,}\]])"""), ":\"$1\"") // unquoted string values
        tryParse(fix3)?.let { return it }

        // 5b. bare word key before nested object — missing both quotes and colon
        //     e.g. {tool: send_sms, args {number: "x"}} → args: {
        //     Matches: , or { + optional space + bare word + space + {
        val fix4 = fix3.replace(Regex("""([,{]\s*)([a-zA-Z_]\w*)\s*(\{)"""), "$1\"$2\": $3")
        tryParse(fix4)?.let { return it }

        // 5c. quoted key before nested object — missing colon only
        //     e.g. "args" {"number": "x"} → "args": {
        val fix5 = fix4.replace(Regex(""""([a-zA-Z_]\w*)"\s*(\{)"""), "\"$1\": $2")
        tryParse(fix5)?.let { return it }

        // 6. single → double quotes
        s = fix5.replace('\'', '"')
        tryParse(s)?.let { return it }

        return null
    }

    /**
     * When the LLM skips braces entirely, e.g.:
     *   done:true, message:'Battery is 72% charged, charging.'
     * Extract done + message directly via regex.
     */
    private fun parseDonePattern(text: String): JsonObject? {
        val isDone = Regex("""done\s*:\s*true""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (!isDone) return null
        val msgMatch = Regex("""message\s*:\s*['"](.+?)['"]""").find(text)
            ?: Regex("""message\s*:\s*(.+)""").find(text)
        val message = msgMatch?.groupValues?.get(1)?.trim() ?: return null
        return buildJsonObject {
            put("done", true)
            put("message", message)
        }
    }
}

