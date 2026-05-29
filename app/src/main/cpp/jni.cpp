#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <exception>
#include <mutex>
#include <dlfcn.h>
#include <malloc.h>
#include <sys/mman.h>
#include <android/log.h>
#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"

#define LOG_TAG "fancy_ai"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// ── Global model/context state ───────────────────────────────────────────────
static llama_model   * g_model   = nullptr;
static llama_context * g_ctx     = nullptr;
static llama_sampler * g_sampler = nullptr;

static std::atomic<bool> g_cancel{false};

// Serializes the compute (nativeInferenceStream) against model teardown/build
// (load/unload/reinit). Without this, navigating Home mid-reply would free the
// context while the inference thread is still using it → use-after-free crash.
// Teardown sets g_cancel first, then waits on this lock for inference to exit.
static std::mutex g_infer_mutex;

static JavaVM*    g_jvm           = nullptr;
static jclass     g_inference_cls = nullptr;
static jmethodID  g_on_token_mid  = nullptr;
static jmethodID  g_on_done_mid   = nullptr;

// ── Engine configuration (updated by nativeSetEngineParams) ──────────────────
static int   g_n_ctx      = 2048;
static int   g_n_threads  = 4;
static bool  g_flash_attn = true;
static bool  g_use_mmap   = true;
static bool  g_use_mlock  = false;
static int   g_gpu_layers = 0;
// Offload target: 0 = CPU, 1 = NPU (Hexagon/HTP), 2 = GPU (Vulkan/Adreno).
// Used to pin llama_model_params.devices so offload targets exactly one
// accelerator instead of being split across every registered GPU device.
static int   g_backend    = 0;
// KV cache data type: 0 = F16 (default/best quality), 1 = Q8_0, 2 = Q4_0.
// Quantized KV roughly halves/quarters cache memory so longer context fits.
static int   g_kv_type    = 0;

static ggml_type kv_ggml_type(int t) {
    switch (t) {
        case 1:  return GGML_TYPE_Q8_0;
        case 2:  return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}

// ── Internal helpers ─────────────────────────────────────────────────────────
static void free_context_only() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
}

static void free_model_state() {
    free_context_only();
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

static bool create_context() {
    if (!g_model) return false;
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = g_n_ctx;
    cparams.n_threads       = g_n_threads;
    cparams.n_threads_batch = g_n_threads;
    // Flash attention is forced on when (a) the KV cache is quantized (llama.cpp
    // requires it) or (b) the Hexagon NPU is the target — the HTP backend's
    // attention path expects FA on (see docs/backend/snapdragon: `-fa on`).
    bool flash = g_flash_attn || (g_kv_type != 0) || (g_backend == 1);
    cparams.flash_attn_type = flash
        ? LLAMA_FLASH_ATTN_TYPE_ENABLED
        : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cparams.type_k = kv_ggml_type(g_kv_type);
    cparams.type_v = kv_ggml_type(g_kv_type);
    LOGI("create_context: ctx=%d threads=%d flash=%d kv_type=%d",
         g_n_ctx, g_n_threads, (int)flash, g_kv_type);
    g_ctx = llama_init_from_model(g_model, cparams);
    return g_ctx != nullptr;
}

// Route llama.cpp / ggml internal logs (including ggml-hex) to Android logcat
// so we can see backend init failures from within the app.
static void ggml_to_logcat(enum ggml_log_level level, const char * text, void *) {
    if (!text) return;
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_INFO;  break;
    }
    __android_log_write(prio, "ggml", text);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    LOGI("JNI_OnLoad: starting");
    g_jvm = vm;
    llama_log_set(ggml_to_logcat, nullptr);
    ggml_log_set(ggml_to_logcat, nullptr);

    // [OPTIMIZATION] Limit malloc arenas
#ifdef M_ARENA_MAX
    mallopt(M_ARENA_MAX, 1);
#endif

    setenv("ADSP_RPC_POWER_MODE", "burst", 1);

    std::string libDir;
    Dl_info info;
    if (dladdr((void*) &JNI_OnLoad, &info) && info.dli_fname) {
        std::string p(info.dli_fname);
        size_t s = p.rfind('/');
        if (s != std::string::npos) {
            libDir = p.substr(0, s);
            std::string dir = libDir + ";";
            setenv("ADSP_LIBRARY_PATH", dir.c_str(), 1);
            setenv("CDSP_LIBRARY_PATH", dir.c_str(), 1);
            setenv("DSP_LIBRARY_PATH",  dir.c_str(), 1);
            LOGI("JNI_OnLoad: FastRPC paths set to %s", dir.c_str());
        }
    }

    if (!libDir.empty()) {
        LOGI("JNI_OnLoad: loading backends from %s", libDir.c_str());
        ggml_backend_load_all_from_path(libDir.c_str());
    } else {
        LOGI("JNI_OnLoad: loading backends from default paths");
        ggml_backend_load_all();
    }

    LOGI("JNI_OnLoad: llama_backend_init()");
    llama_backend_init();
    LOGI("JNI_OnLoad: completed successfully");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    free_model_state();
    llama_backend_free();
    if (g_jvm && g_inference_cls) {
        JNIEnv* env;
        g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (env) env->DeleteGlobalRef(g_inference_cls);
    }
    g_inference_cls = nullptr;
}

