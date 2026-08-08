/*
 * llama_jni.cpp — JNI bridge between LlamaCppBridge.kt and upstream llama.cpp.
 *
 * This is a REAL integration. The llama.cpp sources are vendored as a pinned
 * Git submodule at vendor/llama.cpp and compiled statically by app/CMakeLists.txt
 * into libllama_bridge.so. Every native method below calls the upstream C API
 * from include/llama.h — there are no stubs.
 *
 * Targeted API: llama.cpp b10326 (see vendor/llama.cpp, pinned commit).
 * If the submodule is ever bumped, re-check this file against
 * vendor/llama.cpp/include/llama.h for renamed/removed functions.
 *
 * Notes:
 *  - The library ships only for arm64-v8a / armeabi-v7a (see abiFilters in
 *    app/build.gradle.kts). On other ABIs System.loadLibrary() fails in Kotlin
 *    and the engine falls back to the fakes used by instrumented tests.
 *  - Cancellation: llama_decode() is aborted through the context's abort
 *    callback (CPU-only execution supports it). The flag is a process-global
 *    atomic because the bridge is single-model at a time.
 *  - The Kotlin side only keeps the context pointer; the model pointer is
 *    tracked in a small registry so nativeRelease() can free both.
 *  - Chat prompts are pre-formatted by Kotlin (ChatML with <|im_start|> /
 *    <|im_end|>), so prompts are tokenized with parse_special=true and
 *    add_special=false (no synthetic BOS).
 */

#include <jni.h>
#include <cstring>
#include <ctime>
#include <functional>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global state ────────────────────────────────────────────────────────

static std::atomic<bool> g_cancel{false};
// Seed passed to nativeInit(); reused for sampling so generations are
// deterministic per load. Falls back to llama_time_us() when zero.
static std::atomic<uint32_t> g_seed{0};

static uint32_t sampling_seed() {
    const uint32_t s = g_seed.load(std::memory_order_relaxed);
    return s != 0 ? s : (uint32_t) llama_time_us();
}

// Model handle registry: context* -> model*. Keeps ownership of the model
// alive across the opaque context handle the Kotlin side stores.
static std::mutex g_models_mutex;
static std::unordered_map<llama_context*, llama_model*> g_models;

// Called by llama.cpp between ubatches; true aborts the current llama_decode().
static bool abort_callback(void*) {
    return g_cancel.load(std::memory_order_relaxed);
}

// ── Helpers ─────────────────────────────────────────────────────────────

static std::vector<llama_token> tokenize(const llama_vocab* vocab, const char* text) {
    if (vocab == nullptr || text == nullptr) return {};
    const int32_t n = llama_tokenize(vocab, text, (int32_t) strlen(text), nullptr, 0,
                                     /*add_special=*/false, /*parse_special=*/true);
    if (n <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    llama_tokenize(vocab, text, (int32_t) strlen(text), tokens.data(), n,
                   /*add_special=*/false, /*parse_special=*/true);
    return tokens;
}

// Single token -> UTF-8 piece (may be multi-byte).
static std::string token_to_piece(const llama_vocab* vocab, llama_token token) {
    char buf[64];
    const int32_t n = llama_token_to_piece(vocab, token, buf, (int32_t) sizeof(buf),
                                           /*lstrip=*/0, /*special=*/false);
    if (n <= 0) return "";
    return std::string(buf, static_cast<size_t>(n));
}

static bool ends_with_stop(const std::string& out, const std::vector<std::string>& stops) {
    for (const auto& s : stops) {
        if (s.empty() || out.size() < s.size()) continue;
        if (out.compare(out.size() - s.size(), s.size(), s) == 0) return true;
    }
    return false;
}

// Build the sampler chain (penalties -> top_k -> top_p -> temp -> dist/greedy).
static llama_sampler* build_sampler(const llama_vocab* vocab, uint32_t seed,
                                    float temperature, int32_t top_k, float top_p,
                                    float repeat_penalty) {
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler* chain = llama_sampler_chain_init(sparams);
    if (chain == nullptr) return nullptr;

    if (repeat_penalty > 0.0f && repeat_penalty != 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0.0f, 0.0f));
    }
    if (top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }
    return chain;
}

struct GenResult {
    std::string text;
    bool aborted = false;
};

