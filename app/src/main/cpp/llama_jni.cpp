#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * JNI bridge for llama.cpp.
 *
 * In production, link against libllama.a (from llama.cpp).
 * This stub provides the JNI interface that will call into llama.cpp.
 *
 * Build prerequisites:
 *   - llama.cpp as a Git submodule or vendored dependency
 *   - CMakeLists.txt linking libllama.a
 *   - NDK 25+
 *
 * Native methods correspond to LlamaCppBridge.kt declarations.
 */

// Forward declaration of llama.cpp types (replace with actual headers)
// struct llama_model;
// struct llama_context;
// typedef int llama_token;

// Stub for compilation without llama.cpp headers
struct llama_context { int dummy; };
using llama_token = int;

// Stub llama API (replace with real llama.cpp API)
extern "C" {
    struct llama_context* llama_init_from_file(const char* path, int n_ctx) {
        LOGI("llama_init_from_file: %s (ctx=%d)", path, n_ctx);
        return new llama_context();
    }
    int llama_eval(struct llama_context* ctx, llama_token* tokens, int n_tokens, int n_past, int n_threads) {
        return 0;
    }
    int llama_tokenize(struct llama_context* ctx, const char* text, llama_token* tokens, int max_tokens, bool add_bos) {
        int len = strlen(text);
        for (int i = 0; i < len && i < max_tokens; i++) {
            tokens[i] = static_cast<llama_token>(text[i]);
        }
        return len;
    }
    llama_token llama_token_bos() { return 1; }
    llama_token llama_token_eos() { return 2; }
    int llama_n_vocab(struct llama_context* ctx) { return 32000; }
    int llama_n_ctx(struct llama_context* ctx) { return 2048; }
    int llama_n_embd(struct llama_context* ctx) { return 4096; }
    const char* llama_token_to_str(struct llama_context* ctx, llama_token token) {
        static char buf[2] = { static_cast<char>(token), 0 };
        if (token < 128) return buf;
        return "�";
    }
    void llama_print_timings(struct llama_context* ctx) {}
    void llama_free(struct llama_context* ctx) { delete ctx; LOGI("llama context freed"); }
    void llama_reset_timings(struct llama_context* ctx) {}
    int llama_model_desc(struct llama_context* ctx, char* buf, size_t buf_size) {
        strncpy(buf, "stub model", buf_size);
        return 0;
    }
    const char* llama_model_type_name(int type) { return "stub"; }
    void llama_sample_temperature(float* logits, int n_vocab, float temp) {}
    int llama_sample_token(struct llama_context* ctx, float* logits, float temp) { return 0; }
}

extern "C" {

/*
 * Initialize a llama model from a GGUF file.
 */
JNIEXPORT jlong JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeInit(
    JNIEnv* env, jclass clazz, jstring path, jint n_threads, jint n_ctx, jlong seed) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    LOGI("nativeInit: %s (threads=%d, ctx=%d)", cpath, n_threads, n_ctx);
    auto* ctx = llama_init_from_file(cpath, n_ctx);
    env->ReleaseStringUTFChars(path, cpath);
    return reinterpret_cast<jlong>(ctx);
}

/*
 * Synchronous prompt completion.
 */
