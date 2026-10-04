#include <jni.h>
#include <android/log.h>
#include <string>
#include <utility>
#include <vector>

#include "safetensor2mnn/SafeTensor2MNN.hpp"

#define TAG "fancysdconv"
#if FANCY_INTEGRITY_REQUIRED
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

namespace {
std::string jstr(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_SdConvert_nativeConvert(
        JNIEnv* env,
        jclass,
        jobject /* application */,
        jstring directory,
        jstring checkpointName,
        jboolean clipSkip2,
        jobjectArray loraNames,
        jfloatArray loraStrengths) {
    const std::string dir = jstr(env, directory);
    const std::string checkpoint = jstr(env, checkpointName);
    std::vector<std::pair<std::string, float>> loras;
    if (loraNames) {
        const jsize count = env->GetArrayLength(loraNames);
        jfloat* strengths = loraStrengths
            ? env->GetFloatArrayElements(loraStrengths, nullptr)
            : nullptr;
        const jsize strengthCount = loraStrengths ? env->GetArrayLength(loraStrengths) : 0;
        for (jsize index = 0; index < count; ++index) {
            auto name = reinterpret_cast<jstring>(env->GetObjectArrayElement(loraNames, index));
            const std::string lora = jstr(env, name);
            if (name) env->DeleteLocalRef(name);
            if (!lora.empty()) {
                loras.emplace_back(
                    lora,
                    (strengths && index < strengthCount) ? strengths[index] : 1.0f);
            }
        }
        if (strengths) {
            env->ReleaseFloatArrayElements(loraStrengths, strengths, JNI_ABORT);
        }
    }

    LOGI(
        "converting %s/%s -> MNN (clip_skip_2=%d, loras=%zu)",
        dir.c_str(),
        checkpoint.c_str(),
        clipSkip2 == JNI_TRUE ? 1 : 0,
        loras.size());
    try {
        if (!aura::generateMNNModels(
                dir,
                checkpoint,
                clipSkip2 == JNI_TRUE,
                loras)) {
            LOGE("conversion failed (write error)");
            return JNI_FALSE;
        }
        LOGI("conversion done");
        return JNI_TRUE;
    } catch (const std::exception& failure) {
        LOGE("conversion failed: %s", failure.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("conversion failed (unknown)");
        return JNI_FALSE;
    }
}
