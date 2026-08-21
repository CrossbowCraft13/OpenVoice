#include <jni.h>
#include <cstring>
#include <string>
#include <vector>
#include <thread>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Real whisper.cpp integration. Upstream whisper.cpp is pinned as a Git
// submodule at vendor/whisper.cpp (tag v1.5.5) and compiled directly into
// this bridge by app/CMakeLists.txt (see the comment there for why the
// upstream CMake is not add_subdirectory()-ed).
//
// The JNI surface is unchanged from the original stub — nativeInit /
// nativeTranscribe / nativeRelease / nativeResetState — so
// WhisperSttEngine.kt needed no changes. Audio arrives as PCM16 at 16 kHz
// mono (AudioRecord) and is converted to the float samples whisper expects.
//
// TODO(first-version): language is hardcoded to "en" for reliable output
// with the English-only tiny/base models the app recommends. Exposing a
// language parameter (and translate/no_context options) through the JNI
// surface is a small follow-up.

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_crossbowcraft13_openvoice_stt_WhisperSttEngine_nativeInit(
    JNIEnv* env, jobject thiz, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;
    LOGI("whisper_init: %s", cpath);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context* ctx = whisper_init_from_file_with_params(cpath, cparams);
    env->ReleaseStringUTFChars(path, cpath);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed for %s", cpath);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_io_github_crossbowcraft13_openvoice_stt_WhisperSttEngine_nativeTranscribe(
    JNIEnv* env, jobject thiz, jlong ptr, jshortArray audio, jint len) {
    if (ptr == 0 || len <= 0) return env->NewStringUTF("");
    struct whisper_context* ctx = reinterpret_cast<struct whisper_context*>(ptr);

    jshort* samples = env->GetShortArrayElements(audio, nullptr);
    if (samples == nullptr) return env->NewStringUTF("");

    std::vector<float> pcm(static_cast<size_t>(len));
    for (jint i = 0; i < len; ++i) {
        pcm[static_cast<size_t>(i)] = samples[i] / 32768.0f;
    }
    env->ReleaseShortArrayElements(audio, samples, JNI_ABORT);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = std::max(1u, std::thread::hardware_concurrency());
    params.no_context = true;
    params.single_segment = false;

    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        LOGE("whisper_full failed");
        return env->NewStringUTF("");
    }

    std::string result;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) result += text;
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_io_github_crossbowcraft13_openvoice_stt_WhisperSttEngine_nativeRelease(
    JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr != 0) {
        whisper_free(reinterpret_cast<struct whisper_context*>(ptr));
        LOGI("whisper context released");
    }
}

JNIEXPORT void JNICALL
Java_io_github_crossbowcraft13_openvoice_stt_WhisperSttEngine_nativeResetState(
    JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr != 0) {
        whisper_reset_timings(reinterpret_cast<struct whisper_context*>(ptr));
        LOGI("whisper reset state");
    }
}

} // extern "C"