JNIEXPORT jstring JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeComplete(
    JNIEnv* env, jclass clazz, jlong ptr, jstring prompt,
    jint max_tokens, jfloat temperature, jint top_k, jfloat top_p,
    jfloat repeat_penalty, jobjectArray stop) {

    if (ptr == 0) return env->NewStringUTF("");
    auto* ctx = reinterpret_cast<llama_context*>(ptr);

    const char* cprompt = env->GetStringUTFChars(prompt, nullptr);
    LOGI("nativeComplete: \"%s\" (max=%d, temp=%.2f)", cprompt, max_tokens, temperature);

    // Tokenize prompt
    std::vector<llama_token> tokens(4096);
    int n_tokens = llama_tokenize(ctx, cprompt, tokens.data(), tokens.size() - 1, true);
    env->ReleaseStringUTFChars(prompt, cprompt);

    if (n_tokens <= 0) return env->NewStringUTF("");

    // Generate
    std::string result;
    int n_past = n_tokens;
    for (int i = 0; i < max_tokens; i++) {
        if (llama_eval(ctx, tokens.data(), n_tokens, n_past - n_tokens, 2) != 0) break;
        n_past += 1;
        // Simplified: in production, sample from logits
        llama_token id = llama_token_eos();
        if (id == llama_token_eos()) break;
        const char* str = llama_token_to_str(ctx, id);
        result += str;
        // Check stop tokens
        bool stop_found = false;
        for (const auto& stop_word : { "</s>", "<|im_end|>" }) {
            if (result.size() >= strlen(stop_word) &&
                result.substr(result.size() - strlen(stop_word)) == stop_word) {
                stop_found = true;
                break;
            }
        }
        if (stop_found) break;
        tokens[0] = id;
        n_tokens = 1;
    }

    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

/*
 * Streaming completion placeholder.
 * In production, use a callback to push tokens to Java.
 */
JNIEXPORT void JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeCompleteStream(
    JNIEnv* env, jclass clazz, jlong ptr, jstring prompt,
    jint max_tokens, jfloat temperature, jint top_k, jfloat top_p,
    jfloat repeat_penalty, jobjectArray stop, jobject callback) {

    if (ptr == 0) return;
    // In production: call callback->invoke(token) for each generated token
    // Simplified: just run complete and push the full result
    jstring full = Java_com_example_openvoice_ai_LlamaCppBridge_nativeComplete(
        env, clazz, ptr, prompt, max_tokens, temperature, top_k, top_p, repeat_penalty, stop);
    if (callback != nullptr && full != nullptr) {
        // Call the Kotlin callback
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID invoke = env->GetMethodID(cbClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
        if (invoke != nullptr) {
            env->CallObjectMethod(callback, invoke, full);
        }
    }
}

/*
 * Generate embedding vector.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeEmbed(
    JNIEnv* env, jclass clazz, jlong ptr, jstring text) {
    if (ptr == 0) return nullptr;
    auto* ctx = reinterpret_cast<llama_context*>(ptr);
    const char* ctext = env->GetStringUTFChars(text, nullptr);

    int n_embd = llama_n_embd(ctx);
    jfloatArray result = env->NewFloatArray(n_embd);
    if (result != nullptr) {
        jfloat fill[n_embd];
        for (int i = 0; i < n_embd; i++) fill[i] = 0.0f;
        env->SetFloatArrayRegion(result, 0, n_embd, fill);
    }
    env->ReleaseStringUTFChars(text, ctext);
    return result;
}

/*
 * Cancel generation.
 */
JNIEXPORT void JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeCancel(
    JNIEnv* env, jclass clazz, jlong ptr) {
    LOGI("nativeCancel");
    // In production: set a cancellation flag checked in the generation loop
}

/*
 * Release model and free context.
 */
JNIEXPORT void JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeRelease(
    JNIEnv* env, jclass clazz, jlong ptr) {
    if (ptr != 0) {
        llama_free(reinterpret_cast<llama_context*>(ptr));
        LOGI("nativeRelease");
    }
}

/*
 * Benchmark - returns JSON string with timing info.
 */
JNIEXPORT jstring JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeBenchmark(
    JNIEnv* env, jclass clazz, jlong ptr, jint power, jstring format) {
    return env->NewStringUTF("{\"status\":\"benchmark_completed\"}");
}

/*
 * Get model metadata as array of "key=value" strings.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeMetadata(
    JNIEnv* env, jclass clazz, jlong ptr) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(3, stringClass, nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF("general.architecture=llama"));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF("llama.context_length=2048"));
    env->SetObjectArrayElement(result, 2, env->NewStringUTF("general.file_type=Q4_K_M"));
    return result;
}

/*
 * Reset context state.
 */
JNIEXPORT void JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeResetContext(
    JNIEnv* env, jclass clazz, jlong ptr) {
    LOGI("nativeResetContext");
}

/*
 * Estimate token count for text.
 */
JNIEXPORT jint JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeTokenCount(
    JNIEnv* env, jclass clazz, jlong ptr, jstring text) {
    if (ptr == 0 || text == nullptr) return 0;
    const char* ctext = env->GetStringUTFChars(text, nullptr);
    int len = strlen(ctext);
    env->ReleaseStringUTFChars(text, ctext);
    return len / 4 + 1; // Rough estimate
}

} // extern "C"
