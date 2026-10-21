#include "jni_utf8.h"
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdlib>
#include <cstdint>
#include <iterator>
#include <memory>
#include <mutex>
#include <random>
#include <unistd.h>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "chat.h"
#include "common.h"
#include "arg.h"
#include "json.h"
#include "sampling.h"
#include "ggml-backend.h"
#include "llama.h"

std::string g_native_logs;
std::string g_last_error;
void set_last_error(const std::string & message) {
    g_last_error = message;
    if (g_last_error.size() > 1024) g_last_error.resize(1024);
}

namespace {

constexpr char LOG_TAG[] = "FancyLlama";
constexpr int RESULT_COMPLETED = 0, RESULT_CANCELLED = 1;
constexpr int CHANNEL_TEXT = 0, CHANNEL_REASONING = 1, CHANNEL_TOOL_CALL = 2;
constexpr int BACKEND_CPU = 0, BACKEND_HEXAGON = 2;
constexpr int OFFLOAD_AUTOMATIC = -2, OFFLOAD_ALL = -1;

using MonotonicClock = std::chrono::steady_clock;

jlong elapsed_nanoseconds(const MonotonicClock::time_point & start, const MonotonicClock::time_point & end) {
    return static_cast<jlong>(std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count());
}

int resolve_threads(int configured) {
    if (configured > 0) return configured;
    const long online = sysconf(_SC_NPROCESSORS_ONLN);
    return std::max(1, static_cast<int>(online > 0 ? online : 4) / 2);
}

jlongArray generation_result(
    JNIEnv * env,
    int status,
    size_t prompt_tokens = 0,
    int generated_tokens = 0,
    jlong prefill_nanoseconds = 0,
    jlong time_to_first_token_nanoseconds = 0,
    jlong decode_nanoseconds = 0,
    uint32_t context_tokens = 0,
    int reallocs_same = 0,
    int reallocs_diff = 0) {
    const jlong values[] = {
        static_cast<jlong>(status),
        static_cast<jlong>(prompt_tokens),
        static_cast<jlong>(generated_tokens),
        prefill_nanoseconds,
        time_to_first_token_nanoseconds,
        decode_nanoseconds,
        static_cast<jlong>(context_tokens),
        static_cast<jlong>(reallocs_same),
        static_cast<jlong>(reallocs_diff),
    };
    jlongArray result = env->NewLongArray(static_cast<jsize>(std::size(values)));
    if (result != nullptr) env->SetLongArrayRegion(result, 0, static_cast<jsize>(std::size(values)), values);
    return result;
}

void android_log_callback(ggml_log_level level, const char * text, void *) {
    if (text != nullptr) {
        g_native_logs.append(text);
        if (g_native_logs.size() > 65536) g_native_logs.erase(0, g_native_logs.size() - 65536);
    }
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
        : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
        : ANDROID_LOG_INFO;
    __android_log_write(priority, LOG_TAG, text);
}

void throw_java(JNIEnv * env, const char * class_name, const std::string & message) {
    jclass type = env->FindClass(class_name);
    if (type != nullptr) {
        env->ThrowNew(type, message.c_str());
        env->DeleteLocalRef(type);
    }
}

struct Session {
    int backend = BACKEND_CPU;
    common_init_result_ptr runtime;
    common_params_sampling sampling;
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::unique_ptr<common_batch> batch;
    uint32_t batch_tokens = 0;
    common_chat_templates_ptr templates;
    std::vector<common_chat_msg> messages;
    std::vector<llama_token> cached_tokens;
    std::atomic_bool cancelled{false};
    std::mutex operation_mutex;

