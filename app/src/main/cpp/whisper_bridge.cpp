#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Function pointers resolved at runtime from libwhisper_android.so
typedef struct whisper_context_params (*fn_context_default_params)(void);
typedef struct whisper_context * (*fn_init_from_file_with_params)(const char *, struct whisper_context_params);
typedef void (*fn_free)(struct whisper_context *);
typedef struct whisper_full_params (*fn_full_default_params)(enum whisper_sampling_strategy);
typedef int (*fn_full)(struct whisper_context *, struct whisper_full_params, const float *, int);
typedef int (*fn_full_n_segments)(struct whisper_context *);
typedef const char * (*fn_full_get_segment_text)(struct whisper_context *, int);
typedef int64_t (*fn_full_get_segment_t0)(struct whisper_context *, int);
typedef int64_t (*fn_full_get_segment_t1)(struct whisper_context *, int);

static fn_context_default_params  p_context_default_params  = nullptr;
static fn_init_from_file_with_params p_init_from_file       = nullptr;
static fn_free                    p_free                    = nullptr;
static fn_full_default_params     p_full_default_params     = nullptr;
static fn_full                    p_full                    = nullptr;
static fn_full_n_segments         p_full_n_segments         = nullptr;
static fn_full_get_segment_text   p_full_get_segment_text   = nullptr;
static fn_full_get_segment_t0     p_full_get_segment_t0     = nullptr;
static fn_full_get_segment_t1     p_full_get_segment_t1     = nullptr;

static bool symbols_loaded = false;

static bool load_symbols() {
    if (symbols_loaded) return true;

    void *lib = dlopen("libwhisper_android.so", RTLD_NOW);
    if (!lib) {
        LOGE("dlopen failed: %s", dlerror());
        return false;
    }

    p_context_default_params = (fn_context_default_params)dlsym(lib, "whisper_context_default_params");
    p_init_from_file         = (fn_init_from_file_with_params)dlsym(lib, "whisper_init_from_file_with_params");
    p_free                   = (fn_free)dlsym(lib, "whisper_free");
    p_full_default_params    = (fn_full_default_params)dlsym(lib, "whisper_full_default_params");
    p_full                   = (fn_full)dlsym(lib, "whisper_full");
    p_full_n_segments        = (fn_full_n_segments)dlsym(lib, "whisper_full_n_segments");
    p_full_get_segment_text  = (fn_full_get_segment_text)dlsym(lib, "whisper_full_get_segment_text");
    p_full_get_segment_t0    = (fn_full_get_segment_t0)dlsym(lib, "whisper_full_get_segment_t0");
    p_full_get_segment_t1    = (fn_full_get_segment_t1)dlsym(lib, "whisper_full_get_segment_t1");

    if (!p_context_default_params || !p_init_from_file || !p_free ||
        !p_full_default_params || !p_full || !p_full_n_segments ||
        !p_full_get_segment_text) {
        LOGE("Failed to resolve whisper symbols");
        return false;
    }

    symbols_loaded = true;
    LOGI("Whisper symbols loaded successfully");
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_upcap_pipeline_WhisperBridge_nativeInit(JNIEnv *env, jobject thiz, jstring modelPath) {
    if (!load_symbols()) return 0;

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    struct whisper_context_params cparams = p_context_default_params();
    struct whisper_context *ctx = p_init_from_file(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to load whisper model");
        return 0;
    }

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_upcap_pipeline_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject thiz,
        jlong contextPtr, jfloatArray audioData, jstring language) {

    if (!symbols_loaded || contextPtr == 0) {
        return env->NewStringUTF("");
    }

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);

    jsize audio_len = env->GetArrayLength(audioData);
    jfloat *audio = env->GetFloatArrayElements(audioData, nullptr);

    const char *lang = env->GetStringUTFChars(language, nullptr);

    LOGI("Transcribing %d samples with language=%s", audio_len, lang);

    struct whisper_full_params wparams = p_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language        = lang;
    wparams.detect_language = false;
    wparams.translate       = false;
    wparams.print_progress  = false;
    wparams.print_realtime  = false;
    wparams.print_timestamps = false;
    wparams.no_context      = true;

    int result = p_full(ctx, wparams, audio, audio_len);

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);

    if (result != 0) {
        LOGE("Transcription failed: %d", result);
        return env->NewStringUTF("");
    }

    // Collect all segments
    int n_segments = p_full_n_segments(ctx);
    std::string text;
    for (int i = 0; i < n_segments; i++) {
        const char *seg = p_full_get_segment_text(ctx, i);
        if (seg) {
            text += seg;
            if (i < n_segments - 1) text += " ";
        }
    }

    LOGI("Transcription: %s", text.c_str());
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_upcap_pipeline_WhisperBridge_nativeRelease(JNIEnv *env, jobject thiz, jlong contextPtr) {
    if (!symbols_loaded || contextPtr == 0) return;

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    p_free(ctx);
    LOGI("Context freed");
}

} // extern "C"
