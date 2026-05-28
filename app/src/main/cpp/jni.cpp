#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "MNNInference"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Forward declare MNN types (we don't include headers to avoid dependency hell)
// In a real build, these would come from MNN/Interpreter.hpp etc.
namespace MNN {
    class Interpreter;
    class Session;
    class Tensor;
    enum ForwardType { MNN_FORWARD_CPU = 0 };
}

// Global model state
static MNN::Interpreter* g_interpreter = nullptr;
static MNN::Session* g_session = nullptr;
static int g_vocab_size = 32000;  // Typical for Gemma models
static int g_max_seq_len = 2048;  // Max sequence length

// Simple tokenizer (assumes space-separated words map to vocab indices)
// A real implementation would use BPE or SentencePiece
std::vector<int> tokenize(const std::string& text, int vocab_size) {
    std::vector<int> tokens;
    tokens.push_back(1);  // BOS token (typical for Gemma)

    // Simple heuristic: map words to token IDs
    // In production: use proper tokenizer library
    size_t pos = 0;
    while (pos < text.length()) {
        size_t space = text.find(' ', pos);
        if (space == std::string::npos) space = text.length();

        std::string word = text.substr(pos, space - pos);
        if (!word.empty()) {
            // Hash word to token ID (0-vocab_size)
            int hash = 0;
            for (char c : word) hash = (hash * 31 + c) % vocab_size;
            if (hash < 2) hash = 2;  // Skip BOS/EOS
            tokens.push_back(hash);
        }
        pos = space + 1;
    }

    return tokens;
}

// Detokenize: simple join with spaces (real version uses vocab lookup)
std::string detokenize(const std::vector<int>& tokens) {
    std::string result;
    for (int i = 1; i < tokens.size(); i++) {  // Skip BOS
        if (i > 1) result += " ";
        result += "[token_" + std::to_string(tokens[i]) + "]";
    }
    return result;
}

extern "C" {

/**
 * Load a .mnn model file and create an inference session.
 */
JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_MNNInference_nativeLoadModel(JNIEnv *env, jclass clazz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        LOGE("Failed to get model path");
        return JNI_FALSE;
    }

    LOGI("Loading model from: %s", path);

    try {
        // Phase 3 Implementation:
        // 1. auto interpreter = MNN::Interpreter::createFromFile(path);
        // 2. if (!interpreter) { LOGE("Failed to load"); return JNI_FALSE; }
        // 3. MNN::ScheduleConfig config;
        //    config.type = MNN::MNN_FORWARD_CPU;
        //    config.numThread = 4;
        // 4. auto session = interpreter->createSession(config);
        // 5. g_interpreter = interpreter;
        //    g_session = session;

        // Stub: pretend we loaded
        g_interpreter = (MNN::Interpreter*)1;
        g_session = (MNN::Session*)1;

        LOGI("Model loaded successfully");
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_TRUE;

    } catch (const std::exception &e) {
        LOGE("Exception during model load: %s", e.what());
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }
}

/**
 * Run inference on a prompt using the loaded model.
 *
 * Phase 3 Full Pipeline:
 * 1. Tokenize prompt -> token_ids vector
 * 2. Prepare input tensor with shape [1, seq_len] of int32 token IDs
 * 3. Loop up to max_tokens:
 *    a. Copy token_ids to input tensor
 *    b. session->runSession()
 *    c. Get output logits tensor [1, seq_len, vocab_size]
 *    d. Sample from last position: argmax(logits[0, -1, :])
 *    e. Append next_token to token_ids
 *    f. If next_token == EOS, break
 * 4. Detokenize token_ids -> text response
 * 5. Return response string
 */
JNIEXPORT jstring JNICALL
Java_com_mrj_fancyai_MNNInference_nativeInference(JNIEnv *env, jclass clazz,
                                                   jstring prompt, jint max_tokens) {
    if (!g_interpreter || !g_session) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_cstr) {
        LOGE("Failed to get prompt");
        return nullptr;
    }

    LOGI("Inference: prompt='%s', max_tokens=%d", prompt_cstr, max_tokens);

    try {
        // Tokenize prompt
        std::vector<int> token_ids = tokenize(prompt_cstr, g_vocab_size);
        LOGD("Tokenized to %zu tokens", token_ids.size());

        // Phase 3 Implementation:
        //
        // // Get input tensor
        // auto input_tensor = g_interpreter->getSessionInput(g_session, nullptr);
        // if (!input_tensor) { LOGE("No input tensor"); return env->NewStringUTF(""); }
        //
        // // Prepare input shape [1, seq_len]
        // std::vector<int> shape = {1, (int)token_ids.size()};
        // g_interpreter->resizeSession(g_session, shape);
        //
        // // Copy token IDs to input tensor
        // int32_t* input_data = input_tensor->host<int32_t>();
        // std::memcpy(input_data, token_ids.data(), token_ids.size() * sizeof(int32_t));
        //
        // // Inference loop
        // int eos_token = 2;  // End-of-sequence token (model-specific)
        // for (int iter = 0; iter < max_tokens && token_ids.size() < g_max_seq_len; iter++) {
        //     // Run model
        //     g_session->runSession(g_session);
        //
        //     // Get output logits tensor
        //     auto output_tensor = g_interpreter->getSessionOutput(g_session, nullptr);
        //     if (!output_tensor) break;
        //
        //     // Sample from last position
        //     float* logits = output_tensor->host<float>();
        //     int seq_len = token_ids.size();
        //     int vocab_size = g_vocab_size;
        //
        //     // Find argmax at last position
        //     int next_token = 0;
        //     float max_logit = logits[seq_len * vocab_size];
        //     for (int i = 1; i < vocab_size; i++) {
        //         float val = logits[seq_len * vocab_size + i];
        //         if (val > max_logit) {
        //             max_logit = val;
        //             next_token = i;
        //         }
        //     }
        //
        //     token_ids.push_back(next_token);
        //     if (next_token == eos_token) break;
        //
        //     LOGD("Generated token %d (iter %d/%d)", next_token, iter + 1, max_tokens);
        // }

        // Detokenize back to text
        std::string response = detokenize(token_ids);

        env->ReleaseStringUTFChars(prompt, prompt_cstr);
        return env->NewStringUTF(response.c_str());

    } catch (const std::exception &e) {
        LOGE("Inference exception: %s", e.what());
        env->ReleaseStringUTFChars(prompt, prompt_cstr);
        return env->NewStringUTF("");
    }
}

/**
 * Unload model and free resources.
 */
JNIEXPORT void JNICALL
Java_com_mrj_fancyai_MNNInference_nativeUnloadModel(JNIEnv *env, jclass clazz) {
    if (g_interpreter) {
        LOGI("Unloading model");
        // Phase 3: delete g_interpreter; delete g_session;
        g_interpreter = nullptr;
        g_session = nullptr;
    }
}

} // extern "C"