    ~Session() {
        templates.reset();
        runtime.reset();
    }
};

bool should_abort(void * data) { return static_cast<Session *>(data)->cancelled.load(std::memory_order_relaxed); }
bool continue_loading(float, void * data) { return !static_cast<Session *>(data)->cancelled.load(std::memory_order_relaxed); }

void clear_cache(Session & session) {
    llama_memory_clear(llama_get_memory(session.context), false);
    session.cached_tokens.clear();
}

void prepare_prompt_cache(Session & session, const std::vector<llama_token> & tokens) {
    auto * memory = llama_get_memory(session.context);
    size_t common = 0;
    const size_t limit = std::min(session.cached_tokens.size(), tokens.size());
    while (common < limit && session.cached_tokens[common] == tokens[common]) ++common;
    if (common == tokens.size() && common > 0) --common;
    llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common), -1);
    session.cached_tokens.resize(common);
    size_t batch_limit = session.batch_tokens;
    for (size_t offset = common; offset < tokens.size();) {
        if (session.cancelled.load(std::memory_order_relaxed)) return;
        session.batch->clear();
        size_t end = offset;
        while (end < tokens.size() && end - offset < batch_limit) {
            session.batch->add(tokens[end], static_cast<llama_pos>(end), 0, end + 1 == tokens.size());
            ++end;
        }
        const int result = llama_process(session.context, LLAMA_PROCESS_TYPE_DECODE, session.batch->get());
        if (session.cancelled.load(std::memory_order_relaxed)) return;
        if (result != 0) {
            if (result == -1) throw std::runtime_error("Invalid input batch.");
            if (result < -1) throw std::runtime_error("Compute error.");
            if (batch_limit == 1 && result == 1) throw std::runtime_error("Context size has been exceeded.");
            batch_limit /= 2;
            continue;
        }
        session.cached_tokens.insert(session.cached_tokens.end(), tokens.begin() + static_cast<std::ptrdiff_t>(offset), tokens.begin() + static_cast<std::ptrdiff_t>(end));
        offset = end;
    }
}

std::vector<std::string> split_cli_args(const std::string & text) {
    std::vector<std::string> args;
    std::string current;
    char quote = 0;
    for (size_t i = 0; i < text.size(); ++i) {
        const char c = text[i];
        if (quote != 0) {
            if (c == quote) quote = 0;
            else if (c == '\\' && i + 1 < text.size()) current += text[++i];
            else current += c;
        } else if (c == '"' || c == '\'') {
            quote = c;
        } else if (c == ' ' || c == '\t' || c == '\n') {
            if (!current.empty()) args.push_back(std::move(current));
            current.clear();
        } else {
            current += c;
        }
    }
    if (!current.empty()) args.push_back(std::move(current));
    if (quote != 0) throw std::runtime_error("Unterminated quote in additional arguments");
    return args;
}

struct Receiver {
    JNIEnv * env;
    jobject target;
    jmethodID method;

    [[nodiscard]] bool emit(const std::string & text, int channel) const {
        if (text.empty()) return true;
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
        if (bytes == nullptr) return false;
        env->SetByteArrayRegion(
            bytes,
            0,
            static_cast<jsize>(text.size()),
            reinterpret_cast<const jbyte *>(text.data()));
        env->CallVoidMethod(target, method, bytes, channel);
        env->DeleteLocalRef(bytes);
        return !env->ExceptionCheck();
    }
};

Receiver receiver_from(JNIEnv * env, jobject target) {
    jclass type = env->GetObjectClass(target);
    if (type == nullptr) throw std::runtime_error("Generation callback type is unavailable");
    jmethodID method = env->GetMethodID(type, "onChunk", "([BI)V");
    env->DeleteLocalRef(type);
    if (method == nullptr) throw std::runtime_error("Generation callback method is unavailable");
    return {env, target, method};
}

