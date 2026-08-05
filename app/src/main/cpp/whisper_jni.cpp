#include <jni.h>
#include <cstring>
#include <string>
#include <android/log.h>

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct whisper_context {
    bool valid;
    whisper_context() : valid(true) {}
};

// Simplified JNI bridge for whisper.cpp
// In production, link against the actual whisper.cpp library

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_openvoice_stt_WhisperSttEngine_nativeInit(
    JNIEnv* env, jobject thiz, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    LOGI("whisper_init: %s", cpath);
    env->ReleaseStringUTFChars(path, cpath);
    auto* ctx = new whisper_context();
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_example_openvoice_stt_WhisperSttEngine_nativeTranscribe(
    JNIEnv* env, jobject thiz, jlong ptr, jshortArray audio, jint len) {
    if (ptr == 0) return env->NewStringUTF("");
    jshort* samples = env->GetShortArrayElements(audio, nullptr);

    // In production: call whisper_full()
    std::string result = "hello world";

    env->ReleaseShortArrayElements(audio, samples, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_openvoice_stt_WhisperSttEngine_nativeRelease(
    JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr != 0) {
        delete reinterpret_cast<whisper_context*>(ptr);
        LOGI("whisper context released");
    }
}

JNIEXPORT void JNICALL
Java_com_example_openvoice_stt_WhisperSttEngine_nativeResetState(
    JNIEnv* env, jobject thiz, jlong ptr) {
    LOGI("whisper reset state");
}

} // extern "C"
