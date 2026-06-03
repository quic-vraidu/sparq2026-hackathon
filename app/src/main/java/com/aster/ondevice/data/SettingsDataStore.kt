package com.aster.ondevice.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.aster.ondevice.llm.ChatTemplate
import com.aster.ondevice.llm.LlmBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "aster_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val MODEL_PATH        = stringPreferencesKey("model_path")
        val N_CTX             = intPreferencesKey("n_ctx")
        val N_THREADS         = intPreferencesKey("n_threads")
        val N_GPU_LAYERS      = intPreferencesKey("n_gpu_layers")
        val CHAT_TEMPLATE     = stringPreferencesKey("chat_template")
        val TEMPERATURE       = floatPreferencesKey("temperature")
        val TOP_P             = floatPreferencesKey("top_p")
        val TOP_K             = intPreferencesKey("top_k")
        val REPEAT_PENALTY    = floatPreferencesKey("repeat_penalty")
        // Phase 2
        val LLM_BACKEND       = stringPreferencesKey("llm_backend")
        val GENIE_CONFIG_PATH = stringPreferencesKey("genie_config_path")
        // Phase 3 — LiteRT-LM
        val LITERT_MODEL_PATH = stringPreferencesKey("litert_model_path")
        // Phase 4 — QAIC Cloud
        val QAIC_API_KEY      = stringPreferencesKey("qaic_api_key")
        val APIGEE_TOKEN      = stringPreferencesKey("apigee_token")
        val QAIC_BASE_URL     = stringPreferencesKey("qaic_base_url")
        val QAIC_MODEL        = stringPreferencesKey("qaic_model_id")
    }

    suspend fun getModelPath(): String = context.dataStore.data.first()[Keys.MODEL_PATH]
        ?: "/sdcard/Android/data/com.aster.ondevice/files/models/"
    suspend fun setModelPath(v: String) { context.dataStore.edit { it[Keys.MODEL_PATH] = v } }

    suspend fun getNCtx():        Int          = context.dataStore.data.first()[Keys.N_CTX]        ?: 4096
    suspend fun setNCtx(v: Int)                { context.dataStore.edit { it[Keys.N_CTX] = v } }

    suspend fun getNThreads():    Int          = context.dataStore.data.first()[Keys.N_THREADS]    ?: 4
    suspend fun setNThreads(v: Int)            { context.dataStore.edit { it[Keys.N_THREADS] = v } }

    suspend fun getNGpuLayers():  Int          = context.dataStore.data.first()[Keys.N_GPU_LAYERS] ?: 0
    suspend fun setNGpuLayers(v: Int)          { context.dataStore.edit { it[Keys.N_GPU_LAYERS] = v } }

    suspend fun getChatTemplate(): ChatTemplate {
        val name = context.dataStore.data.first()[Keys.CHAT_TEMPLATE] ?: ChatTemplate.LLAMA3.name
        return runCatching { ChatTemplate.valueOf(name) }.getOrDefault(ChatTemplate.LLAMA3)
    }
    suspend fun setChatTemplate(v: ChatTemplate) { context.dataStore.edit { it[Keys.CHAT_TEMPLATE] = v.name } }

    suspend fun getTemperature():  Float       = context.dataStore.data.first()[Keys.TEMPERATURE]   ?: 1.0f
    suspend fun setTemperature(v: Float)       { context.dataStore.edit { it[Keys.TEMPERATURE] = v } }

    suspend fun getTopP():         Float       = context.dataStore.data.first()[Keys.TOP_P]          ?: 0.95f
    suspend fun setTopP(v: Float)              { context.dataStore.edit { it[Keys.TOP_P] = v } }

    suspend fun getTopK():         Int         = context.dataStore.data.first()[Keys.TOP_K]          ?: 64
    suspend fun setTopK(v: Int)                { context.dataStore.edit { it[Keys.TOP_K] = v } }

    suspend fun getRepeatPenalty(): Float      = context.dataStore.data.first()[Keys.REPEAT_PENALTY] ?: 1.1f
    suspend fun setRepeatPenalty(v: Float)     { context.dataStore.edit { it[Keys.REPEAT_PENALTY] = v } }

    suspend fun getLlmBackend(): LlmBackend {
        val name = context.dataStore.data.first()[Keys.LLM_BACKEND] ?: LlmBackend.GENIE.name
        return runCatching { LlmBackend.valueOf(name) }.getOrDefault(LlmBackend.GENIE)
    }
    suspend fun setLlmBackend(v: LlmBackend) { context.dataStore.edit { it[Keys.LLM_BACKEND] = v.name } }

    suspend fun getGenieConfigPath(): String =
        context.dataStore.data.first()[Keys.GENIE_CONFIG_PATH]
            ?.takeIf { it.isNotBlank() }
            ?: "/sdcard/Android/data/com.aster.ondevice/files/models/qwen/genie_config.json"
    suspend fun setGenieConfigPath(v: String) { context.dataStore.edit { it[Keys.GENIE_CONFIG_PATH] = v } }

    suspend fun getLiteRtModelPath(): String  = context.dataStore.data.first()[Keys.LITERT_MODEL_PATH] ?: ""
    suspend fun setLiteRtModelPath(v: String) { context.dataStore.edit { it[Keys.LITERT_MODEL_PATH] = v } }

    // Phase 4 — QAIC Cloud
    suspend fun getQaicApiKey(): String   = context.dataStore.data.first()[Keys.QAIC_API_KEY]
        ?.takeIf { it.isNotBlank() } ?: "qaic_7kxsqY7r_uaG2SEyUvrXIHLf2a23TW4QETRO50P4F"
    suspend fun setQaicApiKey(v: String)  { context.dataStore.edit { it[Keys.QAIC_API_KEY] = v } }

    suspend fun getApigeeToken(): String  = context.dataStore.data.first()[Keys.APIGEE_TOKEN]
        ?.takeIf { it.isNotBlank() } ?: "kHAEOnGs3WCSeRt9Si5xRWvdDi4PbWavoDy27jNTuCkBmWgn"
    suspend fun setApigeeToken(v: String) { context.dataStore.edit { it[Keys.APIGEE_TOKEN] = v } }

    suspend fun getQaicBaseUrl(): String  = context.dataStore.data.first()[Keys.QAIC_BASE_URL]
        ?.takeIf { it.isNotBlank() } ?: "https://dev.apigwx-op.qualcomm.com/aips/sparq/api"
    suspend fun setQaicBaseUrl(v: String) { context.dataStore.edit { it[Keys.QAIC_BASE_URL] = v } }

    suspend fun getQaicModel(): String    = context.dataStore.data.first()[Keys.QAIC_MODEL]
        ?.takeIf { it.isNotBlank() } ?: "gpt-oss-20b"
    suspend fun setQaicModel(v: String)   { context.dataStore.edit { it[Keys.QAIC_MODEL] = v } }
}