std::string gen_tool_call_id() {
    static const char alphabet[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    thread_local std::mt19937 generator(std::random_device{}());
    std::string result(32, ' ');
    for (auto & c : result) c = alphabet[generator() % (sizeof(alphabet) - 1)];
    return result;
}

void emit_text_diffs(
    const common_chat_msg & previous,
    const common_chat_msg & current,
    const Receiver & receiver) {
    for (const auto & diff : common_chat_msg_diff::compute_diffs(previous, current)) {
        if (!diff.reasoning_content_delta.empty() &&
            !receiver.emit(diff.reasoning_content_delta, CHANNEL_REASONING)) {
            throw std::runtime_error("Generation callback rejected reasoning output");
        }
        if (!diff.content_delta.empty() && !receiver.emit(diff.content_delta, CHANNEL_TEXT)) {
            throw std::runtime_error("Generation callback rejected text output");
        }
    }
}

std::vector<common_chat_msg> read_messages(JNIEnv * env, jobjectArray roles, jobjectArray contents, jobjectArray reasonings) {
    const jsize count = env->GetArrayLength(roles);
    std::vector<common_chat_msg> messages;
    messages.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto role = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, index));
        auto content = reinterpret_cast<jstring>(env->GetObjectArrayElement(contents, index));
        auto reasoning = reinterpret_cast<jstring>(env->GetObjectArrayElement(reasonings, index));
        common_chat_msg message = {to_utf8(env, role), to_utf8(env, content)};
        message.reasoning_content = to_utf8(env, reasoning);
        messages.push_back(std::move(message));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        env->DeleteLocalRef(reasoning);
    }
    return messages;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeOpen(
    JNIEnv * env, jclass, jobject /* application */,
    jstring native_library_directory, jstring cache_directory, jstring model_path,
    jint context_tokens, jint batch_tokens, jint micro_batch_tokens,
    jint decode_threads, jint prompt_threads, jint flash_attention,
    jstring cache_type_k, jstring cache_type_v, jboolean use_mmap,
    jboolean cpu_repack, jint backend, jint offload_layers,
    jint moe_cache_mib, jstring hexagon_power_mode, jstring additional_args) {
    try {
        auto session = std::make_unique<Session>();
        session->backend = backend;
        const std::string library_directory = to_utf8(env, native_library_directory);
        const std::string kernels_directory = to_utf8(env, cache_directory);
        const std::string power_mode = to_utf8(env, hexagon_power_mode);

        static std::once_flag backend_initialization;
        std::call_once(backend_initialization, [&] {
            llama_log_set(android_log_callback, nullptr);
            const std::string kernel_cache = kernels_directory + "/llama-opencl";
            setenv("GGML_OPENCL_KERNEL_CACHE_DIR", kernel_cache.c_str(), 0);
            setenv("ADSP_LIBRARY_PATH", library_directory.c_str(), 0);
            if (!power_mode.empty()) setenv("GGML_HEXAGON_POWER_MODE", power_mode.c_str(), 1);
            ggml_backend_load_all_from_path(library_directory.c_str());
            llama_backend_init();
        });

        common_params params;
        if (backend != BACKEND_CPU) {
            const char * names[] = {"CPU", "OpenCL", "HTP"};
            const auto registration = ggml_backend_reg_by_name(names[backend]);
            if (registration == nullptr || ggml_backend_reg_dev_count(registration) == 0) {
                set_last_error(std::string("backend unavailable backend=") + std::to_string(backend));
                throw std::runtime_error("The selected llama.cpp backend is unavailable");
            }
            params.devices.push_back(ggml_backend_reg_dev_get(registration, 0));
        }
        params.devices.push_back(nullptr);
        params.model.path = to_utf8(env, model_path);
        params.n_gpu_layers = backend == BACKEND_CPU ? 0
            : (offload_layers == OFFLOAD_ALL ? -2
                : (offload_layers == OFFLOAD_AUTOMATIC ? -1 : offload_layers));
        params.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
        params.fit_params = true;
        params.no_extra_bufts = !cpu_repack;
        params.moe_cache_size = moe_cache_mib > 0
            ? static_cast<size_t>(moe_cache_mib) * 1024u * 1024u
            : 0u;
        params.load_progress_callback = continue_loading;
        params.load_progress_callback_user_data = session.get();
        params.n_ctx = context_tokens;
        params.n_batch = batch_tokens;
        params.n_ubatch = micro_batch_tokens;
        params.cpuparams.n_threads = resolve_threads(decode_threads);
        params.cpuparams_batch.n_threads = resolve_threads(prompt_threads);
        postprocess_cpu_params(params.cpuparams);
        postprocess_cpu_params(params.cpuparams_batch, &params.cpuparams);
        params.flash_attn_type = static_cast<llama_flash_attn_type>(flash_attention);
        const auto type_k = to_utf8(env, cache_type_k);
        const auto type_v = to_utf8(env, cache_type_v);
        for (const auto type : {GGML_TYPE_F32, GGML_TYPE_F16, GGML_TYPE_BF16, GGML_TYPE_Q8_0,
                                GGML_TYPE_Q4_0, GGML_TYPE_Q4_1, GGML_TYPE_IQ4_NL, GGML_TYPE_Q5_0, GGML_TYPE_Q5_1}) {
            if (type_k == ggml_type_name(type)) params.cache_type_k = type;
            if (type_v == ggml_type_name(type)) params.cache_type_v = type;
        }
        if (type_k != ggml_type_name(params.cache_type_k)) throw std::runtime_error("Unsupported cache type: " + type_k);
        if (type_v != ggml_type_name(params.cache_type_v)) throw std::runtime_error("Unsupported cache type: " + type_v);
        const std::string extra_args = to_utf8(env, additional_args);
        if (!extra_args.empty()) {
            const auto parts = split_cli_args(extra_args);
            std::vector<std::string> storage;
            storage.reserve(parts.size() + 1);
            storage.emplace_back("llama");
            storage.insert(storage.end(), parts.begin(), parts.end());
            std::vector<char *> argv;
            argv.reserve(storage.size());
            for (auto & arg : storage) argv.push_back(arg.data());
            if (!common_params_parse(static_cast<int>(argv.size()), argv.data(), params, LLAMA_EXAMPLE_COMMON)) {
                throw std::runtime_error("Invalid additional arguments: " + extra_args);
            }
        }
        session->runtime = common_init_from_params(params);
        session->model = session->runtime->model();
        session->context = session->runtime->context();
        if (session->model == nullptr) {
            const char * arch = getenv("GGML_HEXAGON_ARCH");
            const char * power = getenv("GGML_HEXAGON_POWER_MODE");
            std::string detail = "GGUF model loading failed backend=" + std::to_string(backend) +
                " offload=" + std::to_string(offload_layers) +
                " n_gpu_layers=" + std::to_string(params.n_gpu_layers) +
                " n_ctx=" + std::to_string(params.n_ctx) +
                " load_mode=" + std::to_string(static_cast<int>(params.load_mode)) +
                " arch=" + (arch != nullptr ? arch : "auto") +
                " power=" + (power != nullptr ? power : "default");
            set_last_error(detail);
            throw std::runtime_error(detail);
        }
        if (session->context == nullptr) {
            set_last_error("GGUF context allocation failed");
            throw std::runtime_error("GGUF context allocation failed");
        }
        session->sampling = params.sampling;
        llama_set_abort_callback(session->context, should_abort, session.get());
        session->batch_tokens = llama_n_batch(session->context);
        session->batch = std::make_unique<common_batch>(session->context);
        return static_cast<jlong>(reinterpret_cast<intptr_t>(session.release()));
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp could not allocate the model");
    } catch (const std::exception & failure) {
        throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeSetMessages(
    JNIEnv * env,
    jobject,
    jlong handle,
    jobjectArray roles,
    jobjectArray contents,
    jobjectArray reasonings) {
    try {
        Session & session = *reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
        std::lock_guard<std::mutex> lock(session.operation_mutex);
        session.messages = read_messages(env, roles, contents, reasonings);
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp could not store the conversation");
    } catch (const std::exception & failure) {
        throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeCountTokens(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring text) {
    try {
        Session & session = *reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
        std::lock_guard<std::mutex> lock(session.operation_mutex);
        if (session.model == nullptr || session.context == nullptr) {
            throw std::runtime_error("llama.cpp model is not loaded");
        }
        const auto tokens = common_tokenize(session.context, to_utf8(env, text), false, true);
        return static_cast<jint>(tokens.size());
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp could not count tokens");
    } catch (const std::exception & failure) {
        throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeSaveKvCache(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring path) {
    try {
        Session & session = *reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
        std::lock_guard<std::mutex> lock(session.operation_mutex);
        if (session.model == nullptr || session.context == nullptr) {
            throw std::runtime_error("llama.cpp model is not loaded");
        }
        const std::string file = to_utf8(env, path);
        const size_t written = llama_state_seq_save_file(
            session.context, file.c_str(), 0,
            session.cached_tokens.data(), session.cached_tokens.size());
        return written != 0 ? JNI_TRUE : JNI_FALSE;
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp could not save the KV cache");
    } catch (const std::exception & failure) {
        throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeLoadKvCache(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring path) {
    try {
        Session & session = *reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
        std::lock_guard<std::mutex> lock(session.operation_mutex);
        if (session.model == nullptr || session.context == nullptr) {
            throw std::runtime_error("llama.cpp model is not loaded");
        }
        const std::string file = to_utf8(env, path);
        std::vector<llama_token> tokens(llama_n_ctx(session.context));
        size_t count = 0;
        const size_t loaded = llama_state_seq_load_file(
            session.context, file.c_str(), 0, tokens.data(), tokens.size(), &count);
        if (loaded == 0 || count == 0 || count > tokens.size()) return JNI_FALSE;
        tokens.resize(count);
        session.cached_tokens = std::move(tokens);
        return JNI_TRUE;
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp could not restore the KV cache");
    } catch (const std::exception & failure) {
        throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeGenerate(
    JNIEnv * env, jobject, jlong handle, jstring input, jboolean thinking,
    jint max_output_tokens, jfloat temperature, jfloat dynamic_temperature,
    jint top_k, jfloat top_p, jfloat min_p, jfloat repetition_penalty,
    jfloat presence_penalty, jfloat frequency_penalty, jint penalty_window,
    jfloat dry_multiplier, jfloat dry_base, jint dry_allowed_length, jint dry_penalty_range,
    jboolean benchmarking, jstring response_schema, jstring tools_json, jobject callback) {
    Session * session_pointer = nullptr;
    std::vector<common_chat_msg> messages_before;
    bool conversation_changed = false;
    try {
        session_pointer = reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
        Session & session = *session_pointer;
        std::lock_guard<std::mutex> lock(session.operation_mutex);
        session.cancelled.store(false, std::memory_order_relaxed);
        if (benchmarking == JNI_TRUE) {
            clear_cache(session);
            llama_perf_context_reset(session.context);
            const int prompt_count = 512;
            const int output_count = max_output_tokens > 0 ? max_output_tokens : 128;
            const auto started = MonotonicClock::now();
            for (int processed = 0; processed < prompt_count;) {
                if (session.cancelled.load(std::memory_order_relaxed)) {
                    clear_cache(session);
                    return generation_result(env, RESULT_CANCELLED);
                }
                const int count = std::min(prompt_count - processed, static_cast<int>(session.batch_tokens));
                session.batch->clear();
                for (int i = 0; i < count; ++i) {
                    session.batch->add(0, processed + i, 0, processed + i == prompt_count - 1);
                }
                const int result = llama_process(session.context, LLAMA_PROCESS_TYPE_DECODE, session.batch->get());
                if (result != 0) throw std::runtime_error("failed to decode prompt batch, res = " + std::to_string(result));
                processed += count;
            }
            const auto prefill_finished = MonotonicClock::now();
            llama_memory_clear(llama_get_memory(session.context), false);
            const auto tg_start = MonotonicClock::now();
            auto first_token = tg_start;
            for (int i = 0; i < output_count; ++i) {
                if (session.cancelled.load(std::memory_order_relaxed)) {
                    clear_cache(session);
                    return generation_result(env, RESULT_CANCELLED);
                }
                session.batch->clear();
                session.batch->add(0, i, 0, true);
                const int result = llama_process(session.context, LLAMA_PROCESS_TYPE_DECODE, session.batch->get());
                if (result != 0) throw std::runtime_error("failed to decode generation batch, res = " + std::to_string(result));
                if (i == 0) first_token = MonotonicClock::now();
            }
            const auto finished = MonotonicClock::now();
            const auto bench_perf = llama_perf_context(session.context);
            clear_cache(session);
            return generation_result(env, RESULT_COMPLETED, prompt_count, output_count,
                elapsed_nanoseconds(started, prefill_finished), elapsed_nanoseconds(started, first_token),
                elapsed_nanoseconds(tg_start, finished), llama_n_ctx(session.context),
                bench_perf.n_reallocs_same, bench_perf.n_reallocs_diff);
        }
        messages_before = session.messages;

        session.messages.push_back({"user", to_utf8(env, input)});
        conversation_changed = true;

        if (!session.templates) session.templates = common_chat_templates_init(session.model, "");
        const llama_vocab * vocab = llama_model_get_vocab(session.model);
        common_chat_templates_inputs template_inputs;
        template_inputs.json_schema = to_utf8(env, response_schema);
        const auto tools_raw = to_utf8(env, tools_json);
        if (!tools_raw.empty()) {
            auto parsed_tools = common_json::parse(tools_raw);
            template_inputs.tools = common_chat_tools_parse_oaicompat(parsed_tools);
        }
        template_inputs.add_generation_prompt = true;
        template_inputs.use_jinja = true;
        template_inputs.enable_thinking = thinking &&
            common_chat_templates_support_enable_thinking(session.templates.get());
        template_inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        const uint32_t context_size = llama_n_ctx(session.context);
        template_inputs.messages = session.messages;
        common_chat_session chat_session(session.templates.get(), vocab, template_inputs);
        std::vector<llama_token> prompt_tokens = common_tokenize(session.context, chat_session.prompt().str(), true, true);

        size_t pinned_messages = 0;
        if (!session.messages.empty() && session.messages.front().role == "system") {
            pinned_messages = 1;
            if (session.messages.size() > 1 && session.messages[1].role == "assistant") pinned_messages = 2;
        }
        while (!prompt_tokens.empty() && prompt_tokens.size() >= context_size) {
            if (session.cancelled.load(std::memory_order_relaxed)) {
                session.messages = std::move(messages_before);
                clear_cache(session);
                return generation_result(env, RESULT_CANCELLED);
            }
            size_t drop = pinned_messages;
            while (drop + 1 < session.messages.size() && session.messages[drop].role != "user") ++drop;
            if (drop + 1 >= session.messages.size()) break;
            session.messages.erase(session.messages.begin() + static_cast<std::ptrdiff_t>(drop));
            if (drop < session.messages.size() && session.messages[drop].role == "assistant") {
                session.messages.erase(session.messages.begin() + static_cast<std::ptrdiff_t>(drop));
            }
            template_inputs.messages = session.messages;
            chat_session = common_chat_session(session.templates.get(), vocab, template_inputs);
            prompt_tokens = common_tokenize(session.context, chat_session.prompt().str(), true, true);
        }
        if (prompt_tokens.empty() || prompt_tokens.size() >= context_size) {
            throw std::runtime_error("Context allocation cannot hold the prompt");
        }

        int pinned_prefix_tokens = 0;
        if (pinned_messages > 0) {
            std::string prefix_text;
            for (size_t index = 0; index < pinned_messages; ++index) prefix_text += session.messages[index].content + '\n';
            try {
                common_chat_templates_inputs prefix_inputs = template_inputs;
                prefix_inputs.messages.assign(session.messages.begin(), session.messages.begin() + static_cast<std::ptrdiff_t>(pinned_messages));
                prefix_inputs.add_generation_prompt = false;
                prefix_text = common_chat_templates_apply(session.templates.get(), prefix_inputs).prompt.str();
            } catch (const std::exception &) { /* Strict templates reject user-less renders: keep raw text. */ }
            pinned_prefix_tokens = static_cast<int>(common_tokenize(session.context, prefix_text, true, true).size());
        }
        llama_perf_context_reset(session.context);
        prepare_prompt_cache(session, prompt_tokens);
        if (session.cancelled.load(std::memory_order_relaxed)) {
            session.messages = std::move(messages_before);
            clear_cache(session);
            return generation_result(env, RESULT_CANCELLED);
        }
        common_params_sampling params = session.sampling;
        params.top_k = top_k; params.top_p = top_p; params.min_p = min_p;
        params.temp = temperature; params.dynatemp_range = dynamic_temperature;
        params.penalty_last_n = penalty_window; params.penalty_repeat = repetition_penalty;
        params.penalty_freq = frequency_penalty; params.penalty_present = presence_penalty;
        params.dry_multiplier = dry_multiplier; params.dry_base = dry_base;
        params.dry_allowed_length = dry_allowed_length; params.dry_penalty_last_n = dry_penalty_range;
        chat_session.apply_sampling(params);
        if (!thinking && !chat_session.thinking_end_tags().empty()) {
            params.reasoning_budget_tokens = 0;
            params.reasoning_budget_start = common_tokenize(session.context, chat_session.thinking_start_tag(), false, true);
            for (const auto & tag : chat_session.thinking_end_tags()) {
                params.reasoning_budget_end.push_back(common_tokenize(session.context, tag, false, true));
            }
            params.reasoning_budget_forced = params.reasoning_budget_end.front();
        }
        std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(
            common_sampler_init(session.model, params), common_sampler_free);

        for (const auto token : prompt_tokens) {
            common_sampler_accept(sampler.get(), token, false);
        }
        const Receiver receiver = receiver_from(env, callback);
        common_chat_input generated;
        size_t fed_bytes = 0;
        common_chat_msg parsed_output = chat_session.msg();
        std::vector<std::string> tool_ids;
        int sampled_tokens = 0;
        llama_token last_sampled_token = -1;
        size_t generated_bytes = 0;
        const char * stop_reason = "token_limit";
        for (int step = 0; max_output_tokens < 0 || step < max_output_tokens; ++step) {
            if (session.cancelled.load(std::memory_order_relaxed)) {
                session.messages = std::move(messages_before);
                clear_cache(session);
                return generation_result(env, RESULT_CANCELLED);
            }
            if (session.cached_tokens.size() + 1 >= context_size) {
                auto * memory = llama_get_memory(session.context);
                if (session.backend == BACKEND_HEXAGON || !llama_memory_can_shift(memory)) {
                    stop_reason = "context_limit";
                    break;
                }
                // llama-server --context-shift, keeping the rendered system prefix.
                const int n_keep = std::min(static_cast<int>(context_size) - 4,
                    pinned_prefix_tokens + (llama_vocab_get_add_bos(vocab) ? 1 : 0));
                const int n_left = static_cast<int>(session.cached_tokens.size()) - n_keep;
                const int n_discard = std::clamp(n_left / 2, 0, std::max(0, n_left - 1));
                common_memory upstream_memory;
                upstream_memory.init(session.context);
                upstream_memory.seq_rm(0, n_keep, n_keep + n_discard);
                upstream_memory.seq_add(0, n_keep + n_discard,
                    static_cast<llama_pos>(session.cached_tokens.size()), -n_discard);
                session.cached_tokens.erase(session.cached_tokens.begin() + n_keep,
                    session.cached_tokens.begin() + n_keep + n_discard);
            }
            const llama_token token = common_sampler_sample(sampler.get(), session.context, -1);
            ++sampled_tokens;
            last_sampled_token = token;
            common_sampler_accept(sampler.get(), token, true);
            const bool is_eog = llama_vocab_is_eog(vocab, token);
            if (is_eog) {
                stop_reason = "eog";
                break;
            }

            generated.append(common_token_to_piece(session.context, token, true), token);
            generated_bytes = generated.text.size();
            bool stopped = false;
            bool suppress = !complete_utf8(generated.text);
            if (!suppress) {
                size_t first_stop = std::string::npos;
                for (const auto & stop : chat_session.additional_stops()) {
                    if (!stop.empty()) first_stop = std::min(first_stop, generated.text.find(stop));
                }
                if (first_stop != std::string::npos) {
                    generated.truncate(first_stop);
                    stopped = true;
                } else {
                    size_t visible_bytes = generated.text.size();
                    for (const auto & stop : chat_session.additional_stops()) {
                        if (!stop.empty()) visible_bytes = std::min(visible_bytes, string_find_partial_stop(generated.text, stop));
                    }
                    suppress = visible_bytes < generated.text.size();
                }
            }
            if (!suppress) {
                const common_chat_input chunk = generated.substr(fed_bytes);
                if (!chunk.empty()) {
                    auto fed = chat_session.feed(chunk);
                    if (!fed.empty()) {
                        fed.set_tool_call_ids(tool_ids, gen_tool_call_id);
                        emit_text_diffs(parsed_output, fed, receiver);
                        parsed_output = fed;
                    }
                    fed_bytes = generated.text.size();
                }
            }

            if (stopped) {
                stop_reason = "template_stop";
                break;
            }

            session.batch->clear();
            session.batch->add(
                token,
                static_cast<llama_pos>(session.cached_tokens.size()),
                0,
                true);
            int32_t attempt = session.batch->size();
            while (true) {
                const int decoded = llama_process(session.context, LLAMA_PROCESS_TYPE_DECODE, session.batch->get_sub_batch(0, attempt));
                if (session.cancelled.load(std::memory_order_relaxed)) {
                    session.messages = std::move(messages_before);
                    clear_cache(session);
                    return generation_result(env, RESULT_CANCELLED);
                }
                if (decoded == 0) break;
                if (attempt == 1 && decoded == 1) throw std::runtime_error("Context size has been exceeded.");
                if (decoded == -1) throw std::runtime_error("Invalid input batch.");
                if (decoded < -1) throw std::runtime_error("Compute error.");
                attempt /= 2;
            }
            session.cached_tokens.push_back(token);
        }

        auto finished_msg = chat_session.finish({});
        if (!finished_msg.empty()) {
            finished_msg.set_tool_call_ids(tool_ids, gen_tool_call_id);
            emit_text_diffs(parsed_output, finished_msg, receiver);
            parsed_output = finished_msg;
        }
        if (!parsed_output.tool_calls.empty()) {
            auto calls = common_json::array();
            for (const auto & tc : parsed_output.tool_calls) {
                calls.push_back({{"id", tc.id}, {"name", tc.name}, {"arguments", tc.arguments}});
            }
            if (!receiver.emit(calls.dump(), CHANNEL_TOOL_CALL)) {
                throw std::runtime_error("Generation callback rejected tool call output");
            }
        }
        common_chat_msg assistant;
        assistant.role = "assistant";
        assistant.content = parsed_output.render_content();
        assistant.reasoning_content = parsed_output.reasoning_content;
        assistant.tool_calls = parsed_output.tool_calls;
        __android_log_print(
            assistant.content.empty() && assistant.tool_calls.empty() ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
            LOG_TAG,
            "Text completion: stop=%s sampled_tokens=%d last_token=%d "
            "generated_bytes=%zu retained_bytes=%zu text_bytes=%zu reasoning_bytes=%zu "
            "utf8_complete=%d thinking=%d benchmarking=%d",
            stop_reason,
            sampled_tokens,
            static_cast<int>(last_sampled_token),
            generated_bytes,
            generated.text.size(),
            assistant.content.size(),
            parsed_output.reasoning_content.size(),
            static_cast<int>(complete_utf8(generated.text)),
            static_cast<int>(thinking),
            static_cast<int>(benchmarking));
        session.messages.push_back(std::move(assistant));
        llama_perf_context_print(session.context);
        const auto perf = llama_perf_context(session.context);
        return generation_result(env, RESULT_COMPLETED,
            prompt_tokens.size(), sampled_tokens, 0, 0, 0,
            llama_n_ctx(session.context), perf.n_reallocs_same, perf.n_reallocs_diff);
    } catch (const std::bad_alloc &) {
        if (session_pointer != nullptr && conversation_changed) {
            session_pointer->messages = std::move(messages_before); clear_cache(*session_pointer);
        }
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp ran out of memory");
    } catch (const std::exception & failure) {
        if (session_pointer != nullptr && conversation_changed) {
            session_pointer->messages = std::move(messages_before); clear_cache(*session_pointer);
        }
        if (!env->ExceptionCheck()) throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeCancel(JNIEnv * env, jobject, jlong handle) {
    try {
        reinterpret_cast<Session *>(static_cast<intptr_t>(handle))->cancelled.store(true, std::memory_order_relaxed);
    } catch (const std::exception & failure) {
        throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeClose(JNIEnv * env, jobject, jlong handle) {
    try {
        std::unique_ptr<Session> session(reinterpret_cast<Session *>(static_cast<intptr_t>(handle)));
        session->cancelled.store(true, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(session->operation_mutex);
    } catch (const std::exception & failure) {
        throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeLastError(JNIEnv * env, jclass) {
    std::string combined = g_last_error;
    if (!g_native_logs.empty()) {
        const size_t tail = g_native_logs.size() > 8000 ? g_native_logs.size() - 8000 : 0;
        combined.append("\n---native---\n");
        combined.append(g_native_logs.substr(tail));
    }
    if (combined.size() > 12000) combined.erase(0, combined.size() - 12000);
    return env->NewStringUTF(combined.c_str());
}
