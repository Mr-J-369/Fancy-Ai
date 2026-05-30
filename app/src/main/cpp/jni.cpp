// JNI bridge between Kotlin (com.mrj.fancyai.LlamaInference) and llama.cpp.
//
// Exposes 7 native entry points + 2 static callbacks (onToken/onDone). One model +
// context + sampler are held globally and guarded by a single mutex so model
// teardown can never race a running inference. Backend (CPU/Vulkan/OpenCL/Hexagon)
// is chosen at runtime by pinning the offload device in nativeLoadModel.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <malloc.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"

#define LOG_TAG "fancy_ai"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

// ── Global engine state ──────────────────────────────────────────────────────
llama_model   *g_model   = nullptr;
llama_context *g_ctx     = nullptr;
llama_sampler *g_sampler = nullptr;

// Tokens currently resident in the KV cache (seq 0): previous prompt + generated
// reply. Used for prompt caching — a new turn only re-decodes the tokens past the
// longest common prefix instead of re-prefilling the whole (huge) persona prompt.
std::vector<llama_token> g_prev_tokens;

std::atomic<bool> g_cancel{false};
// Serializes inference against model/context teardown. Teardown sets g_cancel,
// then takes this lock — guaranteeing the compute loop has exited first.
std::mutex g_infer_mutex;

JavaVM   *g_jvm          = nullptr;
jclass    g_cls          = nullptr;
jmethodID g_on_token_mid = nullptr;
jmethodID g_on_done_mid  = nullptr;

// Engine configuration (set by nativeSetEngineParams).
int  g_n_ctx      = 2048;
int  g_n_threads  = 4;
bool g_flash_attn = true;
bool g_use_mmap   = true;
bool g_use_mlock  = false;
int  g_gpu_layers = 0;
int  g_backend    = 0;   // 0=CPU, 1=NPU(Hexagon), 2=GPU(Vulkan/OpenCL)
int  g_kv_type    = 0;   // 0=F16, 1=Q8_0, 2=Q4_0

ggml_type kv_ggml_type(int t) {
    switch (t) {
        case 1:  return GGML_TYPE_Q8_0;
        case 2:  return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}

void free_context() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    g_prev_tokens.clear(); // KV cache is gone — the cached prefix is invalid
}

