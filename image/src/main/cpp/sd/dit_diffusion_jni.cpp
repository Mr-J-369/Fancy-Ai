#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "DitEngine.h"

#define LOG_TAG "DitDiffusionJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex gEngineMutex;
void* gEngineHandle = nullptr;
const dit_engine_api* gEngineApi = nullptr;
dit_ctx* gDitCtx = nullptr;
std::atomic<bool> gCancelled{false};

void onDitEngineLog(int level, const char* text, void* /* user_data */) {
    if (!text) return;
    switch (level) {
        case 3: LOGE("[DitEngine] %s", text); break;
        case 2: LOGW("[DitEngine] %s", text); break;
        case 1: LOGI("[DitEngine] %s", text); break;
        default: LOGI("[DitEngine] %s", text); break;
    }
}

struct ProgressBridgeContext {
    JNIEnv* env;
    jobject callback;
    jmethodID onProgressMethod;
    int expectedTotalSteps;
};

bool progressBridge(int step, int totalSteps, float /* stepSeconds */, void* userData) {
    if (gCancelled.load()) {
        return false;
    }
    auto* ctx = static_cast<ProgressBridgeContext*>(userData);
    if (!ctx || !ctx->callback || !ctx->onProgressMethod) {
        return true;
    }

    const int total = (totalSteps > 0) ? totalSteps : ctx->expectedTotalSteps;
    const int percent = (total > 0) ? std::min(100, std::max(0, step * 100 / total)) : 0;
    LOGI("DiT Progress: step=%d/%d percent=%d%%", step, total, percent);

    ctx->env->CallVoidMethod(ctx->callback, ctx->onProgressMethod, static_cast<jint>(percent));
    if (ctx->env->ExceptionCheck()) {
        ctx->env->ExceptionClear();
    }

    return !gCancelled.load();
}

