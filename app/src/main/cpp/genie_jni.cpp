// genie_jni.cpp — JNI bridge between Kotlin and Qualcomm Genie SDK
// Phase 2: NPU inference via libGenie.so + libQnnHtp.so on Snapdragon HTP.
//
// Kotlin class: com.aster.ondevice.llm.GenieEngine
// Native methods: nativeLoad, nativeInfer, nativeFree
//
// SDK files required (copy from Qualcomm AI Hub / QAIRT SDK):
//   app/src/main/cpp/genie/        — GenieCommon.h, GenieDialog.h
//   app/src/main/jniLibs/arm64-v8a/ — libGenie.so, libQnnHtp.so, libQnnHtpV81Stub.so, libQnnHtpV81Skel.so
//
// Model files on device (example layout):
//   /sdcard/Android/data/com.aster.ondevice/files/llama_3p2_3b/
//     genie_config.json
//     tokenizer.json
//     weight_sharing_model_1_of_2.serialized.bin
//     weight_sharing_model_2_of_2.serialized.bin
//     htp_backend_ext_config.json

#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstdarg>
#include <sys/stat.h>
#include "genie/GenieCommon.h"
#include "genie/GenieDialog.h"
#include "genie/GenieLog.h"

#define TAG "AsterGenie"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Genie state ──────────────────────────────────────────────────────────────

static GenieDialogConfig_Handle_t g_config    = nullptr;
static GenieDialog_Handle_t       g_dialog    = nullptr;
static GenieLog_Handle_t          g_log       = nullptr;

// ── Genie SDK log → Android logcat ───────────────────────────────────────────

static void genie_log_cb(const GenieLog_Handle_t /*handle*/,
                         const char* fmt,
                         GenieLog_Level_t level,
                         uint64_t /*timestamp*/,
                         va_list args)
{
    char buf[1024];
    vsnprintf(buf, sizeof(buf), fmt, args);
    switch (level) {
        case GENIE_LOG_LEVEL_ERROR:   __android_log_print(ANDROID_LOG_ERROR, "GenieSDK", "%s", buf); break;
        case GENIE_LOG_LEVEL_WARN:    __android_log_print(ANDROID_LOG_WARN,  "GenieSDK", "%s", buf); break;
        default:                      __android_log_print(ANDROID_LOG_INFO,  "GenieSDK", "%s", buf); break;
    }
}

// ── Per-inference callback (set/cleared around each GenieDialog_query call) ──

struct InferCb {
    JNIEnv*   env        = nullptr;
    jobject   cb         = nullptr;
    jmethodID invoke     = nullptr;
    int       tokenCount = 0;    // incremented per token to detect empty response
};
static InferCb g_infer_cb;   // single-threaded agent loop — no mutex needed

// ── Genie token callback ─────────────────────────────────────────────────────
// Called synchronously from GenieDialog_query on the calling thread.