void free_model() {
    free_context();
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

bool create_context() {
    if (!g_model) return false;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = g_n_ctx;
    cp.n_threads       = g_n_threads;
    cp.n_threads_batch = g_n_threads;
    // Flash attention is required when the KV cache is quantized and expected by
    // the Hexagon HTP attention path; otherwise honor the user's setting.
    const bool flash = g_flash_attn || (g_kv_type != 0) || (g_backend == 1);
    cp.flash_attn_type = flash ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                               : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.type_k = kv_ggml_type(g_kv_type);
    cp.type_v = kv_ggml_type(g_kv_type);
    LOGI("create_context: ctx=%d threads=%d flash=%d kv=%d", g_n_ctx, g_n_threads, (int)flash, g_kv_type);
    g_ctx = llama_init_from_model(g_model, cp);
    return g_ctx != nullptr;
}

llama_sampler *make_sampler(float temp, int top_k, float top_p) {
    llama_sampler *s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(s, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(s, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(s, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return s;
}

// Route llama/ggml internal logs (incl. ggml-hexagon backend init) to logcat.
void log_to_logcat(enum ggml_log_level level, const char *text, void *) {
    if (!text) return;
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_write(prio, "ggml", text);
}

// Pin the offload to one device by name so layers aren't split across every
// registered GPU/NPU backend. Returns the chosen device or nullptr (→ CPU).
ggml_backend_dev_t pick_device(int backend) {
    ggml_backend_dev_t opencl = nullptr, vulkan = nullptr, hexagon = nullptr;
    for (size_t i = 0, n = ggml_backend_dev_count(); i < n; ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (!d) continue;
        const std::string name = ggml_backend_dev_name(d) ? ggml_backend_dev_name(d) : "";
        const std::string desc = ggml_backend_dev_description(d) ? ggml_backend_dev_description(d) : "";
        if (name.find("OpenCL") != std::string::npos)      opencl  = d;
        else if (name.find("Vulkan") != std::string::npos) vulkan  = d;
        if (name.find("HTP") != std::string::npos || desc.find("Hexagon") != std::string::npos) hexagon = d;
    }
    if (backend == 1) return hexagon;                       // NPU
    if (backend == 2) return opencl ? opencl : vulkan;      // GPU: prefer Adreno-native OpenCL
    return nullptr;
}

void emit_token(JNIEnv *env, int cb_id, const std::string &s) {
    jstring js = env->NewStringUTF(s.c_str());
    env->CallStaticVoidMethod(g_cls, g_on_token_mid, cb_id, js);
    env->DeleteLocalRef(js);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    llama_log_set(log_to_logcat, nullptr);
    ggml_log_set(log_to_logcat, nullptr);
#ifdef M_ARENA_MAX
    mallopt(M_ARENA_MAX, 1);
#endif
    setenv("ADSP_RPC_POWER_MODE", "burst", 1);

    // FastRPC opens the DSP skel/stub .so files by path; point its search dirs at
    // our extracted native lib dir so the Hexagon backend can find them.
    Dl_info info;
    if (dladdr(reinterpret_cast<void *>(&JNI_OnLoad), &info) && info.dli_fname) {
        std::string p(info.dli_fname);
        size_t slash = p.rfind('/');
        if (slash != std::string::npos) {
            std::string dir = p.substr(0, slash) + ";";
            setenv("ADSP_LIBRARY_PATH", dir.c_str(), 1);
            setenv("CDSP_LIBRARY_PATH", dir.c_str(), 1);
            setenv("DSP_LIBRARY_PATH",  dir.c_str(), 1);
            LOGI("JNI_OnLoad: FastRPC lib path = %s", dir.c_str());
            ggml_backend_load_all_from_path(p.substr(0, slash).c_str());
        }
    } else {
        ggml_backend_load_all();
    }

    llama_backend_init();
    LOGI("JNI_OnLoad: ready");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
    free_model();
    llama_backend_free();
    if (g_jvm && g_cls) {
        JNIEnv *env = nullptr;
        if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env)
            env->DeleteGlobalRef(g_cls);
    }
    g_cls = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeLoadModel(JNIEnv *env, jclass cls, jstring j_path) {
    if (!g_cls) {
        g_cls = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
        g_on_token_mid = env->GetStaticMethodID(cls, "onToken", "(ILjava/lang/String;)V");
        g_on_done_mid  = env->GetStaticMethodID(cls, "onDone",  "(I)V");
        if (!g_on_token_mid || !g_on_done_mid) LOGE("callback methods not found on LlamaInference");
    }

    const char *c_path = env->GetStringUTFChars(j_path, nullptr);
    if (!c_path) return JNI_FALSE;
    std::string path(c_path);
    env->ReleaseStringUTFChars(j_path, c_path);

    // Cancel + wait for any in-flight inference, then tear down.
    g_cancel.store(true);
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    free_model();
    g_cancel.store(false);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = g_gpu_layers;
    mp.use_mmap     = g_use_mmap;
    mp.use_mlock    = g_use_mlock;

    // The Hexagon backend repacks weights into device buffers, incompatible with
    // mmap — the official Snapdragon runs use --no-mmap. Force it off for NPU.
    if (g_backend == 1) { mp.use_mmap = false; LOGI("NPU: forcing use_mmap=false"); }

    // Pin the offload device (keeps layers off backends the user didn't pick).
    // `devices` must outlive llama_model_load_from_file().
    std::vector<ggml_backend_dev_t> devices;
    if (g_gpu_layers > 0 && g_backend != 0) {
        if (ggml_backend_dev_t dev = pick_device(g_backend)) {
            devices.push_back(dev);
            devices.push_back(nullptr);
            mp.devices = devices.data();
            LOGI("offload pinned to %s (backend=%d)", ggml_backend_dev_name(dev), g_backend);
        } else {
            LOGW("backend=%d requested but no matching device — using CPU", g_backend);
        }
    }

    LOGI("loading model: %s  ctx=%d threads=%d gpu=%d backend=%d", path.c_str(), g_n_ctx, g_n_threads, g_gpu_layers, g_backend);
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { LOGE("llama_model_load_from_file failed"); return JNI_FALSE; }

    char desc[512] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    LOGI("model loaded: %s", desc);

    if (!create_context()) {
        LOGE("create_context failed");
        free_model();
        return JNI_FALSE;
    }
    g_sampler = make_sampler(0.7f, 40, 0.9f);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeUnloadModel(JNIEnv *, jclass) {
    g_cancel.store(true);
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    free_model();
    LOGI("model unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeGetChatTemplate(JNIEnv *env, jclass) {
    if (!g_model) return env->NewStringUTF("");
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    return env->NewStringUTF(tmpl ? tmpl : "");
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeSetEngineParams(JNIEnv *, jclass,
        jint n_ctx, jint n_threads, jboolean flash_attn, jboolean use_mmap,
        jboolean use_mlock, jint gpu_layers, jint backend, jint kv_type) {
    g_n_ctx      = n_ctx;
    g_n_threads  = n_threads;
    g_flash_attn = flash_attn;
    g_use_mmap   = use_mmap;
    g_use_mlock  = use_mlock;
    g_gpu_layers = gpu_layers;
    g_backend    = backend;
    g_kv_type    = kv_type;
    LOGI("engine params: ctx=%d threads=%d flash=%d mmap=%d mlock=%d gpu=%d backend=%d kv=%d",
         g_n_ctx, g_n_threads, (int)g_flash_attn, (int)g_use_mmap, (int)g_use_mlock, g_gpu_layers, g_backend, g_kv_type);
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeReinitContext(JNIEnv *, jclass) {
    if (!g_model) return JNI_TRUE; // params stored; applied on next load
    g_cancel.store(true);
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    free_context();
    if (!create_context()) { LOGE("reinit: create_context failed"); return JNI_FALSE; }
    g_sampler = make_sampler(0.7f, 40, 0.9f);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeInferenceStream(JNIEnv *env, jclass,
        jstring j_prompt, jint max_tokens, jint cb_id,
        jfloat temperature, jint top_k, jfloat top_p) {
    // Hold the lock for the whole generation: blocks teardown and serializes
    // concurrent inferences (which would corrupt the shared context/sampler).
    std::lock_guard<std::mutex> lock(g_infer_mutex);

    if (!g_model || !g_ctx) {
        LOGE("inference: no model loaded");
        if (g_cls && g_on_done_mid) env->CallStaticVoidMethod(g_cls, g_on_done_mid, cb_id);
        return;
    }
    if (!g_cls || !g_on_token_mid || !g_on_done_mid) { LOGE("inference: callbacks missing"); return; }

    const char *c_prompt = env->GetStringUTFChars(j_prompt, nullptr);
    if (!c_prompt) { env->CallStaticVoidMethod(g_cls, g_on_done_mid, cb_id); return; }
    std::string prompt(c_prompt);
    env->ReleaseStringUTFChars(j_prompt, c_prompt);

    if (g_sampler) llama_sampler_free(g_sampler);
    g_sampler = make_sampler(temperature, top_k, top_p);

    LOGI("inference start cbId=%d max=%d temp=%.2f k=%d p=%.2f", (int)cb_id, (int)max_tokens, temperature, (int)top_k, top_p);
    g_cancel.store(false);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // Tokenize. add_special=false: our prompt templates already include BOS/start.
    const int n_tok = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, false, true);
    if (n_tok <= 0) {
        LOGE("tokenize failed: %d", n_tok);
        env->CallStaticVoidMethod(g_cls, g_on_done_mid, cb_id);
        return;
    }
    std::vector<llama_token> tokens(n_tok);
    if (llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), n_tok, false, true) < 0) {
        LOGE("tokenize failed (fill)");
        env->CallStaticVoidMethod(g_cls, g_on_done_mid, cb_id);
        return;
    }
    LOGI("prompt tokenized: %d tokens from %zu chars", n_tok, prompt.size());

    // ── Prompt caching ──────────────────────────────────────────────────────────
    // Reuse the KV prefix shared with the previous turn: find the longest common
    // prefix of the new prompt and the resident sequence, drop the divergent tail
    // from the KV cache, and only decode the new suffix. The big static persona
    // prefix is then prefilled once, not on every message.
    llama_memory_t mem = llama_get_memory(g_ctx);
    int n_keep = 0;
    const int max_keep = std::min((int)g_prev_tokens.size(), n_tok);
    while (n_keep < max_keep && g_prev_tokens[n_keep] == tokens[n_keep]) ++n_keep;
    if (n_keep >= n_tok) n_keep = n_tok - 1;   // always leave ≥1 token to get fresh logits
    if (n_keep < 0) n_keep = 0;

    if (n_keep == 0) {
        llama_memory_clear(mem, true);
    } else {
        llama_memory_seq_rm(mem, 0, n_keep, -1); // keep positions [0, n_keep)
    }
    LOGI("kv reuse: cached %d/%d prompt tokens, prefilling %d new", n_keep, n_tok, n_tok - n_keep);

    llama_perf_context_reset(g_ctx);

    // GPU backends (Adreno Vulkan/OpenCL) can throw from inside llama_decode on a
    // driver error; catch so the turn ends gracefully instead of SIGABRT-ing.
    try {
        // Chunked prefill of the NEW suffix so Stop is honored during prefill.
        bool ok = true;
        const int chunk = 128;
        for (int start = n_keep; start < n_tok; start += chunk) {
            if (g_cancel.load()) { ok = false; break; }
            int n = std::min(chunk, n_tok - start);
            if (llama_decode(g_ctx, llama_batch_get_one(tokens.data() + start, n)) != 0) {
                LOGE("decode (prompt) failed at %d", start);
                ok = false;
                break;
            }
        }

        if (ok && !g_cancel.load()) {
            const int n_max = max_tokens > 0 ? max_tokens : 512;
            char piece[256];
            std::string batch;       // coalesce tokens to cut JNI calls
            const int batch_n = 5;

            for (int i = 0; i < n_max && !g_cancel.load(); ++i) {
                llama_token id = llama_sampler_sample(g_sampler, g_ctx, -1);
                if (llama_vocab_is_eog(vocab, id)) {
                    LOGD("EOG at step %d", i);
                    if (!batch.empty()) emit_token(env, cb_id, batch);
                    break;
                }
                tokens.push_back(id); // grow the resident sequence for next-turn caching
                int len = llama_token_to_piece(vocab, id, piece, (int)sizeof(piece) - 1, 0, true);
                if (len < 0) len = 0;
                piece[len] = '\0';
                batch.append(piece);

                if (batch.size() > 64 || (i + 1) % batch_n == 0) {
                    emit_token(env, cb_id, batch);
                    batch.clear();
                }
                if (llama_decode(g_ctx, llama_batch_get_one(&id, 1)) != 0) {
                    LOGE("decode failed at step %d", i);
                    break;
                }
            }
            if (!batch.empty()) emit_token(env, cb_id, batch);

            // Remember the resident sequence (prompt + reply) so the next turn can
            // reuse this prefix. The KV cache is intentionally NOT cleared.
            g_prev_tokens = tokens;
            llama_perf_context_print(g_ctx); // logs prompt-eval (prefill) + eval (gen) tok/s
        } else {
            g_prev_tokens.clear(); // cancelled / prefill error → force clean prefill next time
        }
    } catch (const std::exception &e) {
        LOGE("inference aborted: %s", e.what());
        g_prev_tokens.clear();
    } catch (...) {
        LOGE("inference aborted: unknown backend exception");
        g_prev_tokens.clear();
    }

    LOGI("inference done cbId=%d", (int)cb_id);
    env->CallStaticVoidMethod(g_cls, g_on_done_mid, cb_id);
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeCancelInference(JNIEnv *, jclass) {
    g_cancel.store(true);
    LOGI("cancel requested");
}

} // extern "C"
