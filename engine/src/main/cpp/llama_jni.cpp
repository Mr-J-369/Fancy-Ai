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
#include <unistd.h>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "chat.h"
#include "common.h"
#include "json.h"
#include "sampling.h"
#include "ggml-backend.h"
#include "llama.h"

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
    uint32_t context_tokens = 0) {
    const jlong values[] = {
        static_cast<jlong>(status),
        static_cast<jlong>(prompt_tokens),
        static_cast<jlong>(generated_tokens),
        prefill_nanoseconds,
        time_to_first_token_nanoseconds,
        decode_nanoseconds,
        static_cast<jlong>(context_tokens),
    };
    jlongArray result = env->NewLongArray(static_cast<jsize>(std::size(values)));
    if (result != nullptr) env->SetLongArrayRegion(result, 0, static_cast<jsize>(std::size(values)), values);
    return result;
}

void android_log_callback(ggml_log_level level, const char * text, void *) {
#if !FANCY_INTEGRITY_REQUIRED
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
        : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
        : level == GGML_LOG_LEVEL_DEBUG ? ANDROID_LOG_DEBUG
        : ANDROID_LOG_INFO;
    __android_log_write(priority, LOG_TAG, text);
#else
    (void)level;
    (void)text;
#endif
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
    llama_batch batch{};
    bool batch_ready = false;
    uint32_t batch_tokens = 0;
    common_chat_templates_ptr templates;
    std::vector<common_chat_msg> messages;
    std::vector<llama_token> cached_tokens;
    std::vector<common_prompt_checkpoint> checkpoints;
    common_context_seq_rm_type sequence_removal = COMMON_CONTEXT_SEQ_RM_TYPE_PART;
    int checkpoint_task = 0;
    std::atomic_bool cancelled{false};
    std::mutex operation_mutex;

    ~Session() {
        if (batch_ready) llama_batch_free(batch);
        templates.reset();
        runtime.reset();
    }
};

bool should_abort(void * data) { return static_cast<Session *>(data)->cancelled.load(std::memory_order_relaxed); }
bool continue_loading(float, void * data) { return !static_cast<Session *>(data)->cancelled.load(std::memory_order_relaxed); }

void clear_cache(Session & session) {
    llama_memory_clear(llama_get_memory(session.context), false);
    session.cached_tokens.clear();
    session.checkpoints.clear();
}