static void genie_token_cb(const char* token,
                            const GenieDialog_SentenceCode_t /*code*/,
                            const void* /*userData*/)
{
    if (!token || !g_infer_cb.env || !g_infer_cb.cb || !g_infer_cb.invoke) return;
    g_infer_cb.tokenCount++;
    jstring jtoken = g_infer_cb.env->NewStringUTF(token);
    if (jtoken) {
        g_infer_cb.env->CallObjectMethod(g_infer_cb.cb, g_infer_cb.invoke, jtoken);
        g_infer_cb.env->DeleteLocalRef(jtoken);
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

static std::string jstr(JNIEnv* env, jstring js)
{
    if (!js) return "";
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// ── JNI: nativeLoad ──────────────────────────────────────────────────────────
// configJson   — full contents of the Genie JSON config file
// nativeLibDir — context.applicationInfo.nativeLibraryDir (where libGenie.so lives)
// modelDir     — directory containing model .bin files and tokenizer.json

extern "C" JNIEXPORT jint JNICALL
Java_com_aster_ondevice_llm_GenieEngine_nativeLoad(
        JNIEnv* env,
        jobject /* this */,
        jstring configJson,
        jstring nativeLibDir,
        jstring modelDir)
{
    std::string json    = jstr(env, configJson);
    std::string libDir  = jstr(env, nativeLibDir);
    std::string modDir  = jstr(env, modelDir);

    // ADSP_LIBRARY_PATH: Genie uses this to find the HTP Skel and model files.
    // Pattern from android-asr-llm-tts reference app: nativeLibDir + model dir.
    // System DSP paths are included so the firmware-matched Skel is preferred.
    std::string adspPath =
        "/vendor/dsp/cdsp;"
        "/vendor/lib/rfsa/adsp;"
        + libDir + ";"
        + modDir;

    setenv("ADSP_LIBRARY_PATH", adspPath.c_str(), 1);
    setenv("LD_LIBRARY_PATH",   adspPath.c_str(), 1);
    LOGI("ADSP_LIBRARY_PATH=%s", adspPath.c_str());

    // Free any previous session
    if (g_dialog) { GenieDialog_free(g_dialog);       g_dialog = nullptr; }
    if (g_config) { GenieDialogConfig_free(g_config); g_config = nullptr; }
    if (g_log)    { GenieLog_free(g_log);             g_log    = nullptr; }

    // Enable verbose Genie SDK logging → Android logcat (tag "GenieSDK")
    GenieLog_create(nullptr, genie_log_cb, GENIE_LOG_LEVEL_VERBOSE, &g_log);

    Genie_Status_t st = GenieDialogConfig_createFromJson(json.c_str(), &g_config);
    if (st != GENIE_STATUS_SUCCESS || !g_config) {
        LOGE("GenieDialogConfig_createFromJson failed (status=%d)", (int)st);
        return -1;
    }

    // ── Diagnostics: dump full config JSON + check ALL referenced files ──────
    // Log JSON in 512-char chunks so nothing is truncated.
    {
        for (size_t off = 0; off < json.size(); off += 512)
            LOGI("config[%zu]: %.512s", off, json.c_str() + off);
    }
    // Check every quoted path ending in a known extension.
    // For small .json files (< 8 KB) also dump their contents.
    {
        const char* exts[] = { ".bin\"", ".json\"", ".so\"", nullptr };
        int fileIdx = 0;
        for (int ei = 0; exts[ei]; ++ei) {
            size_t pos = 0;
            std::string ext(exts[ei]);
            while ((pos = json.find(ext, pos)) != std::string::npos) {
                size_t end   = pos + ext.size() - 1;
                size_t start = json.rfind('"', pos - 1);
                if (start != std::string::npos) {
                    std::string path = json.substr(start + 1, end - start - 1);
                    if (path.find("genie_config") == std::string::npos) {
                        struct stat st2{};
                        if (stat(path.c_str(), &st2) == 0) {
                            LOGI("  [ref %d] OK  (%lld B): %s", fileIdx, (long long)st2.st_size, path.c_str());
                            // Dump small JSON files so we can inspect soc_id etc.
                            if (ext == ".json\"" && st2.st_size < 8192) {
                                FILE* f = fopen(path.c_str(), "r");
                                if (f) {
                                    char buf[8192] = {};
                                    fread(buf, 1, sizeof(buf) - 1, f);
                                    fclose(f);
                                    LOGI("  [ref %d] contents: %s", fileIdx, buf);
                                }
                            }
                        } else {
                            LOGE("  [ref %d] MISSING: %s", fileIdx, path.c_str());
                        }
                        fileIdx++;
                    }
                }
                pos = end + 1;
            }
        }
        if (fileIdx == 0)
            LOGE("  No file paths found in config JSON");
    }

    st = GenieDialog_create(g_config, &g_dialog);
    if (st != GENIE_STATUS_SUCCESS || !g_dialog) {
        LOGE("GenieDialog_create failed (status=%d)", (int)st);
        GenieDialogConfig_free(g_config); g_config = nullptr;
        return -1;
    }

    LOGI("Genie loaded OK");
    return 0;
}

// ── JNI: nativeInfer ─────────────────────────────────────────────────────────
// prompt        — full formatted chat prompt
// maxTokens     — max new tokens to generate
// tokenCallback — kotlin (String) -> Unit  (streaming)

extern "C" JNIEXPORT jint JNICALL
Java_com_aster_ondevice_llm_GenieEngine_nativeInfer(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt,
        jint    maxTokens,
        jobject tokenCallback)
{
    if (!g_dialog) { LOGE("nativeInfer: not loaded"); return -1; }

    std::string p = jstr(env, prompt);

    // Set token budget for this call
    GenieDialog_setMaxNumTokens(g_dialog, (uint32_t)maxTokens);

    // Wire Kotlin lambda: Function1<String, Unit> → invoke(Object): Object
    jclass cbClass        = env->GetObjectClass(tokenCallback);
    g_infer_cb.env        = env;
    g_infer_cb.cb         = tokenCallback;
    g_infer_cb.invoke     = env->GetMethodID(cbClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(cbClass);

    LOGI("Genie inference start, maxTokens=%d  promptLen=%zu", (int)maxTokens, p.size());

    Genie_Status_t st = GenieDialog_query(
            g_dialog, p.c_str(),
            GENIE_DIALOG_SENTENCE_COMPLETE,
            genie_token_cb, nullptr);

    // Recovery: reset dialog and retry once on error OR empty output.
    // Reference app (chatapp_android GenieWrapper.cpp) does the same — Genie can
    // return SUCCESS with zero tokens after a few conversation turns due to KV
    // cache state corruption.
    bool emptyOutput = (st == GENIE_STATUS_SUCCESS && g_infer_cb.tokenCount == 0);
    if (st != GENIE_STATUS_SUCCESS || emptyOutput) {
        LOGE("GenieDialog_query %s (status=%d, tokens=%d) — resetting and retrying",
             emptyOutput ? "produced empty output" : "failed",
             (int)st, g_infer_cb.tokenCount);
        GenieDialog_reset(g_dialog);
        g_infer_cb.tokenCount = 0;
        st = GenieDialog_query(g_dialog, p.c_str(),
                               GENIE_DIALOG_SENTENCE_COMPLETE,
                               genie_token_cb, nullptr);
    }

    // Clear callback state
    g_infer_cb = {};

    if (st == GENIE_STATUS_SUCCESS) {
        LOGI("Genie inference OK");
        return 0;
    } else {
        LOGE("Genie inference failed (status=%d)", (int)st);
        return (jint)st;
    }
}

// ── JNI: nativeFree ──────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_aster_ondevice_llm_GenieEngine_nativeFree(
        JNIEnv* /* env */,
        jobject /* this */)
{
    if (g_dialog) { GenieDialog_free(g_dialog);       g_dialog = nullptr; }
    if (g_config) { GenieDialogConfig_free(g_config); g_config = nullptr; }
    if (g_log)    { GenieLog_free(g_log);             g_log    = nullptr; }
    LOGI("Genie freed");
}
