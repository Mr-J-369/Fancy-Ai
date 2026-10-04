#include <jni.h>
#include <atomic>
#include <chrono>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <streambuf>
#include <utility>
#include "llm/llm.hpp"
#include "llmconfig.hpp"
#include "tokenizer/tokenizer.hpp"
#include "prompt_cache_utils.hpp"

namespace {
using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;
using MNN::Transformer::ChatMessages;
using Clock = std::chrono::steady_clock;

std::string bytes(JNIEnv* env, jbyteArray input) {
    if (!input) throw std::invalid_argument("Missing MNN input");
    const jsize length = env->GetArrayLength(input);
    std::string result(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}
void fail(JNIEnv* env, const char* type, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(type), message);
}
struct Session {
    std::unique_ptr<Llm> llm;
    ChatMessages messages;
    std::atomic_bool cancelled{false};
    std::mutex mutex;
    int context = 4096;
};
Session& session(jlong handle) {
    if (!handle) throw std::invalid_argument("MNN runtime is closed");
    return *reinterpret_cast<Session*>(handle);
}
bool valid_utf8(const std::string& text) {
    size_t i = 0;
    while (i < text.size()) {
        auto c = static_cast<unsigned char>(text[i]);
        const size_t n = c < 0x80 ? 1 : (c & 0xe0) == 0xc0 ? 2 : (c & 0xf0) == 0xe0 ? 3 : (c & 0xf8) == 0xf0 ? 4 : 0;
        if (!n || i + n > text.size()) return false;
        for (size_t j = 1; j < n; ++j) if ((static_cast<unsigned char>(text[i + j]) & 0xc0) != 0x80) return false;
        i += n;
    }
    return true;
}

// Keep split UTF-8 characters and split reasoning markers out of the UI stream.
struct Stream : std::streambuf {
    JNIEnv* env;
    jobject receiver;
    jmethodID method;
    Session& owner;
    std::string pending;
    std::string raw;
    bool reasoning;
    bool opening = true;
    Clock::time_point first{};
    explicit Stream(JNIEnv* e, jobject r, Session& s, bool thinking)
        : env(e), receiver(r), owner(s), pending{}, raw{}, reasoning(thinking) {
        auto cls = env->GetObjectClass(receiver);
        method = env->GetMethodID(cls, "onChunk", "([BZ)V");
        env->DeleteLocalRef(cls);
        if (!method) throw std::runtime_error("MNN receiver is unavailable");
    }
    std::streamsize xsputn(const char* data, std::streamsize count) override {
        if (count > 0 && first == Clock::time_point{}) first = Clock::now();
        raw.append(data, count);
        pending.append(data, count);
        flush(false);
        return count;
    }
    int overflow(int ch) override {
        if (ch != traits_type::eof()) { char c = static_cast<char>(ch); xsputn(&c, 1); }
        return traits_type::not_eof(ch);
    }
    void emit(const std::string& text) {
        if (text.empty() || owner.cancelled.load()) return;
        if (!reasoning && text.find_first_not_of(" \t\r\n") != std::string::npos) opening = false;
        if (text.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
            throw std::length_error("MNN output exceeds JNI array capacity");
        const auto length = static_cast<jsize>(text.size());
        auto array = env->NewByteArray(length);
        if (!array) throw std::bad_alloc();
        env->SetByteArrayRegion(array, 0, length, reinterpret_cast<const jbyte*>(text.data()));
        env->CallVoidMethod(receiver, method, array, reasoning);
        env->DeleteLocalRef(array);
        if (env->ExceptionCheck()) owner.cancelled.store(true);
    }
    void flush(bool final) {
        while (!pending.empty()) {
            size_t pos = std::string::npos;
            std::string marker;
            const std::string candidate = reasoning ? "</think>" : "<think>";
            if (reasoning || opening) {
                const auto found = pending.find(candidate);
                if (found != std::string::npos && (reasoning || pending.substr(0, found).find_first_not_of(" \t\r\n") == std::string::npos)) {
                    pos = found;
                    marker = candidate;
                }
            }
            if (pos != std::string::npos) {
                if (!valid_utf8(pending.substr(0, pos))) return;
                emit(pending.substr(0, pos));
                reasoning = marker == "<think>";
                if (!reasoning) opening = false;
                pending.erase(0, pos + marker.size());
                continue;
            }
            size_t keep = 0;
            if (!final && (reasoning || opening)) {
                for (size_t n = 1; n < candidate.size() && n <= pending.size(); ++n)
                    if (pending.compare(pending.size() - n, n, candidate, 0, n) == 0) keep = std::max(keep, n);
            }
            auto ready = pending.substr(0, pending.size() - keep);
            if (!valid_utf8(ready)) return;
            emit(ready);
            pending.erase(0, ready.size());
            return;
        }
    }
};

bool is_continuation(const ChatMessages& incoming, const ChatMessages& active) {
    if (incoming.size() != active.size() || incoming.empty()) return false;
    for (size_t i = 0; i < incoming.size(); ++i) {
        if (incoming[i].first != active[i].first || incoming[i].second != active[i].second) return false;
    }
    return true;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mrj_fancyai_engine_MnnRuntime_nativeOpen(JNIEnv* env, jclass, jobject /* application */, jbyteArray path, jbyteArray options, jint context) {
    try {
        auto state = std::make_unique<Session>();
        state->context = context > 0 ? context : 4096;
        auto config = std::make_shared<MNN::Transformer::LlmConfig>(bytes(env, path));
        state->llm = std::make_unique<Llm>(config);
        if (!state->llm->set_config(bytes(env, options)) || !state->llm->load()) throw std::runtime_error("MNN model loading failed");
        auto* owner = state.get();
        state->llm->setDebugCallback(
            [owner](const std::vector<MNN::Tensor*>&, const MNN::OperatorInfo*) {
                return !owner->cancelled.load(std::memory_order_relaxed);
            },
            [owner](const std::vector<MNN::Tensor*>&, const MNN::OperatorInfo*) {
                if (!owner->cancelled.load(std::memory_order_relaxed)) return true;
                // This callback runs on the inference thread; no cross-thread LlmContext write.
                const_cast<MNN::Transformer::LlmContext*>(owner->llm->getContext())->status = LlmStatus::USER_CANCEL;
                return false;
            });
        return reinterpret_cast<jlong>(state.release());
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "MNN model allocation failed");
    } catch (const std::exception& error) {
        fail(env, "java/lang/IllegalStateException", error.what());
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_engine_MnnRuntime_nativeSetMessages(JNIEnv* env, jobject, jlong handle, jbyteArray data) {
    try {
        auto& s = session(handle);
        std::lock_guard<std::mutex> lock(s.mutex);
        auto json = ujson::json::parse(bytes(env, data));
        ChatMessages messages;
        for (const auto& message : json)
            messages.emplace_back(message["role"].get<std::string>(), message["content"].get<std::string>());
        if (is_continuation(messages, s.messages)) return;
        s.messages = std::move(messages);
        s.llm->generate_init(nullptr, "");
    } catch (const std::exception& error) { fail(env, "java/lang/IllegalStateException", error.what()); }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_mrj_fancyai_engine_MnnRuntime_nativeGenerate(JNIEnv* env, jobject, jlong handle, jbyteArray input, jboolean thinking, jint maxTokens, jobject receiver) {
    try {
        auto& s = session(handle);
        std::lock_guard<std::mutex> lock(s.mutex);

        s.llm->set_config(thinking ? R"({"jinja":{"context":{"enable_thinking":true}}})" : R"({"jinja":{"context":{"enable_thinking":false}}})");
        auto start = Clock::now();
        auto messages = s.messages;
        messages.emplace_back("user", bytes(env, input));
        // MNN has no native context shift: drop oldest exchanges here with exact
        // token counts until the rendered prompt leaves room for generation.
        // The system message, an opening assistant prefill, and the latest user
        // input are never dropped.
        size_t pinned = 0;
        if (!messages.empty() && messages.front().first == "system") pinned = 1;
        if (messages.size() > pinned + 1 && messages[pinned].first == "assistant") ++pinned;
        const auto budget = static_cast<size_t>(s.context);
        const auto reserve = maxTokens > 0 ? static_cast<size_t>(maxTokens) : 0;
        // Trim down to 80% headroom when forced to trim, so the following turns
        // hit the fast suffix-only path until growth refills the headroom.
        const auto softTarget = (budget * 4) / 5;
        std::string prompt;
        size_t promptTokens = 0;
        bool trimmed = false;
        for (;;) {
            prompt = s.llm->apply_chat_template(messages);
            if (prompt.empty()) throw std::runtime_error("MNN model has no chat template");
            promptTokens = s.llm->tokenizer_encode(prompt).size();
            // Fits and nothing trimmed this turn: fast path, keep everything.
            // Once trimming starts, continue down to soft headroom.
            if (promptTokens + reserve <= budget && (!trimmed || promptTokens + reserve <= softTarget)) break;
            // Nothing droppable left: only pinned prefix plus latest input.
            if (messages.size() <= pinned + 1) break;
            // Oldest user exchange strictly before the latest input.
            size_t drop = pinned;
            while (drop + 1 < messages.size() - 1 && messages[drop].first != "user") ++drop;
            if (drop + 1 >= messages.size() || messages[drop].first != "user") break;
            messages.erase(messages.begin() + static_cast<std::ptrdiff_t>(drop));
            if (drop + 1 < messages.size() && messages[drop].first == "assistant")
                messages.erase(messages.begin() + static_cast<std::ptrdiff_t>(drop));
            trimmed = true;
        }
        if (promptTokens + reserve > budget)
            throw std::runtime_error("Context allocation cannot hold the prompt and reserved output");
        // Only an assistant prefill at the end opens reasoning. A literal marker
        // in the system instruction or history does not classify the new reply.
        const auto promptEnd = prompt.find_last_not_of(" \t\r\n");
        const std::string thinkMarker = "<think>";
        const bool startsThinking = promptEnd != std::string::npos &&
            promptEnd + 1 >= thinkMarker.size() &&
            prompt.compare(promptEnd + 1 - thinkMarker.size(), thinkMarker.size(), thinkMarker) == 0;
        Stream buffer(env, receiver, s, startsThinking);
        std::ostream output(&buffer);
        output.exceptions(std::ios::badbit);
        s.llm->response(messages, &output, "", maxTokens);
        buffer.flush(true);
        const auto* result = s.llm->getContext();
        const auto cancelled = s.cancelled.load() || result->status == LlmStatus::USER_CANCEL;
        if (!cancelled && result->status != LlmStatus::NORMAL_FINISHED && result->status != LlmStatus::MAX_TOKENS_FINISHED)
            throw std::runtime_error("MNN inference failed");
        const jlong values[] = {
            cancelled ? 1L : 0L, result->prompt_len, result->gen_seq_len,
            result->prefill_us, result->decode_us,
            buffer.first == Clock::time_point{} ? 0L : std::chrono::duration_cast<std::chrono::microseconds>(buffer.first - start).count(),
        };
        if (cancelled) { s.llm->reset(); s.llm->generate_init(nullptr, ""); }
        else {
            std::string assistantResponse = buffer.raw;
            MNN::Transformer::stripThinkBlocks(assistantResponse);
            messages.emplace_back("assistant", std::move(assistantResponse));
            s.messages = std::move(messages);
            s.llm->syncPromptCache(s.messages);
        }
        if (env->ExceptionCheck()) return nullptr;
        auto array = env->NewLongArray(6);
        if (array) env->SetLongArrayRegion(array, 0, 6, values);
        return array;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "MNN inference allocation failed");
    } catch (const std::exception& error) {
        // A failed/cancelled graph must not leave a poisoned cache for a later request.
        if (handle) { session(handle).llm->reset(); session(handle).llm->generate_init(nullptr, ""); }
        fail(env, "java/lang/IllegalStateException", error.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_engine_MnnRuntime_nativeCancel(JNIEnv*, jobject, jlong handle) {
    if (handle) session(handle).cancelled.store(true, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_engine_MnnRuntime_nativeClose(JNIEnv*, jobject, jlong handle) {
    std::unique_ptr<Session> s(reinterpret_cast<Session*>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_engine_MnnRuntime_nativePrepare(JNIEnv*, jobject, jlong handle) {
    session(handle).cancelled.store(false, std::memory_order_relaxed);
}