// Shared generation loop for synchronous and streaming completion.
// on_token is invoked for every generated piece; when null the pieces are
// only accumulated.
static GenResult generate(llama_context* ctx, const std::string& prompt,
                          int32_t max_tokens, float temperature, int32_t top_k,
                          float top_p, float repeat_penalty, uint32_t seed,
                          const std::vector<std::string>& stops,
                          const std::function<void(const std::string&)>& on_token) {
    GenResult res;
    const llama_model* model = llama_get_model(ctx);
    const llama_vocab* vocab = model != nullptr ? llama_model_get_vocab(model) : nullptr;
    if (model == nullptr || vocab == nullptr) return res;

    std::vector<llama_token> prompt_tokens = tokenize(vocab, prompt.c_str());
    if (prompt_tokens.empty()) return res;

    g_cancel.store(false, std::memory_order_relaxed);

    llama_sampler* chain = build_sampler(vocab, seed, temperature, top_k, top_p, repeat_penalty);
    if (chain == nullptr) return res;

    // Feed the prompt, halving the batch if the context cannot fit it (rc == 1).
    size_t consumed = 0;
    int32_t chunk = 512;
    while (consumed < prompt_tokens.size()) {
        const int32_t n = std::min<int32_t>(chunk, (int32_t) (prompt_tokens.size() - consumed));
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + consumed, n);
        const int rc = llama_decode(ctx, batch);
        if (rc == 0) {
            consumed += (size_t) n;
        } else if (rc == 1 && chunk > 1) {
            chunk /= 2; // context too small for this batch — retry smaller
        } else {
            res.aborted = rc == 2;
            break;
        }
    }

    if (consumed == prompt_tokens.size() && !res.aborted) {
        for (int32_t i = 0; i < max_tokens && !g_cancel.load(std::memory_order_relaxed); i++) {
            llama_token id = llama_sampler_sample(chain, ctx, /*idx=*/-1);
            if (llama_vocab_is_eog(vocab, id)) break;
            llama_sampler_accept(chain, id);

            const std::string piece = token_to_piece(vocab, id);
            // Always advance the context with the sampled token, even when it
            // renders as an empty piece (control tokens), so the auto-tracked
            // positions stay in sync with the logits we sample from.
            llama_batch batch = llama_batch_get_one(&id, 1);
            const int rc = llama_decode(ctx, batch);
            if (rc == 1) break; // context full
            if (rc != 0) {
                res.aborted = rc == 2;
                break;
            }
            if (piece.empty()) continue;
            res.text += piece;
            if (on_token) on_token(piece);
            if (ends_with_stop(res.text, stops)) break;
        }
    }

    llama_sampler_free(chain);
    return res;
}

// Read the Java stop-token array into C++ strings.
static std::vector<std::string> read_stops(JNIEnv* env, jobjectArray stop) {
    std::vector<std::string> out;
    if (stop == nullptr) return out;
    const jsize n = env->GetArrayLength(stop);
    out.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(stop, i);
        if (s == nullptr) continue;
        const char* c = env->GetStringUTFChars(s, nullptr);
        if (c != nullptr) {
            out.emplace_back(c);
            env->ReleaseStringUTFChars(s, c);
        }
        env->DeleteLocalRef(s);
    }
    return out;
}