// ── Model loading ─────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeLoadModel(JNIEnv* env, jclass cls,
                                                    jstring j_path) {
    if (!g_inference_cls) {
        g_inference_cls = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
        g_on_token_mid = env->GetStaticMethodID(cls, "onToken", "(ILjava/lang/String;)V");
        g_on_done_mid  = env->GetStaticMethodID(cls, "onDone",  "(I)V");
        if (!g_on_token_mid || !g_on_done_mid)
            LOGE("Failed to find callback methods on LlamaInference");
    }

    const char* path = env->GetStringUTFChars(j_path, nullptr);
    if (!path) return JNI_FALSE;
    std::string model_path(path);
    env->ReleaseStringUTFChars(j_path, path);

    // Cancel + wait for any in-flight inference before tearing down / rebuilding.
    g_cancel.store(true);
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    free_model_state();
    g_cancel.store(false);

    LOGI("Loading model: %s  ctx=%d threads=%d flash=%d mmap=%d mlock=%d gpu=%d",
         model_path.c_str(), g_n_ctx, g_n_threads,
         (int)g_flash_attn, (int)g_use_mmap, (int)g_use_mlock, g_gpu_layers);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = g_gpu_layers;
    mparams.use_mmap     = g_use_mmap;
    mparams.use_mlock    = g_use_mlock;

    // The Hexagon NPU repacks weights (Q4_0/Q8_0/MXFP4) into device buffers, which
    // is incompatible with mmap — the official Snapdragon runs all use --no-mmap.
    // Force it off for NPU so the repack buffers allocate correctly.
    if (g_backend == 1) {
        mparams.use_mmap = false;
        LOGI("NPU backend: forcing use_mmap=false for HTP weight repacking");
    }

    // Pin the offload target to a single device so layers don't get split across
    // every registered GPU-type backend (e.g. Vulkan + Hexagon both present). The
    // experimental Hexagon backend stalls (FastRPC 0xc) on k-quants, so leaving the
    // device list unset would let it grab layers even when the user picked GPU.
    // `selected` must outlive the llama_model_load_from_file() call below.
    std::vector<ggml_backend_dev_t> selected;
    if (g_gpu_layers > 0 && g_backend != 0) {
        size_t ndev = ggml_backend_dev_count();
        ggml_backend_dev_t opencl_dev = nullptr, vulkan_dev = nullptr, hexagon_dev = nullptr;
        for (size_t i = 0; i < ndev; ++i) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            if (!d) continue;
            const char* nm = ggml_backend_dev_name(d);
            const char* ds = ggml_backend_dev_description(d);
            std::string name = nm ? nm : "";
            std::string desc = ds ? ds : "";
            // Device names: OpenCL = "GPUOpenCL", Vulkan = "Vulkan0", Hexagon = "HTP0".
            if (name.find("OpenCL") != std::string::npos)       opencl_dev  = d;
            else if (name.find("Vulkan") != std::string::npos)  vulkan_dev  = d;
            if (name.find("HTP") != std::string::npos ||
                desc.find("Hexagon") != std::string::npos)      hexagon_dev = d;
        }
        // backend 1 = NPU (Hexagon); 2 = GPU — prefer OpenCL (Adreno-native, stable),
        // fall back to Vulkan (generic, flaky on Adreno but works on other GPUs).
        ggml_backend_dev_t chosen = nullptr;
        if (g_backend == 1)       chosen = hexagon_dev;
        else if (g_backend == 2)  chosen = opencl_dev ? opencl_dev : vulkan_dev;

        if (chosen) {
            selected.push_back(chosen);
            selected.push_back(nullptr); // null-terminate the device list
            mparams.devices = selected.data();
            LOGI("Offload pinned to %s (backend=%d)",
                 ggml_backend_dev_name(chosen), g_backend);
        } else {
            LOGE("backend=%d requested but no matching device found — using CPU", g_backend);
        }
    }

    // STRATEGY: llama_model_load_from_file uses mmap() by default when use_mmap is true.
    // mmap() allocations are NOT tracked by ART's NativeAlloc counter, thus avoiding
    // blocking GC stalls during model load.
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!g_model) {
        LOGE("llama_model_load_from_file failed for: %s", model_path.c_str());
        return JNI_FALSE;
    }

    char model_desc_buf[512] = {0};
    llama_model_desc(g_model, model_desc_buf, sizeof(model_desc_buf));
    LOGI("Model loaded successfully - %s", model_desc_buf);

    if (!create_context()) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeUnloadModel(JNIEnv* env, jclass cls) {
    LOGI("Unloading model");
    g_cancel.store(true);                              // signal any running inference to stop
    std::lock_guard<std::mutex> lock(g_infer_mutex);   // wait until it actually has
    free_model_state();
    LOGI("Model unloaded");
}