void prepare_prompt_cache(Session & session, const std::vector<llama_token> & tokens,
                          common_chat_msg_delimiters delimiters) {
    auto * memory = llama_get_memory(session.context);
    const common_params defaults{};
    const int n_swa = llama_model_n_swa(session.model);
    const auto n_ubatch = llama_n_ubatch(session.context);
    size_t common = 0;
    const size_t limit = std::min(session.cached_tokens.size(), tokens.size());
    while (common < limit && session.cached_tokens[common] == tokens[common]) ++common;
    common_memory upstream_memory;
    upstream_memory.init(session.context);
    // llama-server --cache-reuse 256: relocate matching chunks after history drops a turn.
    // NPU (HTP) cannot execute in-place RoPE K-shift on device tensors; skip shift on Hexagon.
    if (session.backend != BACKEND_HEXAGON && llama_memory_can_shift(memory)) {
        size_t head_c = common;
        size_t head_p = common;
        while (head_c < session.cached_tokens.size() && head_p < tokens.size()) {
            size_t n_match = 0;
            while (head_c + n_match < session.cached_tokens.size() && head_p + n_match < tokens.size() &&
                   session.cached_tokens[head_c + n_match] == tokens[head_p + n_match]) ++n_match;
            if (n_match >= 256) {
                const auto shift = static_cast<llama_pos>(head_p) - static_cast<llama_pos>(head_c);
                upstream_memory.seq_rm(0, static_cast<llama_pos>(head_p), static_cast<llama_pos>(head_c));
                upstream_memory.seq_add(0, static_cast<llama_pos>(head_c), static_cast<llama_pos>(head_c + n_match), shift);
                for (size_t i = 0; i < n_match; ++i) session.cached_tokens[head_p + i] = session.cached_tokens[head_c + i];
                common += n_match;
                head_c += n_match;
                head_p += n_match;
                session.checkpoints.clear();
            } else ++head_c;
        }
    }
    const auto pos_min_threshold = std::max<llama_pos>(
        0, static_cast<llama_pos>(common) - n_swa - (common < tokens.size() ? 0 : 1));
    if (common > 0 && llama_memory_seq_pos_min(memory, 0) >= pos_min_threshold) {
        const auto checkpoint = std::find_if(session.checkpoints.rbegin(), session.checkpoints.rend(),
            [&](const auto & current) {
                return current.pos_max <= static_cast<llama_pos>(common) &&
                    (current.pos_min < pos_min_threshold || current.pos_min == 0);
            });
        if (checkpoint == session.checkpoints.rend()) common = 0;
        else {
            checkpoint->load_tgt(session.context, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
            common = std::min<size_t>(common, std::max(checkpoint->pos_min + 1, checkpoint->pos_max));
            common = std::min<size_t>(common, checkpoint->n_tokens);
        }
    }
    for (auto it = session.checkpoints.begin(); it != session.checkpoints.end();) {
        if (it->pos_max > static_cast<llama_pos>(common)) it = session.checkpoints.erase(it);
        else ++it;
    }
    if (common == tokens.size() && common > 0) --common;
    upstream_memory.seq_rm(0, static_cast<llama_pos>(common), -1);
    session.cached_tokens.resize(common);
    delimiters.tokenize(llama_model_get_vocab(session.model));
    const auto spans = delimiters.split(tokens);
    const auto last_user_pos = spans.last_user_message_pos();
    const bool checkpoints = defaults.n_ctx_checkpoints > 0 &&
        (session.sequence_removal == COMMON_CONTEXT_SEQ_RM_TYPE_FULL ||
         session.sequence_removal == COMMON_CONTEXT_SEQ_RM_TYPE_RS || n_swa > 0);
    ++session.checkpoint_task;
    size_t batch_limit = session.batch_tokens;
    for (size_t offset = common; offset < tokens.size();) {
        if (session.cancelled.load(std::memory_order_relaxed)) return;
        common_batch_clear(session.batch);
        size_t end = offset;
        while (end < tokens.size() && end - offset < batch_limit) {
            common_batch_add(session.batch, tokens[end], static_cast<llama_pos>(end), {0}, end + 1 == tokens.size());
            ++end;
            if (checkpoints && spans.is_user_start(static_cast<int32_t>(end)) &&
                (end == static_cast<size_t>(last_user_pos) || session.checkpoints.empty() ||
                 end > session.checkpoints.back().n_tokens + defaults.checkpoint_min_step)) break;
            if (checkpoints && (tokens.size() == end + std::min<size_t>(batch_limit, 4 + n_ubatch) ||
                                tokens.size() == end + std::min<size_t>(batch_limit, 4))) break;
        }
        const bool near_end = tokens.size() < end + n_ubatch;
        const bool user_start = spans.is_user_start(static_cast<int32_t>(offset));
        const bool last_user = offset == static_cast<size_t>(last_user_pos);
        const auto pos_min = llama_memory_seq_pos_min(memory, 0);
        const auto pos_max = llama_memory_seq_pos_max(memory, 0);
        if (checkpoints && (end == tokens.size() || user_start || near_end) && pos_min >= 0 &&
            (session.checkpoints.empty() || last_user || near_end ||
             offset > session.checkpoints.back().n_tokens + defaults.checkpoint_min_step)) {
            int64_t last = -1;
            for (auto it = session.checkpoints.begin(); it != session.checkpoints.end();) {
                if (it->id_task != session.checkpoint_task && last >= 0 &&
                    it->n_tokens <= last + defaults.checkpoint_min_step) {
                    it = session.checkpoints.erase(it);
                } else {
                    last = it->n_tokens;
                    ++it;
                }
            }
            while (session.checkpoints.size() >= static_cast<size_t>(defaults.n_ctx_checkpoints)) {
                session.checkpoints.erase(session.checkpoints.begin());
            }
            auto & checkpoint = session.checkpoints.emplace_back();
            checkpoint.id_task = session.checkpoint_task;
            checkpoint.update_pos(static_cast<int64_t>(offset), pos_min, pos_max);
            checkpoint.update_tgt(session.context, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        }
        const int result = llama_decode(session.context, session.batch);
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

common_chat_msg parse_and_emit(
    const std::string & raw,
    bool partial,
    const common_chat_parser_params & parser,
    const common_chat_msg & previous,
    const Receiver & receiver) {
    auto parsed = common_chat_parse(raw, partial, parser);
    if (parsed.empty()) return previous;
    for (const auto & diff : common_chat_msg_diff::compute_diffs(previous, parsed)) {
        if (!diff.reasoning_content_delta.empty() &&
            !receiver.emit(diff.reasoning_content_delta, CHANNEL_REASONING)) {
            throw std::runtime_error("Generation callback rejected reasoning output");
        }
        if (!diff.content_delta.empty() && !receiver.emit(diff.content_delta, CHANNEL_TEXT)) {
            throw std::runtime_error("Generation callback rejected text output");
        }
    }
    return parsed;
}

bool strip_stop(std::string & raw, const std::vector<std::string> & stops) {
    size_t first = std::string::npos;
    for (const std::string & stop : stops) {
        if (!stop.empty()) first = std::min(first, raw.find(stop));
    }
    if (first == std::string::npos) return false;
    raw.resize(first);
    return true;
}

std::vector<common_chat_msg> read_messages(JNIEnv * env, jobjectArray roles, jobjectArray contents) {
    const jsize count = env->GetArrayLength(roles);
    std::vector<common_chat_msg> messages;
    messages.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto role = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, index));
        auto content = reinterpret_cast<jstring>(env->GetObjectArrayElement(contents, index));
        messages.push_back({to_utf8(env, role), to_utf8(env, content)});
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
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
    jboolean cpu_repack, jint backend, jint offload_layers) {
    try {
        auto session = std::make_unique<Session>();
        session->backend = backend;
        const std::string library_directory = to_utf8(env, native_library_directory);
        const std::string kernels_directory = to_utf8(env, cache_directory);

        static std::once_flag backend_initialization;
        std::call_once(backend_initialization, [&] {
            llama_log_set(android_log_callback, nullptr);
            const std::string kernel_cache = kernels_directory + "/llama-opencl";
            setenv("GGML_OPENCL_KERNEL_CACHE_DIR", kernel_cache.c_str(), 0);
            setenv("ADSP_LIBRARY_PATH", library_directory.c_str(), 0);
            ggml_backend_load_all_from_path(library_directory.c_str());
            llama_backend_init();
        });

        common_params params;
        if (backend != BACKEND_CPU) {
            const char * names[] = {"CPU", "OpenCL", "HTP"};
            const auto registration = ggml_backend_reg_by_name(names[backend]);
            if (registration == nullptr || ggml_backend_reg_dev_count(registration) == 0) {
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
        params.no_extra_bufts = !cpu_repack;
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
        params.no_perf = false;
        session->runtime = common_init_from_params(params);
        session->model = session->runtime->model();
        session->context = session->runtime->context();
        if (session->model == nullptr) throw std::runtime_error("GGUF model loading failed");
        if (session->context == nullptr) throw std::runtime_error("GGUF context allocation failed");
        session->sampling = params.sampling;
        session->sequence_removal = common_context_can_seq_rm(session->context);
        llama_set_abort_callback(session->context, should_abort, session.get());
        session->batch_tokens = llama_n_batch(session->context);
        session->batch = llama_batch_init(static_cast<int32_t>(session->batch_tokens), 0, 1);
        session->batch_ready = true;
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
    jobjectArray contents) {
    try {
        Session & session = *reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
        std::lock_guard<std::mutex> lock(session.operation_mutex);
        session.messages = read_messages(env, roles, contents);
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp could not store the conversation");
    } catch (const std::exception & failure) {
        throw_java(env, "java/lang/IllegalStateException", failure.what());
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_mrj_fancyai_engine_LlamaRuntime_nativeGenerate(
    JNIEnv * env, jobject, jlong handle, jstring input, jboolean thinking,
    jint max_output_tokens, jfloat temperature, jfloat dynamic_temperature,
    jint top_k, jfloat top_p, jfloat min_p, jfloat repetition_penalty,
    jfloat presence_penalty, jfloat frequency_penalty, jint penalty_window,
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
                common_batch_clear(session.batch);
                for (int i = 0; i < count; ++i) {
                    common_batch_add(session.batch, 0, processed + i, {0}, processed + i == prompt_count - 1);
                }
                const int result = llama_decode(session.context, session.batch);
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
                common_batch_clear(session.batch);
                common_batch_add(session.batch, 0, i, {0}, true);
                const int result = llama_decode(session.context, session.batch);
                if (result != 0) throw std::runtime_error("failed to decode generation batch, res = " + std::to_string(result));
                if (i == 0) first_token = MonotonicClock::now();
            }
            const auto finished = MonotonicClock::now();
            clear_cache(session);
            return generation_result(env, RESULT_COMPLETED, prompt_count, output_count,
                elapsed_nanoseconds(started, prefill_finished), elapsed_nanoseconds(started, first_token),
                elapsed_nanoseconds(tg_start, finished), llama_n_ctx(session.context));
        }
        messages_before = session.messages;

        session.messages.push_back({"user", to_utf8(env, input)});
        conversation_changed = true;

        if (!session.templates) session.templates = common_chat_templates_init(session.model, "");
        common_chat_templates_inputs template_inputs;
        template_inputs.json_schema = to_utf8(env, response_schema);
        const auto tools_raw = to_utf8(env, tools_json);
        if (!tools_raw.empty()) {
            try {
                auto parsed_tools = common_json::parse(tools_raw);
                template_inputs.tools = common_chat_tools_parse_oaicompat(parsed_tools);
            } catch (...) {
            }
        }
        template_inputs.add_generation_prompt = true;
        template_inputs.use_jinja = true;
        template_inputs.enable_thinking = thinking;
        template_inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        const uint32_t context_size = llama_n_ctx(session.context);
        template_inputs.messages = session.messages;
        common_chat_params chat = common_chat_templates_apply(session.templates.get(), template_inputs);
        std::vector<llama_token> prompt_tokens = common_tokenize(session.context, chat.prompt, true, true);
        // Kotlin budgets history with a tokenizer-agnostic heuristic. Trim the
        // oldest exchanges here with exact token counts so a long turn degrades
        // instead of failing the request. The pinned system prefix and the
        // latest user input are never dropped.
        size_t pinned_messages = 0;
        if (!session.messages.empty() && session.messages.front().role == "system") pinned_messages = 1;
        if (session.messages.size() > pinned_messages && session.messages[pinned_messages].role == "assistant") ++pinned_messages;
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
            chat = common_chat_templates_apply(session.templates.get(), template_inputs);
            prompt_tokens = common_tokenize(session.context, chat.prompt, true, true);
        }
        if (prompt_tokens.empty() || prompt_tokens.size() >= context_size) {
            throw std::runtime_error("Context allocation cannot hold the prompt");
        }
        // Exact rendered size of the pinned system prefix, kept on context shift
        // so mid-generation eviction cannot discard the character definition.
        int pinned_prefix_tokens = 0;
        if (pinned_messages > 0) {
            common_chat_templates_inputs prefix_inputs = template_inputs;
            prefix_inputs.messages = std::vector<common_chat_msg>(
                session.messages.begin(),
                session.messages.begin() + static_cast<std::ptrdiff_t>(pinned_messages));
            prefix_inputs.add_generation_prompt = false;
            pinned_prefix_tokens = static_cast<int>(common_tokenize(
                session.context,
                common_chat_templates_apply(session.templates.get(), prefix_inputs).prompt,
                true, true).size());
        }
        llama_perf_context_reset(session.context);
        prepare_prompt_cache(session, prompt_tokens, chat.message_delimiters);
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
        params.grammar = {template_inputs.tools.empty() ? COMMON_GRAMMAR_TYPE_OUTPUT_FORMAT : COMMON_GRAMMAR_TYPE_TOOL_CALLS, chat.grammar};
        params.grammar_lazy = chat.grammar_lazy;
        params.grammar_triggers = chat.grammar_triggers;
        params.generation_prompt = chat.generation_prompt;
        if (!thinking && !chat.thinking_end_tags.empty()) {
            params.reasoning_budget_tokens = 0;
            params.reasoning_budget_start = common_tokenize(session.context, chat.thinking_start_tag, false, true);
            for (const auto & tag : chat.thinking_end_tags) {
                params.reasoning_budget_end.push_back(common_tokenize(session.context, tag, false, true));
            }
            params.reasoning_budget_forced = params.reasoning_budget_end.front();
        }
        for (const auto & text : chat.preserved_tokens) {
            const auto tokens = common_tokenize(session.context, text, false, true);
            if (tokens.size() == 1) params.preserved_tokens.insert(tokens.front());
        }
        std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(
            common_sampler_init(session.model, params), common_sampler_free);

        for (const auto token : prompt_tokens) {
            common_sampler_accept(sampler.get(), token, false);
        }
        const Receiver receiver = receiver_from(env, callback);
        common_chat_parser_params parser(chat);
        parser.parser.load(chat.parser);
        parser.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        parser.parse_tool_calls = !template_inputs.tools.empty();

        std::string raw_output;
        common_chat_msg parsed_output;
        const llama_vocab * vocab = llama_model_get_vocab(session.model);
        int sampled_tokens = 0;
        llama_token last_sampled_token = -1;
        size_t generated_bytes = 0;
        const char * stop_reason = "token_limit";
        for (int generated = 0; max_output_tokens < 0 || generated < max_output_tokens; ++generated) {
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
                session.checkpoints.clear();
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

            const size_t previous_bytes = raw_output.size();
            raw_output += common_token_to_piece(session.context, token, true);
            generated_bytes += raw_output.size() - previous_bytes;
            const bool stopped = strip_stop(raw_output, chat.additional_stops);
            size_t visible_bytes = raw_output.size();
            if (!stopped) {
                for (const auto & stop : chat.additional_stops) {
                    if (!stop.empty()) visible_bytes = std::min(visible_bytes, string_find_partial_stop(raw_output, stop));
                }
            }
            const std::string visible_output = raw_output.substr(0, visible_bytes);
            if (complete_utf8(visible_output)) {
                parsed_output = parse_and_emit(
                    visible_output,
                    !stopped,
                    parser,
                    parsed_output,
                    receiver);
            }

            if (stopped) {
                stop_reason = "template_stop";
                break;
            }

            common_batch_clear(session.batch);
            common_batch_add(
                session.batch,
                token,
                static_cast<llama_pos>(session.cached_tokens.size()),
                {0},
                true);
            while (true) {
                const int decoded = llama_decode(session.context, session.batch);
                if (session.cancelled.load(std::memory_order_relaxed)) {
                    session.messages = std::move(messages_before);
                    clear_cache(session);
                    return generation_result(env, RESULT_CANCELLED);
                }
                if (decoded == 0) break;
                if (session.batch.n_tokens == 1 && decoded == 1) throw std::runtime_error("Context size has been exceeded.");
                if (decoded == -1) throw std::runtime_error("Invalid input batch.");
                if (decoded < -1) throw std::runtime_error("Compute error.");
                session.batch.n_tokens /= 2;
            }
            session.cached_tokens.push_back(token);
        }

        if (complete_utf8(raw_output)) {
            parsed_output = parse_and_emit(
                raw_output,
                false,
                parser,
                parsed_output,
                receiver);
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
#if !FANCY_INTEGRITY_REQUIRED
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
            raw_output.size(),
            assistant.content.size(),
            parsed_output.reasoning_content.size(),
            static_cast<int>(complete_utf8(raw_output)),
            static_cast<int>(thinking),
            static_cast<int>(benchmarking));
#endif
        session.messages.push_back(std::move(assistant));
        llama_perf_context_print(session.context);
        return generation_result(env, RESULT_COMPLETED);
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