// ── JNI entry points (mirror LlamaCppBridge.kt declarations) ────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeInit(
    JNIEnv* env, jclass /*clazz*/, jstring path, jint n_threads, jint n_ctx, jlong seed) {
    static std::once_flag backend_flag;
    std::call_once(backend_flag, []() { llama_backend_init(); });

    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-only inference
    llama_model* model = llama_model_load_from_file(cpath, mparams);
    env->ReleaseStringUTFChars(path, cpath);
    if (model == nullptr) {
        LOGE("nativeInit: failed to load model from %s", cpath);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? (uint32_t) n_ctx : 0; // 0 = model default
    cparams.n_threads = n_threads > 0 ? n_threads : 1;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.abort_callback = abort_callback;
    cparams.abort_callback_data = nullptr;
    cparams.no_perf = true;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("nativeInit: failed to create context (ctx=%d, threads=%d)", n_ctx, n_threads);
        llama_model_free(model);
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(g_models_mutex);
        g_models[ctx] = model;
    }
    g_cancel.store(false, std::memory_order_relaxed);
    g_seed.store((uint32_t) seed, std::memory_order_relaxed);
    LOGI("nativeInit: model loaded (ctx=%d, threads=%d)", n_ctx, n_threads);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeComplete(
    JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring prompt,
    jint max_tokens, jfloat temperature, jint top_k, jfloat top_p,
    jfloat repeat_penalty, jobjectArray stop) {
    if (ptr == 0 || prompt == nullptr) return env->NewStringUTF("");

    const char* cprompt = env->GetStringUTFChars(prompt, nullptr);
    if (cprompt == nullptr) return env->NewStringUTF("");
    const std::string prompt_str(cprompt);
    env->ReleaseStringUTFChars(prompt, cprompt);

    const std::vector<std::string> stops = read_stops(env, stop);
    const GenResult res = generate(reinterpret_cast<llama_context*>(ptr), prompt_str,
                                   max_tokens, temperature, top_k, top_p,
                                   repeat_penalty, sampling_seed(), stops, nullptr);
    LOGI("nativeComplete: generated %zu chars%s", res.text.size(),
         res.aborted ? " (aborted)" : "");
    return env->NewStringUTF(res.text.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeCompleteStream(
    JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring prompt,
    jint max_tokens, jfloat temperature, jint top_k, jfloat top_p,
    jfloat repeat_penalty, jobjectArray stop, jobject callback) {
    if (ptr == 0 || prompt == nullptr) return;

    const char* cprompt = env->GetStringUTFChars(prompt, nullptr);
    if (cprompt == nullptr) return;
    const std::string prompt_str(cprompt);
    env->ReleaseStringUTFChars(prompt, cprompt);

    const std::vector<std::string> stops = read_stops(env, stop);

    // Kotlin lambda arrives as kotlin.jvm.functions.Function1.
    jclass cb_class = callback != nullptr ? env->GetObjectClass(callback) : nullptr;
    jmethodID cb_invoke = cb_class != nullptr
        ? env->GetMethodID(cb_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;")
        : nullptr;

    const GenResult res = generate(
        reinterpret_cast<llama_context*>(ptr), prompt_str,
        max_tokens, temperature, top_k, top_p, repeat_penalty,
        sampling_seed(), stops,
        [&](const std::string& piece) {
            if (cb_invoke == nullptr) return;
            // Delete the local ref each token — without this a 512-token
            // response overflows ART's local reference table (max=512).
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallObjectMethod(callback, cb_invoke, jpiece);
            env->DeleteLocalRef(jpiece);
            if (env->ExceptionCheck()) {
                env->ExceptionClear(); // stop generating; caller handles its own exceptions
                g_cancel.store(true, std::memory_order_relaxed);
            }
        });
    LOGI("nativeCompleteStream: streamed %zu chars%s", res.text.size(),
         res.aborted ? " (aborted)" : "");
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeEmbed(
    JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring text) {
    jfloatArray empty = env->NewFloatArray(0);
    if (ptr == 0 || text == nullptr) return empty;

    const char* ctext = env->GetStringUTFChars(text, nullptr);
    if (ctext == nullptr) return empty;
    const std::string text_str(ctext);
    env->ReleaseStringUTFChars(text, ctext);

    llama_context* ctx = reinterpret_cast<llama_context*>(ptr);
    const llama_model* model = llama_get_model(ctx);
    const llama_vocab* vocab = model != nullptr ? llama_model_get_vocab(model) : nullptr;
    const std::vector<llama_token> tokens = tokenize(vocab, text_str.c_str());
    if (tokens.empty()) return empty;

    g_cancel.store(false, std::memory_order_relaxed);
    // Clear the shared context first: repeated embed() calls (e.g. indexing a
    // memory store) would otherwise fill the KV cache and start failing.
    llama_memory_clear(llama_get_memory(ctx), /*data=*/false);
    llama_set_embeddings(ctx, /*embeddings=*/true);
    llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(tokens.data()),
                                            (int32_t) tokens.size());
    const int rc = llama_decode(ctx, batch);
    llama_set_embeddings(ctx, /*embeddings=*/false);
    if (rc != 0) return empty;

    const int32_t n_embd = llama_model_n_embd(model);
    float* emb = llama_get_embeddings_ith(ctx, 0);
    if (emb == nullptr) emb = llama_get_embeddings(ctx);
    if (emb == nullptr || n_embd <= 0) return empty;

    jfloatArray result = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(result, 0, n_embd, emb);
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeCancel(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong /*ptr*/) {
    g_cancel.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeRelease(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    g_cancel.store(false, std::memory_order_relaxed);
    if (ptr == 0) return;
    llama_context* ctx = reinterpret_cast<llama_context*>(ptr);

    llama_model* model = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_models_mutex);
        auto it = g_models.find(ctx);
        if (it != g_models.end()) {
            model = it->second;
            g_models.erase(it);
        }
    }
    llama_free(ctx);
    if (model != nullptr) llama_model_free(model);
    LOGI("nativeRelease: context and model freed");
}

JNIEXPORT jstring JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeBenchmark(
    JNIEnv* env, jclass /*clazz*/, jlong ptr, jint power, jstring format) {
    if (ptr == 0) return env->NewStringUTF("{\"status\":\"no_model\"}");

    llama_context* ctx = reinterpret_cast<llama_context*>(ptr);
    const llama_model* model = llama_get_model(ctx);
    const llama_vocab* vocab = model != nullptr ? llama_model_get_vocab(model) : nullptr;
    const char* cformat = format != nullptr ? env->GetStringUTFChars(format, nullptr) : nullptr;

    std::string bench = "{\"status\":\"benchmark_completed\",\"power\":%d,\"format\":\"%s\",\"tokens_per_second\":%.1f}";
    if (vocab == nullptr) {
        const std::string out = "{\"status\":\"no_vocab\"}";
        if (cformat != nullptr) env->ReleaseStringUTFChars(format, cformat);
        return env->NewStringUTF(out.c_str());
    }

    // ~64-token dummy prompt, warm up once, then time 8 single-token decodes.
    std::string dummy;
    for (int i = 0; i < 8; i++) dummy += "The quick brown fox jumps over the lazy dog. ";
    const std::vector<llama_token> tokens = tokenize(vocab, dummy.c_str());
    if (tokens.empty()) {
        if (cformat != nullptr) env->ReleaseStringUTFChars(format, cformat);
        return env->NewStringUTF("{\"status\":\"benchmark_failed\"}");
    }
    llama_batch warmup = llama_batch_get_one(const_cast<llama_token*>(tokens.data()),
                                             (int32_t) tokens.size());
    llama_decode(ctx, warmup);

    const int64_t t0 = llama_time_us();
    for (int i = 0; i < 8; i++) {
        llama_token tok = tokens[i % tokens.size()];
        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, b) != 0) break;
    }
    const int64_t dt = llama_time_us() - t0;
    const float tok_s = dt > 0 ? 8.0f / ((float) dt / 1e6f) : 0.0f;

    char buf[256];
    snprintf(buf, sizeof(buf), bench.c_str(), power,
             cformat != nullptr ? cformat : "gguf", tok_s);
    if (cformat != nullptr) env->ReleaseStringUTFChars(format, cformat);
    return env->NewStringUTF(buf);
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeMetadata(
    JNIEnv* env, jclass /*clazz*/, jlong ptr) {
    jclass string_class = env->FindClass("java/lang/String");
    std::vector<std::string> entries;
    if (ptr != 0) {
        llama_context* ctx = reinterpret_cast<llama_context*>(ptr);
        const llama_model* model = llama_get_model(ctx);
        if (model != nullptr) {
            const int32_t count = llama_model_meta_count(model);
            for (int32_t i = 0; i < count; i++) {
                char key[128];
                char val[2048];
                const int32_t kl = llama_model_meta_key_by_index(model, i, key, sizeof(key));
                const int32_t vl = llama_model_meta_val_str_by_index(model, i, val, sizeof(val));
                if (kl > 0 && vl >= 0) {
                    entries.emplace_back(std::string(key) + "=" + std::string(val));
                }
            }
            char desc[512];
            if (llama_model_desc(model, desc, sizeof(desc)) > 0) {
                entries.emplace_back(std::string("llama.model_desc=") + desc);
            }
            entries.emplace_back(std::string("llama.context_length=") +
                                 std::to_string(llama_n_ctx(ctx)));
        }
    }
    jobjectArray result = env->NewObjectArray((jsize) entries.size(), string_class, nullptr);
    for (size_t i = 0; i < entries.size(); i++) {
        env->SetObjectArrayElement(result, (jsize) i, env->NewStringUTF(entries[i].c_str()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeResetContext(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    if (ptr == 0) return;
    llama_context* ctx = reinterpret_cast<llama_context*>(ptr);
    llama_memory_clear(llama_get_memory(ctx), /*data=*/false);
}

JNIEXPORT jint JNICALL
Java_com_example_openvoice_ai_LlamaCppBridge_nativeTokenCount(
    JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring text) {
    if (ptr == 0 || text == nullptr) return 0;
    const char* ctext = env->GetStringUTFChars(text, nullptr);
    if (ctext == nullptr) return 0;

    const llama_model* model = llama_get_model(reinterpret_cast<llama_context*>(ptr));
    const llama_vocab* vocab = model != nullptr ? llama_model_get_vocab(model) : nullptr;
    const std::vector<llama_token> tokens = tokenize(vocab, ctext);
    env->ReleaseStringUTFChars(text, ctext);
    return (jint) tokens.size();
}

} // extern "C"
