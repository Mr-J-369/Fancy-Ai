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
std::string gLastError;
std::mutex gEngineDiagnosticLogMutex;
std::string gEngineDiagnosticLog;

constexpr int kDitModelMossImage = 3;
constexpr int kDitModelKrea2 = 4;
constexpr int kDitModelFlux1 = 6;
constexpr int kDitModelAnima = 7;

int gModelKind = 0;

float distilledGuidanceForKind(int kind) {
    switch (kind) {
        case 1:
        case kDitModelFlux1:
        case 0:
        case 2:
        case kDitModelMossImage:
        case kDitModelKrea2:
        case kDitModelAnima:
        default:
            return 3.5f;
    }
}

bool hasMarker(const std::string& modelDir, const char* marker) {
    if (modelDir.empty() || !marker) return false;
    std::string path(modelDir);
    path += '/';
    path += marker;
    return access(path.c_str(), F_OK) == 0;
}

bool isAuxWeight(const std::string& name) {
    if (name == "." || name == ".." || name == "vae.safetensors" ||
        name == "ae.safetensors" || name == "llm.gguf") {
        return true;
    }
    std::string lower = name;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    return lower.find("clip") != std::string::npos ||
        lower.find("text_encoder") != std::string::npos ||
        lower.find("t5xxl") != std::string::npos ||
        lower.find("t5-v1") != std::string::npos ||
        lower.find("t5") == 0 ||
        lower.find("tae") == 0 ||
        lower.find("vae") != std::string::npos ||
        lower.find("tokenizer") != std::string::npos ||
        lower.find("qwen3") != std::string::npos ||
        lower.find("qwen_3_06b") != std::string::npos ||
        lower.find("mmproj") != std::string::npos ||
        lower.find("vision") != std::string::npos;
}

bool isEncoderWeight(const std::string& name) {
    std::string lower = name;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    return lower.find("llm") != std::string::npos ||
        lower.find("clip") != std::string::npos ||
        lower.find("text_encoder") != std::string::npos ||
        lower.find("t5xxl") != std::string::npos ||
        lower.find("t5-v1") != std::string::npos ||
        lower.find("t5") == 0 ||
        lower.find("qwen3") != std::string::npos ||
        lower.find("qwen_3_06b") != std::string::npos;
}

bool isVisionWeight(const std::string& name) {
    std::string lower = name;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    return lower.find("mmproj") != std::string::npos ||
        lower.find("vision") != std::string::npos;
}

bool hasWeightExtension(const std::string& name) {
    return (name.size() > 5 && name.rfind(".gguf") == name.size() - 5) ||
        (name.size() > 12 && name.rfind(".safetensors") == name.size() - 12);
}

std::string findModelEncoder(const std::string& modelDir) {
    if (modelDir.empty()) return {};
    DIR* dir = opendir(modelDir.c_str());
    if (!dir) return {};
    std::string found;
    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        const std::string name(entry->d_name);
        if (hasWeightExtension(name) && isEncoderWeight(name)) {
            found = modelDir;
            found += '/';
            found += name;
            break;
        }
    }
    closedir(dir);
    return found;
}

std::string findVisionModel(const std::string& dirPath) {
    if (dirPath.empty()) return {};
    DIR* dir = opendir(dirPath.c_str());
    if (!dir) return {};
    std::string found;
    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        const std::string name(entry->d_name);
        if (hasWeightExtension(name) && isVisionWeight(name)) {
            found = dirPath;
            found += '/';
            found += name;
            break;
        }
    }
    closedir(dir);
    return found;
}

void onDitEngineLog(int level, const char* text, void* /* user_data */) {
    if (!text) return;
    if (level >= 4) {
        LOGE("[DitEngine] %s", text);
    } else if (level == 3) {
        LOGW("[DitEngine] %s", text);
    } else {
        LOGI("[DitEngine] %s", text);
    }
    std::string message(text);
    std::replace(message.begin(), message.end(), '\n', ' ');
    std::replace(message.begin(), message.end(), '\r', ' ');
    if (!message.empty()) {
        std::lock_guard<std::mutex> lock(gEngineDiagnosticLogMutex);
        if (!gEngineDiagnosticLog.empty()) gEngineDiagnosticLog += " | ";
        gEngineDiagnosticLog += message;
    }
}

struct ProgressBridgeContext {
    JNIEnv* env;
    jobject callback;
    jmethodID onProgressMethod;
    int expectedTotalSteps;
    jobject previewCallback;
    jmethodID onPreviewMethod;
};

