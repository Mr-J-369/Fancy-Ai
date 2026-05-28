#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "MNNInference"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global model handles
// In a full implementation, these would be:
// - std::shared_ptr<MNN::Interpreter> g_interpreter
// - MNN::Session* g_session
static void* g_model = nullptr;
static void* g_session = nullptr;

extern "C" {

/**
 * Load a .mnn model file and create an inference session.
 * Full implementation requires:
 * 1. MNN headers (Interpreter.hpp, Tensor.hpp, etc.)
 * 2. Call MNN::Interpreter::createFromFile(path)
 * 3. Create session with MNN::ScheduleConfig
 * 4. Handle input/output tensor setup
 */
JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_MNNInference_nativeLoadModel(JNIEnv *env, jclass clazz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        LOGE("Failed to get model path");
        return JNI_FALSE;
    }

    LOGI("Loading model: %s", path);

    // TODO: Phase 3 - Implement actual MNN API calls
    // auto interpreter = MNN::Interpreter::createFromFile(path);
    // if (!interpreter) return JNI_FALSE;
    // MNN::ScheduleConfig config; config.type = MNN_FORWARD_CPU; config.numThread = 4;
    // auto session = interpreter->createSession(config);
    // if (!session) return JNI_FALSE;
    // g_interpreter = std::shared_ptr<MNN::Interpreter>(interpreter);
    // g_session = session;

    g_model = (void*)1;  // Mark as "loaded" (stub)
    g_session = (void*)1;

    LOGI("Model load (stub) successful");
    env->ReleaseStringUTFChars(model_path, path);
    return JNI_TRUE;
}

/**
 * Run inference on a prompt.
 * Full implementation requires:
 * 1. Tokenize prompt string to token IDs
 * 2. Prepare input tensor with token IDs
 * 3. Loop up to max_tokens:
 *    - g_session->runSession()
 *    - Get output logits tensor
 *    - Sample/argmax to get next token
 *    - Append to output sequence
 * 4. Detokenize output tokens back to text
 * 5. Return result string
 */
JNIEXPORT jstring JNICALL
Java_com_mrj_fancyai_MNNInference_nativeInference(JNIEnv *env, jclass clazz,
                                                   jstring prompt, jint max_tokens) {
    if (!g_model || !g_session) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_cstr) {
        LOGE("Failed to get prompt string");
        return nullptr;
    }

    LOGD("Inference: prompt='%s', max_tokens=%d", prompt_cstr, max_tokens);

    // TODO: Phase 3 - Implement full LLM inference pipeline
    // - Tokenize prompt
    // - Prepare input tensors
    // - Run generation loop (max_tokens iterations)
    // - Return decoded text

    std::string response = "[MNN stub] " + std::string(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    return env->NewStringUTF(response.c_str());
}

/**
 * Unload model and free resources.
 */
JNIEXPORT void JNICALL
Java_com_mrj_fancyai_MNNInference_nativeUnloadModel(JNIEnv *env, jclass clazz) {
    if (g_model) {
        LOGI("Unloading model");
        // TODO: Phase 3 - Clean up MNN objects
        // g_interpreter = nullptr;  (auto-deletes via shared_ptr)
        // g_session = nullptr;
        g_model = nullptr;
        g_session = nullptr;
    }
}

} // extern "C"
