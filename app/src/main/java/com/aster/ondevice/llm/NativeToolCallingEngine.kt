package com.aster.ondevice.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Optional extension for [LlmEngine] implementations that support the
 * OpenAI-compatible native tool-calling API (tools + tool_choice in the
 * /v1/chat/completions request body).
 *
 * On-device engines (Genie, LiteRT) do NOT implement this interface — they
 * rely on in-prompt JSON tool schemas and text-based parsing instead.
 *
 * [QaicEngine] implements this to leverage the cloud model's native function-
 * calling capability, producing structured [CloudToolCall] objects rather than
 * asking the model to emit hand-crafted JSON.
 */
interface NativeToolCallingEngine {

    /**
     * Send a conversation to the model with a native tools array.
     *
     * @param messages  Full message history as a JsonArray of OpenAI-format
     *                  message objects (role + content, or assistant with tool_calls,
     *                  or tool with tool_call_id).
     * @param tools     OpenAI function-calling tool definitions (type="function",
     *                  function.name / description / parameters).
     * @param maxNewTokens  Max tokens to generate.
     * @param temperature   Sampling temperature.
     * @param topP          Nucleus sampling p.
     * @return [CloudNativeResult] containing either a final text [content] or a
     *         list of [toolCalls] to execute (never both non-null).
     */
    fun generateNativeTools(
        messages: JsonArray,
        tools: JsonArray,
        maxNewTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.95f
    ): CloudNativeResult
}

/**
 * A single tool call returned by the cloud model.
 *
 * @param id         Opaque call ID from the API (must be echoed back in the
 *                   tool-result message as tool_call_id).
 * @param name       Exact tool name (matches [ToolDefinitions] entry).
 * @param arguments  Parsed argument map — ready to pass to [ToolDispatcher].
 */
data class CloudToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
)

/**
 * Result of one [NativeToolCallingEngine.generateNativeTools] call.
 *
 * Exactly one of [content] or [toolCalls] will be non-null/non-empty
 * in a successful response:
 *  - [toolCalls] non-empty → the model wants to call tool(s); execute them and
 *    add results to history before calling again.
 *  - [content] non-null   → the model produced a final answer; stop the loop.
 *  - Both null            → API error; [content] may carry the error string.
 *
 * [rawAssistantMessage] is the verbatim assistant message object from the API
 * response.  It must be appended to the messages history (with its tool_calls
 * array intact) before tool-result messages are added, so the model sees a
 * correctly ordered conversation on the next turn.
 */
data class CloudNativeResult(
    val content: String?,
    val toolCalls: List<CloudToolCall>?,
    val rawAssistantMessage: JsonObject
)