// Returns the model's embedded chat template (Jinja string), or "" if none.
// Used to auto-detect the correct prompt format instead of guessing from the
// file name — a wrong template means the model never emits its stop token.
JNIEXPORT jstring JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeGetChatTemplate(JNIEnv* env, jclass cls) {
    if (!g_model) return env->NewStringUTF("");
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    return env->NewStringUTF(tmpl ? tmpl : "");
}

// ── Engine parameter update (applies immediately without model reload) ────────
// Context-level params (n_ctx, n_threads, flash_attn) take effect via
// nativeReinitContext. Model-level params (mmap, mlock) take effect on
// the next nativeLoadModel call.
JNIEXPORT void JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeSetEngineParams(JNIEnv* env, jclass cls,
    jint n_ctx, jint n_threads, jboolean flash_attn,
    jboolean use_mmap, jboolean use_mlock, jint gpu_layers, jint backend, jint kv_type)
{
    g_n_ctx      = (int)n_ctx;
    g_n_threads  = (int)n_threads;
    g_flash_attn = (bool)flash_attn;
    g_use_mmap   = (bool)use_mmap;
    g_use_mlock  = (bool)use_mlock;
    g_gpu_layers = (int)gpu_layers;
    g_backend    = (int)backend;
    g_kv_type    = (int)kv_type;
    LOGI("Engine params updated: ctx=%d threads=%d flash=%d mmap=%d mlock=%d gpu=%d backend=%d kv=%d",
         g_n_ctx, g_n_threads, (int)g_flash_attn, (int)g_use_mmap, (int)g_use_mlock, g_gpu_layers, g_backend, g_kv_type);
}

// Recreates the context with current engine params. Call after nativeSetEngineParams
// when a model is already loaded, so changes to n_ctx/n_threads/flash_attn apply
// without reloading the model weights.
JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeReinitContext(JNIEnv* env, jclass cls)
{
    if (!g_model) {
        LOGI("nativeReinitContext: no model loaded — params stored for next load");
        return JNI_TRUE;
    }
    // Cancel + wait for in-flight inference before freeing/recreating the context.
    g_cancel.store(true);
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    free_context_only();
    if (!create_context()) {
        LOGE("nativeReinitContext: create_context failed");
        return JNI_FALSE;
    }
    LOGI("Context reinitialized: ctx=%d threads=%d flash=%d",
         g_n_ctx, g_n_threads, (int)g_flash_attn);
    return JNI_TRUE;
}