bool progressBridge(int step, int totalSteps, float /* stepSeconds */, void* userData) {
    if (gCancelled.load()) {
        return false;
    }
    auto* ctx = static_cast<ProgressBridgeContext*>(userData);
    if (!ctx || !ctx->callback || !ctx->onProgressMethod) {
        return true;
    }
    if (totalSteps > 0 && totalSteps != ctx->expectedTotalSteps) {
        return !gCancelled.load();
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

void previewBridge(int /* step */, const uint8_t* rgb, int width, int height, void* userData) {
    auto* ctx = static_cast<ProgressBridgeContext*>(userData);
    if (gCancelled.load() || !ctx || !ctx->previewCallback || !ctx->onPreviewMethod ||
        !rgb || width <= 0 || height <= 0) {
        return;
    }

    constexpr int kMinPreviewEdge = 256;
    constexpr int kMaxPreviewEdge = 384;
    const int maxDim = std::max(width, height);
    const float scale = (maxDim < kMinPreviewEdge)
        ? (static_cast<float>(kMinPreviewEdge) / maxDim)
        : std::min(1.0f, static_cast<float>(kMaxPreviewEdge) / maxDim);
    const int previewWidth = std::max(1, static_cast<int>(width * scale));
    const int previewHeight = std::max(1, static_cast<int>(height * scale));
    JNIEnv* env = ctx->env;
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapClass || !configClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (configClass) env->DeleteLocalRef(configClass);
        if (bitmapClass) env->DeleteLocalRef(bitmapClass);
        return;
    }
    jfieldID configField = env->GetStaticFieldID(
        configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID createBitmap = env->GetStaticMethodID(
        bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!configField || !createBitmap || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(configClass);
        env->DeleteLocalRef(bitmapClass);
        return;
    }
    jobject config = env->GetStaticObjectField(configClass, configField);
    jobject bitmap = config
        ? env->CallStaticObjectMethod(bitmapClass, createBitmap, previewWidth, previewHeight, config)
        : nullptr;
    if (!bitmap || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (bitmap) env->DeleteLocalRef(bitmap);
        if (config) env->DeleteLocalRef(config);
        env->DeleteLocalRef(configClass);
        env->DeleteLocalRef(bitmapClass);
        return;
    }

    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    const bool bitmapReady =
        AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
        info.format == ANDROID_BITMAP_FORMAT_RGBA_8888;
    const bool pixelsLocked = bitmapReady &&
        AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS;
    if (pixelsLocked && pixels) {
        for (int y = 0; y < previewHeight; ++y) {
            const float gy = (previewHeight > 1)
                ? (static_cast<float>(y) * (height - 1) / (previewHeight - 1))
                : 0.0f;
            const int y0 = std::clamp(static_cast<int>(gy), 0, height - 1);
            const int y1 = std::min(y0 + 1, height - 1);
            const float wy = gy - y0;
            auto* dst = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
            for (int x = 0; x < previewWidth; ++x) {
                const float gx = (previewWidth > 1)
                    ? (static_cast<float>(x) * (width - 1) / (previewWidth - 1))
                    : 0.0f;
                const int x0 = std::clamp(static_cast<int>(gx), 0, width - 1);
                const int x1 = std::min(x0 + 1, width - 1);
                const float wx = gx - x0;
                const auto idx00 = (static_cast<size_t>(y0) * width + x0) * 3;
                const auto idx01 = (static_cast<size_t>(y0) * width + x1) * 3;
                const auto idx10 = (static_cast<size_t>(y1) * width + x0) * 3;
                const auto idx11 = (static_cast<size_t>(y1) * width + x1) * 3;
                for (int ch = 0; ch < 3; ++ch) {
                    const float top = rgb[idx00 + ch] * (1.0f - wx) + rgb[idx01 + ch] * wx;
                    const float bot = rgb[idx10 + ch] * (1.0f - wx) + rgb[idx11 + ch] * wx;
                    const float val = top * (1.0f - wy) + bot * wy;
                    dst[x * 4 + ch] = static_cast<uint8_t>(std::clamp(static_cast<int>(val + 0.5f), 0, 255));
                }
                dst[x * 4 + 3] = 255;
            }
        }
        env->CallVoidMethod(ctx->previewCallback, ctx->onPreviewMethod, bitmap);
    }
    if (pixelsLocked) AndroidBitmap_unlockPixels(env, bitmap);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(bitmap);
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);
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

bool copyBitmapToMask(JNIEnv* env, jobject bitmap, std::vector<uint8_t>& outMask, int& width, int& height) {
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
    outMask.resize(static_cast<size_t>(width) * height);
    for (int y = 0; y < height; ++y) {
        const auto* srcRow = static_cast<const uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        auto* dstRow = outMask.data() + static_cast<size_t>(y) * width;
        for (int x = 0; x < width; ++x) {
            const uint8_t r = srcRow[x * 4 + 0];
            const uint8_t g = srcRow[x * 4 + 1];
            const uint8_t b = srcRow[x * 4 + 2];
            const uint8_t a = srcRow[x * 4 + 3];
            dstRow[x] = (a < 255) ? a : static_cast<uint8_t>((static_cast<int>(r) * 299 + static_cast<int>(g) * 587 + static_cast<int>(b) * 114) / 1000);
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
    gModelKind = 0;
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
        "libggml-htp-v73.so",
        "libggml-htp-v75.so",
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
        std::string destPath(dspDir);
        destPath += '/';
        destPath += fileName;
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
    return 8;
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeCancel(JNIEnv* /* env */, jobject /* thiz */) {
    gCancelled.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeLastError(JNIEnv* env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return env->NewStringUTF(gLastError.c_str());
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
    jstring backendSpecStr,
    jint kindInt,
    jint threads) {
    std::lock_guard<std::mutex> lock(gEngineMutex);

    unloadEngineLocked();
    gLastError.clear();
    gCancelled.store(false);
    {
        std::lock_guard<std::mutex> logLock(gEngineDiagnosticLogMutex);
        gEngineDiagnosticLog.clear();
    }

    const char* rawNativeLibDir = nativeLibDirStr ? env->GetStringUTFChars(nativeLibDirStr, nullptr) : nullptr;
    const char* rawDspDir = dspDirStr ? env->GetStringUTFChars(dspDirStr, nullptr) : nullptr;
    const char* rawBackendSpec = backendSpecStr ? env->GetStringUTFChars(backendSpecStr, nullptr) : nullptr;
    const std::string nativeLibDir = rawNativeLibDir ? rawNativeLibDir : "";
    const std::string dspDir = rawDspDir ? rawDspDir : "";
    const std::string backendSpec = rawBackendSpec ? rawBackendSpec : "";
    if (rawNativeLibDir) env->ReleaseStringUTFChars(nativeLibDirStr, rawNativeLibDir);
    if (rawDspDir) env->ReleaseStringUTFChars(dspDirStr, rawDspDir);
    if (rawBackendSpec) env->ReleaseStringUTFChars(backendSpecStr, rawBackendSpec);

    if (!dspDir.empty()) {
        ensureDspSkeletons(env, application, dspDir);
        std::string newPath = dspDir + ";" + nativeLibDir + ";/system/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/dsp";
        const char* currentAdsp = getenv("ADSP_LIBRARY_PATH");
        if (currentAdsp && currentAdsp[0] != '\0') {
            newPath += ';';
            newPath += currentAdsp;
        }
        setenv("ADSP_LIBRARY_PATH", newPath.c_str(), 1);
        LOGI("Configured ADSP_LIBRARY_PATH: %s", newPath.c_str());
    }

    std::string enginePath = nativeLibDir.empty() ? "libdit_engine.so" : (nativeLibDir + "/libdit_engine.so");
    gEngineHandle = dlopen(enginePath.c_str(), RTLD_NOW | RTLD_LOCAL);
    std::string enginePathError;
    if (!gEngineHandle) {
        const char* err = dlerror();
        enginePathError = err ? err : "unknown error";
        LOGE("Failed to dlopen %s: %s", enginePath.c_str(), enginePathError.c_str());
        gEngineHandle = dlopen("libdit_engine.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (!gEngineHandle) {
        const char* err = dlerror();
        const char* detail = err ? err : "unknown error";
        LOGE("Failed to dlopen libdit_engine.so by soname: %s", detail);
        gLastError = "Failed to dlopen libdit_engine.so; path=" + enginePath +
            " error=" + enginePathError + "; soname error=" + detail;
        return JNI_FALSE;
    }

    auto getApiFn = reinterpret_cast<dit_engine_get_api_fn>(dlsym(gEngineHandle, DIT_ENGINE_ENTRY_SYMBOL));
    if (!getApiFn) {
        LOGE("Entry symbol %s not found in engine library", DIT_ENGINE_ENTRY_SYMBOL);
        gLastError = "Engine entry symbol not found";
        unloadEngineLocked();
        return JNI_FALSE;
    }

    gEngineApi = getApiFn(DIT_ENGINE_ABI_VERSION);
    if (!gEngineApi || gEngineApi->abi_version != DIT_ENGINE_ABI_VERSION) {
        LOGE("Engine API ABI mismatch (requested %d)", DIT_ENGINE_ABI_VERSION);
        gLastError = "Engine API ABI mismatch";
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
        std::string linkTarget;
        {
            const std::string linkPath = modelDir + "/.link";
            struct stat linkStat{};
            if (stat(linkPath.c_str(), &linkStat) == 0 && S_ISREG(linkStat.st_mode)) {
                if (FILE* lf = fopen(linkPath.c_str(), "r")) {
                    char target[4096];
                    if (fgets(target, sizeof(target), lf)) {
                        linkTarget = target;
                        while (!linkTarget.empty() && (linkTarget.back() == '\n' || linkTarget.back() == '\r' || linkTarget.back() == ' ')) {
                            linkTarget.pop_back();
                        }
                    }
                    fclose(lf);
                }
            }
        }
        struct stat linkTargetStat{};
        if (!linkTarget.empty() && stat(linkTarget.c_str(), &linkTargetStat) == 0 && S_ISREG(linkTargetStat.st_mode)) {
            diffModelPath = linkTarget;
        } else {
            DIR* dir = opendir(modelDir.c_str());
            if (dir) {
                struct dirent* entry = nullptr;
                while ((entry = readdir(dir)) != nullptr) {
                    const std::string name(entry->d_name);
                    if (!hasWeightExtension(name) || isAuxWeight(name)) {
                        continue;
                    }
                    diffModelPath = modelDir;
                    diffModelPath += '/';
                    diffModelPath += name;
                    break;
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

    std::string kreaDonor;
    size_t modelSlash = modelDir.find_last_of('/');
    if (modelSlash != std::string::npos) {
        kreaDonor = modelDir.substr(0, modelSlash) + "/krea2_turbo";
    }

    std::string mossDir;
    if (!sharedDir.empty()) {
        size_t sharedSlash = sharedDir.find_last_of('/');
        if (sharedSlash != std::string::npos) {
            mossDir = sharedDir.substr(0, sharedSlash) + "/moss_components";
        }
    }

    std::string kreaDir;
    if (!sharedDir.empty()) {
        size_t sharedSlash = sharedDir.find_last_of('/');
        if (sharedSlash != std::string::npos) {
            kreaDir = sharedDir.substr(0, sharedSlash) + "/krea_components";
        }
    }

    std::string chromaDir;
    if (!sharedDir.empty()) {
        size_t sharedSlash = sharedDir.find_last_of('/');
        if (sharedSlash != std::string::npos) {
            chromaDir = sharedDir.substr(0, sharedSlash) + "/chroma_components";
        }
    }

    std::string animaDir;
    if (!sharedDir.empty()) {
        size_t sharedSlash = sharedDir.find_last_of('/');
        if (sharedSlash != std::string::npos) {
            animaDir = sharedDir.substr(0, sharedSlash) + "/anima_components";
        }
    }

    std::string llmPath;
    std::vector<std::string> llmCandidates;
    if (modelKind == kDitModelMossImage) {
        llmCandidates = {
            findModelEncoder(modelDir),
            mossDir + "/Qwen3.5-9B-Q4_0.gguf",
        };
        const std::string ownTokenizer = modelDir + "/tokenizer.json";
        const std::string sharedTokenizer = mossDir + "/tokenizer.json";
        if (access(ownTokenizer.c_str(), F_OK) == 0) {
            setenv("SD_QWEN35_TOKENIZER", ownTokenizer.c_str(), 1);
        } else if (access(sharedTokenizer.c_str(), F_OK) == 0) {
            setenv("SD_QWEN35_TOKENIZER", sharedTokenizer.c_str(), 1);
        }
    } else if (static_cast<int>(modelKind) == kDitModelFlux1) {
        llmCandidates = {};
    } else if (static_cast<int>(modelKind) == kDitModelKrea2) {
        llmCandidates = {
            modelDir + "/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf",
            kreaDir + "/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf",
            kreaDonor + "/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf",
        };
    } else if (static_cast<int>(modelKind) == kDitModelAnima) {
        llmCandidates = {
            findModelEncoder(modelDir),
            modelDir + "/qwen_3_06b_base.safetensors",
            animaDir + "/qwen_3_06b_base.safetensors",
        };
    } else if (modelKind == DIT_MODEL_QWEN_IMAGE_2_1) {
        llmCandidates = {
            modelDir + "/Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf",
            qwenDir + "/Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf",
            modelDir + "/llm.gguf",
            qwenDir + "/llm.gguf",
        };
    } else if (modelKind == DIT_MODEL_FLUX2_KLEIN && hasMarker(modelDir, "KLEIN9B")) {
        llmCandidates = {
            findModelEncoder(modelDir),
            qwenDir + "/Qwen_Qwen3-8B-Q4_0.gguf",
        };
    } else if (modelKind == DIT_MODEL_SD15 || modelKind == DIT_MODEL_SDXL) {
        llmCandidates = {};
    } else {
        llmCandidates = {
            findModelEncoder(modelDir),
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

    std::string t5Path;
    std::string clipPath;
    if (static_cast<int>(modelKind) == kDitModelFlux1) {
        const std::vector<std::string> t5Candidates = {
            findModelEncoder(modelDir),
            chromaDir + "/t5-v1_1-xxl-encoder-Q4_0.gguf",
        };
        for (const auto& candidate : t5Candidates) {
            if (!candidate.empty() && access(candidate.c_str(), F_OK) == 0) {
                t5Path = candidate;
                break;
            }
        }
        const std::vector<std::string> clipCandidates = {
            modelDir + "/clip_l.safetensors",
            chromaDir + "/clip_l.safetensors",
        };
        for (const auto& candidate : clipCandidates) {
            if (!candidate.empty() && access(candidate.c_str(), F_OK) == 0) {
                clipPath = candidate;
                break;
            }
        }
    }

    const char* primaryVae = (modelKind == DIT_MODEL_Z_IMAGE) ? "ae.safetensors" : "vae.safetensors";
    const char* secondaryVae = (modelKind == DIT_MODEL_Z_IMAGE) ? "vae.safetensors" : "ae.safetensors";
    if (static_cast<int>(modelKind) == kDitModelFlux1) {
        primaryVae = "ae.safetensors";
        secondaryVae = "vae.safetensors";
    }

    std::string vaePath;
    std::vector<std::string> vaeCandidates;
    if (modelKind == DIT_MODEL_QWEN_IMAGE_2_1) {
        vaeCandidates = {
            modelDir + "/qwen_image_2.1_vae_bf16.safetensors",
            qwenDir + "/qwen_image_2.1_vae_bf16.safetensors",
            modelDir + "/vae.safetensors",
            qwenDir + "/vae.safetensors",
        };
    } else if (static_cast<int>(modelKind) == kDitModelKrea2) {
        vaeCandidates = {
            modelDir + "/qwen_image_vae.safetensors",
            kreaDir + "/qwen_image_vae.safetensors",
            modelDir + "/qwen_image-vae.safetensors",
            kreaDir + "/qwen_image-vae.safetensors",
            modelDir + "/wan_2.1_vae.safetensors",
            kreaDir + "/wan_2.1_vae.safetensors",
            kreaDonor + "/wan_2.1_vae.safetensors",
        };
    } else if (modelKind == kDitModelMossImage) {
        vaeCandidates = {
            modelDir + "/flux2-vae.safetensors",
            mossDir + "/flux2-vae.safetensors",
            modelDir + "/vae.safetensors",
            sharedDir + "/vae.safetensors",
        };
    } else if (static_cast<int>(modelKind) == kDitModelAnima) {
        vaeCandidates = {
            modelDir + "/qwen_image_vae.safetensors",
            animaDir + "/qwen_image_vae.safetensors",
            sharedDir + "/vae.safetensors",
        };
    } else if (static_cast<int>(modelKind) == kDitModelFlux1) {
        vaeCandidates = {
            modelDir + "/flux1_ae.safetensors",
            chromaDir + "/flux1_ae.safetensors",
            modelDir + "/ae.safetensors",
            chromaDir + "/ae.safetensors",
            sharedDir + "/ae.safetensors",
            modelDir + "/vae.safetensors",
            sharedDir + "/vae.safetensors",
        };
    } else if (modelKind == DIT_MODEL_SD15 || modelKind == DIT_MODEL_SDXL) {
        vaeCandidates = {
            modelDir + "/vae.safetensors",
            modelDir + "/vae.pt",
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
        gLastError = "DiT diffusion model not found in " + modelDir;
        unloadEngineLocked();
        return JNI_FALSE;
    }
    const bool isMonolithic = (modelKind == DIT_MODEL_SD15 || modelKind == DIT_MODEL_SDXL);
    const bool fluxNoLlm = (static_cast<int>(modelKind) == kDitModelFlux1);
    if (!isMonolithic && (llmPath.empty() || access(llmPath.c_str(), F_OK) != 0)) {
        if (!fluxNoLlm) {
            LOGE("DiT text encoder not found (checked %s and %s)", modelDir.c_str(), sharedDir.c_str());
            gLastError = "DiT text encoder not found in " + modelDir;
            unloadEngineLocked();
            return JNI_FALSE;
        }
        llmPath.clear();
    }
    if (!isMonolithic && fluxNoLlm && (t5Path.empty() || access(t5Path.c_str(), F_OK) != 0)) {
        LOGE("DiT T5 encoder not found (checked %s)", modelDir.c_str());
        gLastError = "DiT T5 encoder not found in " + modelDir;
        unloadEngineLocked();
        return JNI_FALSE;
    }
    if (!isMonolithic && (vaePath.empty() || access(vaePath.c_str(), F_OK) != 0)) {
        LOGE("DiT VAE not found (checked %s and %s)", modelDir.c_str(), sharedDir.c_str());
        gLastError = "DiT VAE not found in " + modelDir;
        unloadEngineLocked();
        return JNI_FALSE;
    }

    std::string taesdPath;
    std::vector<std::string> taesdCandidates;
    const bool isWanFamily = (modelKind == DIT_MODEL_QWEN_IMAGE_2_1 ||
                              static_cast<int>(modelKind) == kDitModelKrea2 ||
                              static_cast<int>(modelKind) == kDitModelAnima);
    const bool isFlux1Family = (static_cast<int>(modelKind) == kDitModelFlux1 ||
                                modelKind == DIT_MODEL_Z_IMAGE);
    const bool isFlux2Family = (static_cast<int>(modelKind) == DIT_MODEL_FLUX2_KLEIN ||
                                modelKind == kDitModelMossImage);

    if (isWanFamily) {
        taesdCandidates = {
            modelDir + "/taew2_1.safetensors",
            sharedDir + "/taew2_1.safetensors",
            qwenDir + "/taew2_1.safetensors",
            kreaDir + "/taew2_1.safetensors",
            animaDir + "/taew2_1.safetensors",
            modelDir + "/taeqwen.safetensors",
            sharedDir + "/taeqwen.safetensors",
            qwenDir + "/taeqwen.safetensors",
        };
    } else if (isFlux1Family) {
        taesdCandidates = {
            modelDir + "/taef1.safetensors",
            chromaDir + "/taef1.safetensors",
            sharedDir + "/taef1.safetensors",
            modelDir + "/taesd.safetensors",
            sharedDir + "/taesd.safetensors",
        };
    } else if (isFlux2Family) {
        taesdCandidates = {
            modelDir + "/taef2.safetensors",
            mossDir + "/taef2.safetensors",
            sharedDir + "/taef2.safetensors",
            modelDir + "/taesd.safetensors",
            sharedDir + "/taesd.safetensors",
        };
    } else if (modelKind == DIT_MODEL_SDXL) {
        taesdCandidates = {
            modelDir + "/taesdxl.safetensors",
            sharedDir + "/taesdxl.safetensors",
            modelDir + "/taesdxl.gguf",
            sharedDir + "/taesdxl.gguf",
            modelDir + "/taesd.safetensors",
            sharedDir + "/taesd.safetensors",
        };
    } else if (modelKind == DIT_MODEL_SD15) {
        taesdCandidates = {
            modelDir + "/taesd.safetensors",
            sharedDir + "/taesd.safetensors",
            modelDir + "/taesd.gguf",
            sharedDir + "/taesd.gguf",
        };
    } else {
        taesdCandidates = {
            modelDir + "/taesd.safetensors",
            modelDir + "/taesd.gguf",
            sharedDir + "/taesd.safetensors",
            sharedDir + "/taesd.gguf",
        };
    }
    for (const auto& candidate : taesdCandidates) {
        if (!candidate.empty() && access(candidate.c_str(), F_OK) == 0) {
            taesdPath = candidate;
            break;
        }
    }

    std::string visionPath;
    if (!isMonolithic) {
        const std::vector<std::string> visionCandidates = {
            findVisionModel(modelDir),
            modelDir + "/mmproj-Qwen_Qwen3-VL-8B-Instruct-f16.gguf",
            qwenDir + "/mmproj-Qwen_Qwen3-VL-8B-Instruct-f16.gguf",
            sharedDir + "/mmproj-Qwen_Qwen3-VL-8B-Instruct-f16.gguf",
            findVisionModel(qwenDir),
            modelDir + "/mmproj.gguf",
            qwenDir + "/mmproj.gguf",
            sharedDir + "/mmproj.gguf",
        };
        for (const auto& candidate : visionCandidates) {
            if (!candidate.empty() && access(candidate.c_str(), F_OK) == 0) {
                visionPath = candidate;
                break;
            }
        }
    }

    LOGI("Loading DiT kind=%d diff=%s llm=%s vision=%s t5=%s vae=%s taesd=%s", kindInt, diffModelPath.c_str(), llmPath.c_str(), visionPath.c_str(), t5Path.c_str(), vaePath.c_str(), taesdPath.c_str());

    dit_ctx_params params{};
    params.kind = modelKind;
    if (isMonolithic) {
        params.model_path = diffModelPath.c_str();
        params.diffusion_model_path = nullptr;
    } else {
        params.model_path = nullptr;
        params.diffusion_model_path = diffModelPath.c_str();
    }
    params.llm_path = llmPath.empty() ? nullptr : llmPath.c_str();
    params.llm_vision_path = visionPath.empty() ? nullptr : visionPath.c_str();
    params.t5xxl_path = t5Path.empty() ? nullptr : t5Path.c_str();
    params.clip_l_path = clipPath.empty() ? nullptr : clipPath.c_str();
    params.clip_g_path = nullptr;
    params.vae_path = vaePath.empty() ? nullptr : vaePath.c_str();
    params.taesd_path = taesdPath.empty() ? nullptr : taesdPath.c_str();
    std::string selectedDevice = backendSpec;
    const auto assignment = selectedDevice.find('=');
    if (assignment != std::string::npos) {
        const auto comma = selectedDevice.find(',', assignment);
        selectedDevice = selectedDevice.substr(assignment + 1, comma == std::string::npos ? std::string::npos : comma - assignment - 1);
    }
    const bool isUnet = (modelKind == DIT_MODEL_SD15 || modelKind == DIT_MODEL_SDXL);
    if (isUnet && (selectedDevice == "HTP0" || selectedDevice == "htp0" || selectedDevice == "HTP")) {
        LOGW("SD1.5 / SDXL UNet contains strided convolutions incompatible with Hexagon HMX repacking. Redirecting to GPUOpenCL.");
        selectedDevice = "GPUOpenCL";
    }
    if (selectedDevice.empty()) {
        if (!isUnet && gEngineApi->has_device && gEngineApi->has_device("HTP0")) {
            selectedDevice = "HTP0";
            LOGI("Auto selected Hexagon because HTP0 is available");
        } else if (gEngineApi->has_device && gEngineApi->has_device("GPUOpenCL")) {
            selectedDevice = "GPUOpenCL";
            LOGI("Auto selected GPUOpenCL because OpenCL is available");
        } else {
            selectedDevice = "CPU";
            LOGI("Auto selected CPU");
        }
    }
    const bool openclRequested = selectedDevice == "GPUOpenCL";
    const char* noHmx = getenv("GGML_HEXAGON_NHMX");
    std::string pinnedBackend;
    std::string autoFitBackend;
    std::string paramsBackend;
    if (selectedDevice == "CPU" || selectedDevice == "cpu") {
        pinnedBackend = "CPU";
        paramsBackend = "all=disk";
    } else if (selectedDevice == "HTP0") {
        pinnedBackend = noHmx && noHmx[0] == '0'
            ? "diffusion=HTP0,te=HTP0,vae=CPU"
            : "diffusion=HTP0,te=HTP0,vae=HTP0";
        paramsBackend = "all=disk";
    } else if (selectedDevice == "GPUOpenCL") {
        pinnedBackend = "GPUOpenCL";
        paramsBackend = "all=disk";
    } else if (!selectedDevice.empty()) {
        pinnedBackend = selectedDevice;
        paramsBackend = "all=disk";
    }
    params.backend = pinnedBackend.empty() ? nullptr : pinnedBackend.c_str();
    params.params_backend = paramsBackend.empty() ? nullptr : paramsBackend.c_str();
    params.auto_fit_backend = autoFitBackend.empty() ? nullptr : autoFitBackend.c_str();
    params.auto_fit_max_ram_gib = 0.f;
    const char* maxVram = getenv("DIT_MAX_VRAM_GIB");
    params.max_vram_gib = !openclRequested && maxVram && maxVram[0] ? maxVram : nullptr;
    params.n_threads = threads > 0 ? threads : 4;
    params.flash_attn = true;
    params.vae_conv_direct = true;
    if (!autoFitBackend.empty()) {
        LOGI("DiT auto-fit preferred device: %s", autoFitBackend.c_str());
    } else if (!pinnedBackend.empty()) {
        LOGI("Using DiT backend: %s", pinnedBackend.c_str());
    } else {
        LOGI("DiT auto-fit across available GPUs");
    }

    gDitCtx = gEngineApi->create(&params);
    if (!gDitCtx) {
        const char* err = gEngineApi->last_error(nullptr);
        LOGE("Failed to create DiT context: %s", err ? err : "unknown error");
        gLastError = std::string("Failed to create DiT context: ") + (err ? err : "unknown error");
        std::string engineLog;
        {
            std::lock_guard<std::mutex> logLock(gEngineDiagnosticLogMutex);
            engineLog = gEngineDiagnosticLog;
        }
        if (!engineLog.empty()) {
            gLastError += " | engine log: ";
            gLastError += engineLog;
        }
        unloadEngineLocked();
        return JNI_FALSE;
    }

    LOGI("DiT context successfully created for kind=%d", kindInt);
    gModelKind = kindInt;
    return JNI_TRUE;
}

const char* ditSamplerName(const std::string& sampler) {
    if (sampler == "EULER") return "euler";
    if (sampler == "EULER_A") return "euler_a";
    if (sampler == "HEUN") return "heun";
    if (sampler == "DPM2") return "dpm2";
    if (sampler == "DPMPP_2S_A") return "dpm++2s_a";
    if (sampler == "DPMPP_2M") return "dpm++2m";
    if (sampler == "DPMPP_2M_SDE") return "dpm++2m_sde";
    if (sampler == "DDIM") return "ddim_trailing";
    if (sampler == "LCM") return "lcm";
    if (sampler == "LMS") return "lms";
    if (sampler == "ER_SDE") return "er_sde";
    return nullptr;
}

const char* ditSchedulerName(const std::string& scheduler) {
    if (scheduler == "KARRAS") return "karras";
    if (scheduler == "EXPONENTIAL") return "exponential";
    if (scheduler == "SGM_UNIFORM") return "sgm_uniform";
    if (scheduler == "SIMPLE") return "simple";
    if (scheduler == "BETA") return "beta";
    return nullptr;
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
    jfloat guidance,
    jlong seed,
    jobject sourceBitmap,
    jobject maskBitmap,
    jobjectArray referenceBitmaps,
    jfloat denoiseStrength,
    jobject outputBitmap,
    jobject progressCallback,
    jobject previewCallback,
    jstring samplerStr,
    jstring schedulerStr,
    jstring cacheModeStr,
    jstring cacheOptionStr,
    jint clipSkip,
    jobjectArray loraPaths,
    jfloatArray loraStrengths) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gCancelled.store(false);
    {
        std::lock_guard<std::mutex> logLock(gEngineDiagnosticLogMutex);
        gEngineDiagnosticLog.clear();
    }

    if (!gDitCtx || !gEngineApi) {
        LOGE("nativeGenerate called without valid DiT context");
        gLastError = "Generate called without valid DiT context";
        return JNI_FALSE;
    }

    const char* prompt = promptStr ? env->GetStringUTFChars(promptStr, nullptr) : "";
    const char* negativePrompt = negativePromptStr ? env->GetStringUTFChars(negativePromptStr, nullptr) : "";
    const char* samplerChars = samplerStr ? env->GetStringUTFChars(samplerStr, nullptr) : nullptr;
    const char* schedulerChars = schedulerStr ? env->GetStringUTFChars(schedulerStr, nullptr) : nullptr;
    const char* cacheModeChars = cacheModeStr ? env->GetStringUTFChars(cacheModeStr, nullptr) : nullptr;
    const char* cacheOptionChars = cacheOptionStr ? env->GetStringUTFChars(cacheOptionStr, nullptr) : nullptr;

    std::vector<uint8_t> initRgb;
    int initW = 0;
    int initH = 0;
    if (sourceBitmap) {
        copyBitmapToRgb(env, sourceBitmap, initRgb, initW, initH);
    }

    std::vector<uint8_t> maskData;
    int maskW = 0;
    int maskH = 0;
    if (maskBitmap) {
        copyBitmapToMask(env, maskBitmap, maskData, maskW, maskH);
    }

    std::vector<std::vector<uint8_t>> refRgbBuffers;
    std::vector<const uint8_t*> refRgbPointers;
    std::vector<int> refWidths;
    std::vector<int> refHeights;

    if (referenceBitmaps) {
        jsize refCount = env->GetArrayLength(referenceBitmaps);
        if (refCount > 0) {
            refRgbBuffers.resize(refCount);
            refRgbPointers.resize(refCount);
            refWidths.resize(refCount);
            refHeights.resize(refCount);
            int validCount = 0;
            for (jsize i = 0; i < refCount; ++i) {
                jobject refBm = env->GetObjectArrayElement(referenceBitmaps, i);
                if (refBm) {
                    int rw = 0, rh = 0;
                    if (copyBitmapToRgb(env, refBm, refRgbBuffers[validCount], rw, rh)) {
                        refRgbPointers[validCount] = refRgbBuffers[validCount].data();
                        refWidths[validCount] = rw;
                        refHeights[validCount] = rh;
                        validCount++;
                    }
                    env->DeleteLocalRef(refBm);
                }
            }
            if (validCount > 0) {
                refRgbBuffers.resize(validCount);
                refRgbPointers.resize(validCount);
                refWidths.resize(validCount);
                refHeights.resize(validCount);
            } else {
                refRgbBuffers.clear();
                refRgbPointers.clear();
                refWidths.clear();
                refHeights.clear();
            }
        }
    }

    dit_gen_params params{};
    params.prompt = prompt;
    params.negative_prompt = negativePrompt;
    params.clip_skip = clipSkip;
    params.width = width;
    params.height = height;
    params.steps = steps;
    params.cfg_scale = cfgScale;
    params.guidance = (guidance > 0.0f) ? guidance : distilledGuidanceForKind(gModelKind);
    params.seed = seed;
    params.sample_method = samplerChars ? ditSamplerName(samplerChars) : nullptr;
    params.scheduler = schedulerChars ? ditSchedulerName(schedulerChars) : nullptr;
    params.init_image_rgb = initRgb.empty() ? nullptr : initRgb.data();
    params.init_width = initW;
    params.init_height = initH;
    params.denoise_strength = denoiseStrength;
    if (!maskData.empty()) {
        params.mask_image = maskData.data();
        params.mask_width = maskW;
        params.mask_height = maskH;
    }
    if (!refRgbPointers.empty()) {
        params.reference_images_rgb = refRgbPointers.data();
        params.reference_widths = refWidths.data();
        params.reference_heights = refHeights.data();
        params.reference_image_count = static_cast<int>(refRgbPointers.size());
    }
    params.cache_mode = cacheModeChars;
    params.cache_option = cacheOptionChars;

    std::vector<std::string> loraPathStrs;
    std::vector<dit_lora_spec> loraSpecs;
    if (loraPaths) {
        jsize count = env->GetArrayLength(loraPaths);
        jfloat* strengths = loraStrengths ? env->GetFloatArrayElements(loraStrengths, nullptr) : nullptr;
        jsize strengthCount = loraStrengths ? env->GetArrayLength(loraStrengths) : 0;
        loraPathStrs.reserve(count);
        loraSpecs.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            auto loraStr = reinterpret_cast<jstring>(env->GetObjectArrayElement(loraPaths, i));
            if (loraStr) {
                const char* c = env->GetStringUTFChars(loraStr, nullptr);
                if (c && c[0] != '\0') {
                    loraPathStrs.push_back(c);
                    float str = (strengths && i < strengthCount) ? strengths[i] : 1.0f;
                    loraSpecs.push_back({loraPathStrs.back().c_str(), str});
                }
                if (c) env->ReleaseStringUTFChars(loraStr, c);
                env->DeleteLocalRef(loraStr);
            }
        }
        if (strengths) env->ReleaseFloatArrayElements(loraStrengths, strengths, JNI_ABORT);
    }
    if (!loraSpecs.empty()) {
        params.loras = loraSpecs.data();
        params.lora_count = static_cast<int>(loraSpecs.size());
    }

    const long long pixelCount = (long long)width * (long long)height;
    if (pixelCount > 786432LL) {
        params.vae_tile_size = 64;
        params.vae_tile_overlap = 0.125f;
    } else {
        params.vae_tile_size = 0;
        params.vae_tile_overlap = 0.0f;
    }

    ProgressBridgeContext progressContext{env, progressCallback, nullptr, steps, previewCallback, nullptr};
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
    if (previewCallback) {
        jclass cbClass = env->GetObjectClass(previewCallback);
        if (cbClass) {
            progressContext.onPreviewMethod = env->GetMethodID(cbClass, "onPreview", "(Landroid/graphics/Bitmap;)V");
            env->DeleteLocalRef(cbClass);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }
    const bool previewsEnabled = previewCallback && progressContext.onPreviewMethod;
    gEngineApi->set_preview_interval(gDitCtx, previewsEnabled ? 1 : 0);

    uint8_t* outRgb = nullptr;
    int outWidth = 0;
    int outHeight = 0;
    int outChannels = 3;

    const bool ok = gEngineApi->generate(
        gDitCtx,
        &params,
        progressCallback ? &progressBridge : nullptr,
        previewsEnabled ? &previewBridge : nullptr,
        &progressContext,
        &outRgb,
        &outWidth,
        &outHeight,
        &outChannels);

    if (promptStr) env->ReleaseStringUTFChars(promptStr, prompt);
    if (negativePromptStr) env->ReleaseStringUTFChars(negativePromptStr, negativePrompt);
    if (samplerStr) env->ReleaseStringUTFChars(samplerStr, samplerChars);
    if (schedulerStr) env->ReleaseStringUTFChars(schedulerStr, schedulerChars);
    if (cacheModeStr) env->ReleaseStringUTFChars(cacheModeStr, cacheModeChars);
    if (cacheOptionStr) env->ReleaseStringUTFChars(cacheOptionStr, cacheOptionChars);

    if (!ok || !outRgb) {
        const char* err = gEngineApi->last_error(gDitCtx);
        std::string detail = err ? err : (gCancelled.load() ? "cancelled" : "unknown");
        std::string engineLog;
        {
            std::lock_guard<std::mutex> logLock(gEngineDiagnosticLogMutex);
            engineLog = gEngineDiagnosticLog;
        }
        if (!engineLog.empty()) {
            detail += " | engine: ";
            detail += engineLog;
        }
        LOGE("DiT generation failed: %s", detail.c_str());
        gLastError = std::string("DiT generation failed: ") + detail;
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

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_DitDiffusion_nativeConvert(
    JNIEnv* env,
    jclass /* clazz */,
    jstring inputPathStr,
    jstring vaePathStr,
    jstring outputPathStr,
    jint outputType) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    const char* inputPath = inputPathStr ? env->GetStringUTFChars(inputPathStr, nullptr) : nullptr;
    const char* vaePath = vaePathStr ? env->GetStringUTFChars(vaePathStr, nullptr) : nullptr;
    const char* outputPath = outputPathStr ? env->GetStringUTFChars(outputPathStr, nullptr) : nullptr;

    bool cleanupHandle = false;
    void* handle = gEngineHandle;
    const dit_engine_api* api = gEngineApi;
    if (!api) {
        handle = dlopen("libdit_engine.so", RTLD_NOW | RTLD_LOCAL);
        if (handle) {
            auto getApiFn = reinterpret_cast<dit_engine_get_api_fn>(dlsym(handle, DIT_ENGINE_ENTRY_SYMBOL));
            if (getApiFn) {
                api = getApiFn(DIT_ENGINE_ABI_VERSION);
            }
            cleanupHandle = true;
        }
    }

    bool success = false;
    if (api && api->convert_model && inputPath && outputPath) {
        LOGI("Starting model conversion: %s -> %s (type=%d)", inputPath, outputPath, outputType);
        success = api->convert_model(inputPath, vaePath, outputPath, outputType);
        LOGI("Model conversion finished: %d", success ? 1 : 0);
    } else {
        LOGE("Cannot convert model: api=%p convert_model=%p input=%p output=%p",
             api, api ? reinterpret_cast<void*>(api->convert_model) : nullptr, inputPath, outputPath);
    }

    if (cleanupHandle && handle) {
        dlclose(handle);
    }
    if (inputPath) env->ReleaseStringUTFChars(inputPathStr, inputPath);
    if (vaePath) env->ReleaseStringUTFChars(vaePathStr, vaePath);
    if (outputPath) env->ReleaseStringUTFChars(outputPathStr, outputPath);
    return success ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
