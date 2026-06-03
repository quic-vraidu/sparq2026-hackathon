package com.aster.ondevice.agent

import android.os.Build
import com.aster.ondevice.llm.ChatTemplate
import com.aster.ondevice.llm.LlmEngine
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemPromptBuilder @Inject constructor(
    private val llm: LlmEngine
) {

    /**
     * On-device system prompt — includes strict JSON output rules and the full
     * inline tool list.  Used by all non-cloud backends (Genie, LiteRT).
     */
    fun build(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return """
You are an on-device AI agent controlling ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}).
Current date and time: $now

STRICT OUTPUT RULES — each step output EXACTLY ONE JSON line, nothing else:

Step type A — call a tool (do NOT include "done"):
{"tool":"name","args":{"key":"val"}}

Step type B — final answer after all tools have run (do NOT include "tool"):
{"done":true,"message":"answer to user"}

NEVER combine "tool" and "done" in the same JSON.
NEVER guess or assume values — always call the tool and use its result.
NEVER output plain text — output ONLY a JSON line, nothing else.
IMPORTANT: "tool" value MUST be the EXACT name from the TOOLS list — no abbreviations, no variations.
After receiving a tool result, output {"done":true,"message":"..."} to finish unless another tool is needed.
Optional reasoning on the line before JSON: THOUGHT: ...

Example for "what is my battery level?":
{"tool":"get_battery","args":{}}
  → tool result comes back
{"done":true,"message":"Battery is 85%, charging."}

Example for "send sms to 555-1234 saying hello":
{"tool":"send_sms","args":{"number":"555-1234","message":"hello"}}
  → tool result comes back
{"done":true,"message":"SMS sent to 555-1234."}

Tool name rules — the "tool" value must always be copied EXACTLY from the TOOLS list:
  - Use full snake_case names: list_packages NOT list, take_screenshot NOT screenshot
  - Use exact spelling: send_sms NOT sendSms, get_battery NOT getBattery
  - When unsure, scan the TOOLS list and pick the name that best matches the user intent

Rules: one tool per step; report errors in done message.

TOOLS (? = optional):
${ToolDefinitions.toCompactText()}
""".trimIndent()
    }

    /**
     * System prompt for the QAIC Cloud backend.
     *
     * Intentionally simpler than [build] — there are NO JSON output rules and
     * NO inline tool list.  The cloud path uses the OpenAI-compatible native
     * function-calling API (tools + tool_choice in the request body).  The
     * model receives structured tool definitions via the API and returns
     * tool_calls objects; [OnDeviceAgent.processCloud] handles the loop.
     *
     * The agent's role description, device context, and date are still provided
     * so the model understands what it is controlling.
     */
    fun buildCloudPrompt(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return """
You are an AI agent controlling ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}).
Current date and time: $now

You have access to tools that can read and control this Android device.
Use them to fulfil the user's request accurately.
Always call the appropriate tool to retrieve live data rather than guessing.
After receiving all tool results, reply with a clear, concise answer.
""".trimIndent()
    }

    /**
     * Format a full conversation into the model-specific prompt string.
     *
     * @param systemContent  The system prompt text.
     * @param turns          Alternating user/assistant turns: list of (role, content) pairs.
     *                       role must be "user", "assistant", or "tool_result".
     * @return               Full prompt string ready to feed to the LLM.
     */
    fun formatPrompt(systemContent: String, turns: List<Pair<String, String>>): String {
        return when (llm.chatTemplate()) {
            ChatTemplate.LLAMA3  -> formatLlama3(systemContent, turns)
            ChatTemplate.GEMMA   -> formatGemma(systemContent, turns)
            ChatTemplate.OPENAI  -> formatOpenAI(systemContent, turns)
        }
    }

    // ── Template formatters ───────────────────────────────────────────────────

    private fun formatLlama3(sys: String, turns: List<Pair<String, String>>): String = buildString {
        append("<|begin_of_text|>")
        append("<|start_header_id|>system<|end_header_id|>\n\n$sys<|eot_id|>")
        for ((role, content) in turns) {
            when (role) {
                "user"        -> append("<|start_header_id|>user<|end_header_id|>\n\n$content<|eot_id|>")
                "assistant"   -> append("<|start_header_id|>assistant<|end_header_id|>\n\n$content<|eot_id|>")
                "tool_result" -> append("<|start_header_id|>tool<|end_header_id|>\n\n$content<|eot_id|>")
            }
        }
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun formatGemma(sys: String, turns: List<Pair<String, String>>): String = buildString {
        // Gemma has no explicit system role — prepend system content to first user turn
        var systemPrepended = false
        for ((role, content) in turns) {
            when (role) {
                "user" -> {
                    val text = if (!systemPrepended) { systemPrepended = true; "$sys\n\n$content" } else content
                    append("<start_of_turn>user\n$text<end_of_turn>\n")
                }
                "assistant"   -> append("<start_of_turn>model\n$content<end_of_turn>\n")
                "tool_result" -> append("<start_of_turn>user\nTool result: $content<end_of_turn>\n")
            }
        }
        if (!systemPrepended) append("<start_of_turn>user\n$sys<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    /**
     * OpenAI messages format for cloud inference (text-only / generateDirect path).
     * Returns a JSON array string: [{"role":"...","content":"..."},...]
     * QaicEngine.generate() deserialises this and sends it as messages[] to the REST API.
     *
     * "/no_think" prefix disables Qwen3 chain-of-thought at the prompt level.
     * This is a model-level instruction so it works even if the gateway does not
     * support the enable_thinking API parameter.
     *
     * Note: the native tool-calling path (processCloud) builds its messages array
     * directly as JsonObjects and does NOT go through this formatter.
     */
    private fun formatOpenAI(sys: String, turns: List<Pair<String, String>>): String {
        // /no_think must be the first token of the first USER message for Qwen3 to
        // suppress thinking mode. Putting it in the system message has no effect.
        var noThinkInjected = false
        val messages = buildJsonArray {
            addJsonObject { put("role", "system"); put("content", sys) }
            for ((role, content) in turns) {
                addJsonObject {
                    when (role) {
                        "user" -> {
                            val text = if (!noThinkInjected) {
                                noThinkInjected = true
                                "/no_think\n$content"
                            } else content
                            put("role", "user"); put("content", text)
                        }
                        "assistant"   -> { put("role", "assistant"); put("content", content) }
                        "tool_result" -> { put("role", "user");      put("content", "Tool result: $content") }
                    }
                }
            }
        }
        return messages.toString()
    }
}