// ── Streaming inference ───────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeInferenceStream(JNIEnv* env, jclass cls,
                                                          jstring j_prompt,
                                                          jint max_tokens,
                                                          jint cb_id,
                                                          jfloat temperature,
                                                          jint top_k,
                                                          jfloat top_p) {
    // Hold the inference lock for the entire generation. This (a) keeps load/unload/
    // reinit from freeing the model/context out from under us, and (b) serializes
    // concurrent inferences, which would otherwise corrupt the shared context/sampler.
    std::lock_guard<std::mutex> lock(g_infer_mutex);
    if (!g_model || !g_ctx) {
        LOGE("nativeInferenceStream: model not loaded");
        if (g_inference_cls && g_on_done_mid)
            env->CallStaticVoidMethod(g_inference_cls, g_on_done_mid, cb_id);
        return;
    }
    if (!g_inference_cls || !g_on_token_mid || !g_on_done_mid) {
        LOGE("nativeInferenceStream: callbacks not cached");
        return;
    }

    const char* prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    if (!prompt_cstr) return;
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    // Rebuild sampler with per-call parameters
    if (g_sampler) { llama_sampler_free(g_sampler); }
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Inference start cbId=%d max=%d temp=%.2f k=%d p=%.2f",
         (int)cb_id, (int)max_tokens, temperature, (int)top_k, top_p);
    g_cancel.store(false);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Tokenize
    // We set add_special to false because our JS templates (ChatML, Llama3, Gemma)
    // already include the appropriate BOS/start tokens.
    const int n_len = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                                      nullptr, 0, false, true);
    if (n_len <= 0) {
        LOGE("Tokenization count failed: %d", n_len);
        env->CallStaticVoidMethod(g_inference_cls, g_on_done_mid, cb_id);
        return;
    }
    std::vector<llama_token> tokens(n_len);
    int tokenized = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                       tokens.data(), n_len, false, true);
    if (tokenized < 0) {
        LOGE("Tokenization failed: %d", tokenized);
        env->CallStaticVoidMethod(g_inference_cls, g_on_done_mid, cb_id);
        return;
    }
    LOGI("Prompt tokenized: %d tokens from %zu chars", tokenized, prompt.size());

    // Decode prompt + generate. GPU backends (Vulkan on Adreno especially, and
    // OpenCL) can THROW a C++ exception from inside llama_decode on a driver
    // error. Without this guard the exception propagates out of JNI and aborts
    // the whole app (SIGABRT). Catch it so the turn ends gracefully — the UI just
    // gets an empty reply via onDone, and the user can switch hardware.
    try {
        // Prefill the prompt in chunks so a Stop (g_cancel) is honored *during*
        // prompt processing — a single llama_decode over the whole prompt is
        // atomic and uninterruptible, which made the Stop button feel dead on
        // long prompts. Positions continue sequentially across chunks.
        bool prefill_ok = true;
        const int prefill_chunk = 128;
        for (int start = 0; start < n_len; start += prefill_chunk) {
            if (g_cancel.load()) { prefill_ok = false; LOGI("Prefill cancelled at %d/%d", start, n_len); break; }
            int n = (n_len - start < prefill_chunk) ? (n_len - start) : prefill_chunk;
            llama_batch chunk = llama_batch_get_one(tokens.data() + start, n);
            if (llama_decode(g_ctx, chunk) != 0) {
                LOGE("llama_decode (prompt) failed at offset %d", start);
                prefill_ok = false;
                break;
            }
        }
        if (prefill_ok && !g_cancel.load()) {
            const int n_max = (max_tokens > 0) ? (int)max_tokens : 512;
            char piece_buf[256];

            for (int i = 0; i < n_max && !g_cancel.load(); ++i) {
                llama_token id = llama_sampler_sample(g_sampler, g_ctx, -1);

                if (llama_vocab_is_eog(vocab, id)) {
                    LOGD("EOS at step %d", i);
                    break;
                }

                int len = llama_token_to_piece(vocab, id, piece_buf,
                                               (int)sizeof(piece_buf) - 1, 0, true);
                if (len < 0) len = 0;
                piece_buf[len] = '\0';

                jstring j_piece = env->NewStringUTF(piece_buf);
                env->CallStaticVoidMethod(g_inference_cls, g_on_token_mid, cb_id, j_piece);
                env->DeleteLocalRef(j_piece);

                llama_batch next = llama_batch_get_one(&id, 1);
                if (llama_decode(g_ctx, next) != 0) {
                    LOGE("llama_decode failed at step %d", i);
                    break;
                }
            }

            // Clear KV cache for next call if we don't support incremental yet
            llama_memory_clear(llama_get_memory(g_ctx), true);
        }
    } catch (const std::exception& e) {
        LOGE("Inference aborted by backend exception: %s", e.what());
    } catch (...) {
        LOGE("Inference aborted by unknown backend exception");
    }

    LOGI("Inference done cbId=%d", (int)cb_id);
    env->CallStaticVoidMethod(g_inference_cls, g_on_done_mid, cb_id);
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_LlamaInference_nativeCancelInference(JNIEnv* env, jclass cls) {
    g_cancel.store(true);
    LOGI("Inference cancel requested");
}

} // extern "C"