bool copyBitmapToRgb(JNIEnv* env, jobject bitmap, std::vector<uint8_t>& outRgb, int& width, int& height) {
    AndroidBitmapInfo info{};
    if (!bitmap ||
        AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return false;
    }
    width = static_cast<int>(info.width);
    height = static_cast<int>(info.height);
    if (width <= 0 || height <= 0) {
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return false;
    }
    outRgb.resize(static_cast<size_t>(width) * height * 3);
    for (int y = 0; y < height; ++y) {
        const auto* srcRow = static_cast<const uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        auto* dstRow = outRgb.data() + static_cast<size_t>(y) * width * 3;
        for (int x = 0; x < width; ++x) {
            dstRow[x * 3 + 0] = srcRow[x * 4 + 0];
            dstRow[x * 3 + 1] = srcRow[x * 4 + 1];
            dstRow[x * 3 + 2] = srcRow[x * 4 + 2];
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

bool copyRgbToBitmap(JNIEnv* env, jobject bitmap, const uint8_t* rgb, int width, int height, int channels = 3) {
    AndroidBitmapInfo info{};
    if (!bitmap || !rgb ||
        AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int>(info.width) != width || static_cast<int>(info.height) != height) {
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return false;
    }
    for (int y = 0; y < height; ++y) {
        const auto* srcRow = rgb + static_cast<size_t>(y) * width * channels;
        auto* dstRow = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        if (channels == 4) {
            std::memcpy(dstRow, srcRow, static_cast<size_t>(width) * 4);
        } else {
            for (int x = 0; x < width; ++x) {
                dstRow[x * 4 + 0] = srcRow[x * 3 + 0];
                dstRow[x * 4 + 1] = srcRow[x * 3 + 1];
                dstRow[x * 4 + 2] = srcRow[x * 3 + 2];
                dstRow[x * 4 + 3] = 255;
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

void unloadEngineLocked() {
    if (gDitCtx && gEngineApi) {
        gEngineApi->destroy(gDitCtx);
        gDitCtx = nullptr;
    }
    if (gEngineHandle) {
        dlclose(gEngineHandle);
        gEngineHandle = nullptr;
        gEngineApi = nullptr;
    }
}

void ensureDspSkeletons(JNIEnv* env, jobject application, const std::string& dspDir) {
    if (dspDir.empty() || !application) return;

    mkdir(dspDir.c_str(), 0755);

    const std::vector<std::string> skelFiles = {
        "libggml-htp-v79.so",
        "libggml-htp-v81.so",
        "libggml-htp-v85.so"
    };

    jclass contextClass = env->GetObjectClass(application);
    if (!contextClass) return;
    jmethodID getAssetsMethod = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    if (!getAssetsMethod) return;

    jobject assetManagerObj = env->CallObjectMethod(application, getAssetsMethod);
    if (!assetManagerObj) return;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManagerObj);
    if (!mgr) return;

    for (const auto& fileName : skelFiles) {
        const std::string destPath = dspDir + "/" + fileName;
        if (access(destPath.c_str(), F_OK) == 0) {
            continue;
        }

        const std::string assetPath = "ditlibs/" + fileName;
        AAsset* asset = AAssetManager_open(mgr, assetPath.c_str(), AASSET_MODE_BUFFER);
        if (!asset) {
            LOGW("DSP asset %s not found in APK", assetPath.c_str());
            continue;
        }

        const void* buffer = AAsset_getBuffer(asset);
        const off_t length = AAsset_getLength(asset);
        if (buffer && length > 0) {
            FILE* fp = fopen(destPath.c_str(), "wb");
            if (fp) {
                fwrite(buffer, 1, static_cast<size_t>(length), fp);
                fclose(fp);
                chmod(destPath.c_str(), 0755);
                LOGI("Extracted DSP skeleton to %s (%ld bytes)", destPath.c_str(), static_cast<long>(length));
            }
        }
        AAsset_close(asset);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeAbiVersion(JNIEnv* /* env */, jobject /* thiz */) {
    return 1;
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeCancel(JNIEnv* /* env */, jobject /* thiz */) {
    gCancelled.store(true);
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeWasCancelled(JNIEnv* /* env */, jobject /* thiz */) {
    return gCancelled.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeUnload(JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    unloadEngineLocked();
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeLoad(
    JNIEnv* env,
    jobject /* thiz */,
    jobject application,
    jstring modelDirStr,
    jstring nativeLibDirStr,
    jstring dspDirStr,
    jstring sharedDirStr,
    jint kindInt,
    jint threads) {
    std::lock_guard<std::mutex> lock(gEngineMutex);

    unloadEngineLocked();
    gCancelled.store(false);

    const char* rawNativeLibDir = nativeLibDirStr ? env->GetStringUTFChars(nativeLibDirStr, nullptr) : nullptr;
    const char* rawDspDir = dspDirStr ? env->GetStringUTFChars(dspDirStr, nullptr) : nullptr;
    const std::string nativeLibDir = rawNativeLibDir ? rawNativeLibDir : "";
    const std::string dspDir = rawDspDir ? rawDspDir : "";
    if (rawNativeLibDir) env->ReleaseStringUTFChars(nativeLibDirStr, rawNativeLibDir);
    if (rawDspDir) env->ReleaseStringUTFChars(dspDirStr, rawDspDir);

    if (!dspDir.empty()) {
        ensureDspSkeletons(env, application, dspDir);
        std::string newPath = dspDir + ";" + nativeLibDir + ";/system/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/dsp";
        const char* currentAdsp = getenv("ADSP_LIBRARY_PATH");
        if (currentAdsp && currentAdsp[0] != '\0') {
            newPath += ";";
            newPath += currentAdsp;
        }
        setenv("ADSP_LIBRARY_PATH", newPath.c_str(), 1);
        LOGI("Configured ADSP_LIBRARY_PATH: %s", newPath.c_str());
    }

    std::string enginePath = nativeLibDir.empty() ? "libdit_engine.so" : (nativeLibDir + "/libdit_engine.so");
    gEngineHandle = dlopen(enginePath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!gEngineHandle) {
        gEngineHandle = dlopen("libdit_engine.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (!gEngineHandle) {
        const char* err = dlerror();
        LOGE("Failed to dlopen libdit_engine.so: %s", err ? err : "unknown error");
        return JNI_FALSE;
    }

    auto getApiFn = reinterpret_cast<dit_engine_get_api_fn>(dlsym(gEngineHandle, DIT_ENGINE_ENTRY_SYMBOL));
    if (!getApiFn) {
        LOGE("Entry symbol %s not found in engine library", DIT_ENGINE_ENTRY_SYMBOL);
        unloadEngineLocked();
        return JNI_FALSE;
    }

    gEngineApi = getApiFn(DIT_ENGINE_ABI_VERSION);
    if (!gEngineApi || gEngineApi->abi_version != DIT_ENGINE_ABI_VERSION) {
        LOGE("Engine API ABI mismatch (requested %d)", DIT_ENGINE_ABI_VERSION);
        unloadEngineLocked();
        return JNI_FALSE;
    }

    if (gEngineApi->set_log_callback) {
        gEngineApi->set_log_callback(onDitEngineLog, nullptr);
    }

    const char* rawModelDir = modelDirStr ? env->GetStringUTFChars(modelDirStr, nullptr) : nullptr;
    const char* rawSharedDir = sharedDirStr ? env->GetStringUTFChars(sharedDirStr, nullptr) : nullptr;
    const std::string modelDir = rawModelDir ? rawModelDir : "";
    const std::string sharedDir = rawSharedDir ? rawSharedDir : "";
    if (rawModelDir) env->ReleaseStringUTFChars(modelDirStr, rawModelDir);
    if (rawSharedDir) env->ReleaseStringUTFChars(sharedDirStr, rawSharedDir);

    std::string diffModelPath;
    struct stat st{};
    if (!modelDir.empty() && stat(modelDir.c_str(), &st) == 0 && S_ISREG(st.st_mode)) {
        diffModelPath = modelDir;
    } else if (!modelDir.empty()) {
        const std::vector<std::string> candidates = {
            "dit.safetensors",
            "qwen_image_2_1.safetensors",
            "diffusion_model.safetensors",
            "model.safetensors",
            "diffusion_model.gguf",
            "dit.gguf",
            "model.gguf"
        };
        for (const auto& candidate : candidates) {
            std::string path = modelDir + "/" + candidate;
            if (access(path.c_str(), F_OK) == 0) {
                diffModelPath = path;
                break;
            }
        }
        if (diffModelPath.empty()) {
            DIR* dir = opendir(modelDir.c_str());
            if (dir) {
                struct dirent* entry;
                while ((entry = readdir(dir)) != nullptr) {
                    const std::string name(entry->d_name);
                    if (name == "." || name == ".." || name == "vae.safetensors" ||
                        name == "ae.safetensors" || name == "llm.gguf") {
                        continue;
                    }
                    std::string lower = name;
                    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
                    if (lower.find("clip") != std::string::npos ||
                        lower.find("text_encoder") != std::string::npos ||
                        lower.find("t5xxl") != std::string::npos ||
                        lower.find("vae") != std::string::npos ||
                        lower.find("tokenizer") != std::string::npos) {
                        continue;
                    }
                    if ((name.size() > 5 && name.rfind(".gguf") == name.size() - 5) ||
                        (name.size() > 12 && name.rfind(".safetensors") == name.size() - 12)) {
                        diffModelPath = modelDir + "/" + name;
                        break;
                    }
                }
                closedir(dir);
            }
        }
    }

    const auto modelKind = static_cast<dit_model_kind>(kindInt);

    std::string qwenDir;
    if (!sharedDir.empty()) {
        size_t lastSlash = sharedDir.find_last_of('/');
        if (lastSlash != std::string::npos) {
            qwenDir = sharedDir.substr(0, lastSlash) + "/qwen_components";
        }
    }

    std::string llmPath;
    std::vector<std::string> llmCandidates;
    if (modelKind == DIT_MODEL_QWEN_IMAGE_2_1) {
        llmCandidates = {
            modelDir + "/Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf",
            qwenDir + "/Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf",
            modelDir + "/llm.gguf",
            qwenDir + "/llm.gguf",
        };
    } else {
        llmCandidates = {
            modelDir + "/llm.gguf",
            sharedDir + "/llm.gguf",
            modelDir + "/clip.gguf",
            sharedDir + "/clip.gguf",
            modelDir + "/text_encoder.safetensors",
            sharedDir + "/text_encoder.safetensors",
        };
    }
    for (const auto& candidate : llmCandidates) {
        if (!candidate.empty() && access(candidate.c_str(), F_OK) == 0) {
            llmPath = candidate;
            break;
        }
    }

    const char* primaryVae = (modelKind == DIT_MODEL_Z_IMAGE) ? "ae.safetensors" : "vae.safetensors";
    const char* secondaryVae = (modelKind == DIT_MODEL_Z_IMAGE) ? "vae.safetensors" : "ae.safetensors";

    std::string vaePath;
    std::vector<std::string> vaeCandidates;
    if (modelKind == DIT_MODEL_QWEN_IMAGE_2_1) {
        vaeCandidates = {
            modelDir + "/qwen_image_2.1_vae_bf16.safetensors",
            qwenDir + "/qwen_image_2.1_vae_bf16.safetensors",
            modelDir + "/vae.safetensors",
            qwenDir + "/vae.safetensors",
        };
    } else {
        vaeCandidates = {
            modelDir + "/" + primaryVae,
            sharedDir + "/" + primaryVae,
            modelDir + "/" + secondaryVae,
            sharedDir + "/" + secondaryVae,
        };
    }
    for (const auto& candidate : vaeCandidates) {
        if (!candidate.empty() && access(candidate.c_str(), F_OK) == 0) {
            vaePath = candidate;
            break;
        }
    }

    if (diffModelPath.empty() || access(diffModelPath.c_str(), F_OK) != 0) {
        LOGE("DiT diffusion model not found in %s", modelDir.c_str());
        unloadEngineLocked();
        return JNI_FALSE;
    }
    if (llmPath.empty() || access(llmPath.c_str(), F_OK) != 0) {
        LOGE("DiT text encoder not found (checked %s and %s)", modelDir.c_str(), sharedDir.c_str());
        unloadEngineLocked();
        return JNI_FALSE;
    }
    if (vaePath.empty() || access(vaePath.c_str(), F_OK) != 0) {
        LOGE("DiT VAE not found (checked %s and %s)", modelDir.c_str(), sharedDir.c_str());
        unloadEngineLocked();
        return JNI_FALSE;
    }

    LOGI("Loading DiT kind=%d diff=%s llm=%s vae=%s", kindInt, diffModelPath.c_str(), llmPath.c_str(), vaePath.c_str());

    dit_ctx_params params{};
    params.kind = modelKind;
    params.diffusion_model_path = diffModelPath.c_str();
    params.llm_path = llmPath.c_str();
    params.llm_vision_path = nullptr;
    params.vae_path = vaePath.c_str();
    params.backend = "diffusion=HTP0,te=HTP0,vae=HTP0";
    params.params_backend = "all=disk";
    params.n_threads = threads > 0 ? threads : 4;
    params.flash_attn = true;
    params.vae_conv_direct = true;

    gDitCtx = gEngineApi->create(&params);
    if (!gDitCtx) {
        const char* err = gEngineApi->last_error(nullptr);
        LOGE("Failed to create DiT context: %s", err ? err : "unknown error");
        unloadEngineLocked();
        return JNI_FALSE;
    }

    LOGI("DiT context successfully created for kind=%d", kindInt);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeGenerate(
    JNIEnv* env,
    jobject /* thiz */,
    jstring promptStr,
    jstring negativePromptStr,
    jint width,
    jint height,
    jint steps,
    jfloat cfgScale,
    jlong seed,
    jobject sourceBitmap,
    jfloat denoiseStrength,
    jobject outputBitmap,
    jobject progressCallback) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gCancelled.store(false);

    if (!gDitCtx || !gEngineApi) {
        LOGE("nativeGenerate called without valid DiT context");
        return JNI_FALSE;
    }

    const char* prompt = promptStr ? env->GetStringUTFChars(promptStr, nullptr) : "";
    const char* negativePrompt = negativePromptStr ? env->GetStringUTFChars(negativePromptStr, nullptr) : "";

    std::vector<uint8_t> initRgb;
    int initW = 0;
    int initH = 0;
    if (sourceBitmap) {
        copyBitmapToRgb(env, sourceBitmap, initRgb, initW, initH);
    }

    dit_gen_params params{};
    params.prompt = prompt;
    params.negative_prompt = negativePrompt;
    params.width = width;
    params.height = height;
    params.steps = steps;
    params.cfg_scale = cfgScale;
    params.guidance = 3.5f;
    params.seed = seed;
    params.sample_method = "euler";
    params.init_image_rgb = initRgb.empty() ? nullptr : initRgb.data();
    params.init_width = initW;
    params.init_height = initH;
    params.denoise_strength = denoiseStrength;
    params.vae_tile_size = 0;
    params.vae_tile_overlap = 0.0f;

    ProgressBridgeContext progressContext{env, progressCallback, nullptr, steps};
    if (progressCallback) {
        jclass cbClass = env->GetObjectClass(progressCallback);
        if (cbClass) {
            progressContext.onProgressMethod = env->GetMethodID(cbClass, "onProgress", "(I)V");
            env->DeleteLocalRef(cbClass);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }

    uint8_t* outRgb = nullptr;
    int outWidth = 0;
    int outHeight = 0;
    int outChannels = 3;

    const bool ok = gEngineApi->generate(
        gDitCtx,
        &params,
        progressCallback ? &progressBridge : nullptr,
        nullptr,
        &progressContext,
        &outRgb,
        &outWidth,
        &outHeight,
        &outChannels);

    if (promptStr) env->ReleaseStringUTFChars(promptStr, prompt);
    if (negativePromptStr) env->ReleaseStringUTFChars(negativePromptStr, negativePrompt);

    if (!ok || !outRgb) {
        const char* err = gEngineApi->last_error(gDitCtx);
        LOGE("DiT generation failed: %s", err ? err : (gCancelled.load() ? "cancelled" : "unknown"));
        return JNI_FALSE;
    }

    const bool copied = copyRgbToBitmap(env, outputBitmap, outRgb, outWidth, outHeight, outChannels);
    gEngineApi->free_image(outRgb);

    if (!copied) {
        LOGE("Failed to copy generated DiT output to Bitmap");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

}  // extern "C"
