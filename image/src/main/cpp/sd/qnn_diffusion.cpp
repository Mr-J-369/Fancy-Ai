#include "clip_tokenizer.h"
#include "native_diffusion_pipeline.h"
#include "qnn_model.h"
#include "sdxl_model.h"

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define TAG "fancyqnn"
#if FANCY_INTEGRITY_REQUIRED
#define LOGE(...) ((void)0)
#else
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

namespace {

constexpr uint32_t kAbiVersion = 2u;

struct ImageBuffer {
    uint8_t* data;
    size_t size;
    int width;
    int height;
    int stride;
};

using ProgressCallback = void (*)(void* context, int percent);

struct DiffusionRequest {
    const char* prompt;
    const char* negative_prompt;
    int width;
    int height;
    int steps;
    float guidance;
    int64_t seed;
    float strength;
    int sampler;
    int schedule;
    bool v_prediction;
    const uint8_t* source_rgba;
    int source_width;
    int source_height;
    int source_stride;
    ProgressCallback progress;
    void* progress_context;
};


using aura::QnnGraphRunner;
using aura::SdModel;
using aura::SdxlModel;

SdModel* sd15 = nullptr;
SdxlModel* sdxl = nullptr;
std::mutex lifecycleMutex;
std::atomic_bool cancelled{false};
std::string cachedPositive;
std::string cachedNegative;
bool cachedGuided = false;
size_t cachedSdxlChunks = 1;
std::vector<float> conditional;
std::vector<float> unconditional;

void clearConditioning() {
    cachedPositive.clear();
    cachedNegative.clear();
    cachedGuided = false;
    cachedSdxlChunks = 1;
    conditional.clear();
    unconditional.clear();
}

ImageBuffer imageBuffer(std::vector<uint8_t> rgba, int width, int height) {
    if (rgba.size() != static_cast<size_t>(width) * height * 4) return {nullptr, 0, 0, 0, 0};
    auto* data = static_cast<uint8_t*>(std::malloc(rgba.size()));
    if (!data) return {nullptr, 0, 0, 0, 0};
    std::memcpy(data, rgba.data(), rgba.size());
    return {data, rgba.size(), width, height, width * 4};
}

void unloadLocked() {
    const bool hadModel = sd15 != nullptr || sdxl != nullptr;
    delete sd15;
    sd15 = nullptr;
    delete sdxl;
    sdxl = nullptr;
    clearConditioning();
    QnnGraphRunner::releasePerfVote();
    if (hadModel) {
#if !FANCY_INTEGRITY_REQUIRED
        __android_log_print(
            ANDROID_LOG_INFO,
            "fancyqnn",
            "QNN diffusion: all model contexts unloaded");
#endif
    }
}

uint32_t abiVersion() {
    return kAbiVersion;
}

int32_t countTokens(const char* tokenizerJson, const char* text) {
    if (!tokenizerJson || !text) return 0;
    aura::ClipTokenizer tokenizer;
    if (!tokenizer.load(tokenizerJson)) return 0;
    return static_cast<int32_t>(tokenizer.countTokens(text));
}

bool loadSd15(
    const char* directory,
    const char* libraryDirectory,
    const char* skeletonDirectory,
    int width,
    int height) {
    if (!directory || !libraryDirectory || !skeletonDirectory) return false;
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    unloadLocked();
    sd15 = new SdModel();
    if (!sd15->load(directory, libraryDirectory, skeletonDirectory, width, height)) { unloadLocked(); return false; }
    return true;
}

bool loadSdxl(
    const char* directory,
    const char* libraryDirectory,
    const char* skeletonDirectory) {
    if (!directory || !libraryDirectory || !skeletonDirectory) return false;
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    unloadLocked();
    sdxl = new SdxlModel();
    if (!sdxl->load(directory, libraryDirectory, skeletonDirectory)) { unloadLocked(); return false; }
    return true;
}

void unloadModels() {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    unloadLocked();
}

ImageBuffer generateSd15(const DiffusionRequest* request) {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    if (!sd15 || !request || !request->prompt || request->steps <= 0) return {nullptr, 0, 0, 0, 0};
    cancelled.store(false, std::memory_order_release);
    const bool guided = request->guidance != 1.0f;
    const std::string positive = request->prompt;
    const std::string negative = request->negative_prompt ? request->negative_prompt : "";
    if (positive != cachedPositive || negative != cachedNegative || guided != cachedGuided || conditional.empty()) {
        conditional = sd15->encodeText(positive);
        unconditional = guided ? sd15->encodeText(negative) : std::vector<float>{};
        if (conditional.empty() || (guided && unconditional.size() != conditional.size())) {
            clearConditioning();
            return {nullptr, 0, 0, 0, 0};
        }
        cachedPositive = positive;
        cachedNegative = negative;
        cachedGuided = guided;
    }
    aura::diffusion::Request nativeRequest{
        .width = request->width,
        .height = request->height,
        .steps = request->steps,
        .seed = request->seed,
        .strength = request->strength,
        .sampler = static_cast<aura::diffusion::Sampler>(request->sampler),
        .schedule = static_cast<aura::diffusion::Schedule>(request->schedule),
        .vPrediction = request->v_prediction,
        .sourceRgba = request->source_rgba,
        .sourceWidth = request->source_width,
        .sourceHeight = request->source_height,
        .sourceStride = request->source_stride,
    };
    auto rgba = aura::diffusion::generate(
        nativeRequest,
        0.18215f,
        [&](const uint8_t* pixels, int width, int height, int stride) {
            return aura::diffusion::encodeTiled(
                pixels, width, height, stride, request->width, request->height,
                [&](const std::vector<float>& tile) { return sd15->vaeEncode(tile); });
        },
        [&](const std::vector<float>& latent, int timestep) {
            auto cond = sd15->unet(latent, timestep, conditional);
            if (!guided) return cond;
            const auto uncond = sd15->unet(latent, timestep, unconditional);
            if (cond.size() != uncond.size()) return std::vector<float>{};
            std::vector<float> result(cond.size());
            for (size_t i = 0; i < result.size(); ++i) {
                result[i] = uncond[i] + request->guidance * (cond[i] - uncond[i]);
            }
            return result;
        },
        [&](const std::vector<float>& latent) {
            return aura::diffusion::decodeTiled(
                latent, request->width, request->height,
                [&](const std::vector<float>& tile) { return sd15->vaeDecode(tile); });
        },
        [&](int percent) {
            if (request->progress) request->progress(request->progress_context, percent);
        },
        [&] { return cancelled.load(std::memory_order_acquire); });
    return imageBuffer(std::move(rgba), request->width, request->height);
}

ImageBuffer generateSdxl(const DiffusionRequest* request) {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    if (!sdxl || !request || !request->prompt || request->steps <= 0) return {nullptr, 0, 0, 0, 0};
    cancelled.store(false, std::memory_order_release);
    const bool guided = request->guidance != 1.0f;
    const std::string positive = request->prompt;
    const std::string negative = request->negative_prompt ? request->negative_prompt : "";
    const size_t positiveChunks = sdxl->textChunkCount(positive);
    const size_t negativeChunks = guided ? sdxl->textChunkCount(negative) : 0;
    const size_t requestedChunks = std::max(positiveChunks, negativeChunks);
    const size_t chunks = sdxl->supportedChunkCount(requestedChunks);
    if (positive != cachedPositive || negative != cachedNegative || guided != cachedGuided ||
        chunks != cachedSdxlChunks || conditional.empty()) {
        conditional = sdxl->encodeText(positive, chunks);
        unconditional = guided ? sdxl->encodeText(negative, chunks) : std::vector<float>{};
        const size_t expected = chunks * static_cast<size_t>(SdxlModel::CTX) + SdxlModel::POOL;
        if (conditional.size() != expected ||
            (guided && unconditional.size() != conditional.size())) {
            clearConditioning();
            return {nullptr, 0, 0, 0, 0};
        }
        cachedPositive = positive;
        cachedNegative = negative;
        cachedGuided = guided;
        cachedSdxlChunks = chunks;
    }
    const size_t contextSize = chunks * static_cast<size_t>(SdxlModel::CTX);
    const auto contextEnd = conditional.begin() + static_cast<std::ptrdiff_t>(contextSize);
    const std::vector<float> context(conditional.begin(), contextEnd);
    const std::vector<float> pool(contextEnd, conditional.end());
    const std::vector<float> uncondContext = guided
        ? std::vector<float>(unconditional.begin(), unconditional.begin() + static_cast<std::ptrdiff_t>(contextSize))
        : std::vector<float>{};
    const std::vector<float> uncondPool = guided
        ? std::vector<float>(unconditional.begin() + static_cast<std::ptrdiff_t>(contextSize), unconditional.end())
        : std::vector<float>{};
    const std::vector<float> timeIds{
        static_cast<float>(request->height), static_cast<float>(request->width), 0.0f, 0.0f,
        static_cast<float>(request->height), static_cast<float>(request->width),
    };
    aura::diffusion::Request nativeRequest{
        .width = request->width,
        .height = request->height,
        .steps = request->steps,
        .seed = request->seed,
        .strength = request->strength,
        .sampler = static_cast<aura::diffusion::Sampler>(request->sampler),
        .schedule = static_cast<aura::diffusion::Schedule>(request->schedule),
        .vPrediction = request->v_prediction,
        .sourceRgba = request->source_rgba,
        .sourceWidth = request->source_width,
        .sourceHeight = request->source_height,
        .sourceStride = request->source_stride,
    };
    auto rgba = aura::diffusion::generate(
        nativeRequest,
        0.13025f,
        [&](const uint8_t* pixels, int width, int height, int stride) {
            return sdxl->vaeEncode(aura::diffusion::imageToChw(
                pixels, width, height, stride, request->width, request->height));
        },
        [&](const std::vector<float>& latent, int timestep) {
            auto cond = sdxl->unet(latent, timestep, context, pool, timeIds, std::min(positiveChunks, chunks));
            if (!guided) return cond;
            const auto uncond = sdxl->unet(latent, timestep, uncondContext, uncondPool, timeIds, std::min(negativeChunks, chunks));
            if (cond.size() != uncond.size()) return std::vector<float>{};
            std::vector<float> result(cond.size());
            for (size_t i = 0; i < result.size(); ++i) {
                result[i] = uncond[i] + request->guidance * (cond[i] - uncond[i]);
            }
            return result;
        },
        [&](const std::vector<float>& latent) { return sdxl->vaeDecode(latent); },
        [&](int percent) {
            if (request->progress) request->progress(request->progress_context, percent);
        },
        [&] { return cancelled.load(std::memory_order_acquire); });
    return imageBuffer(std::move(rgba), request->width, request->height);
}

void cancelGeneration() {
    cancelled.store(true, std::memory_order_release);
}

bool wasCancelled() {
    return cancelled.load(std::memory_order_acquire);
}

void freeImage(ImageBuffer value) {
    std::free(value.data);
}

std::string fromJString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

struct SourcePixels {
    std::vector<uint8_t> rgba;
    int width = 0;
    int height = 0;
    int stride = 0;
};

bool copyBitmap(JNIEnv* env, jobject bitmap, SourcePixels& result) {
    if (!bitmap) return true;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("QNN diffusion source must be RGBA_8888");
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        LOGE("QNN diffusion could not lock source bitmap");
        return false;
    }
    result.width = static_cast<int>(info.width);
    result.height = static_cast<int>(info.height);
    result.stride = result.width * 4;
    result.rgba.resize(static_cast<size_t>(result.stride) * result.height);
    for (int y = 0; y < result.height; ++y) {
        std::memcpy(
            result.rgba.data() + static_cast<size_t>(y) * result.stride,
            static_cast<const uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride,
            static_cast<size_t>(result.stride));
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

bool writeBitmap(JNIEnv* env, jobject bitmap, const ImageBuffer& image) {
    AndroidBitmapInfo info{};
    if (!bitmap || !image.data ||
        AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int>(info.width) != image.width || static_cast<int>(info.height) != image.height) {
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return false;
    for (int y = 0; y < image.height; ++y) {
        std::memcpy(
            static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride,
            image.data + static_cast<size_t>(y) * image.stride,
            static_cast<size_t>(image.width) * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

struct ProgressContext {
    JNIEnv* env;
    jobject callback;
    jmethodID method;
};

void reportProgress(void* opaque, int percent) {
    auto* context = static_cast<ProgressContext*>(opaque);
    if (!context || !context->callback || !context->method) return;
    context->env->CallVoidMethod(context->callback, context->method, static_cast<jint>(percent));
}

jboolean generate(
    JNIEnv* env,
    bool useSdxl,
    jstring prompt,
    jstring negativePrompt,
    jint width,
    jint height,
    jint steps,
    jfloat guidance,
    jlong seed,
    jobject source,
    jfloat strength,
    jint sampler,
    jint schedule,
    jboolean vPrediction,
    jobject output,
    jobject progress) {
    const auto positive = fromJString(env, prompt);
    const auto negative = fromJString(env, negativePrompt);
    SourcePixels sourcePixels;
    if (!copyBitmap(env, source, sourcePixels)) return JNI_FALSE;
    jmethodID progressMethod = nullptr;
    if (progress) {
        jclass progressClass = env->FindClass("com/mrj/fancyai/sd/NativeImageProgress");
        if (!progressClass) return JNI_FALSE;
        progressMethod = env->GetMethodID(progressClass, "onProgress", "(I)V");
        env->DeleteLocalRef(progressClass);
        if (!progressMethod) return JNI_FALSE;
    }
    ProgressContext progressContext{env, progress, progressMethod};
    DiffusionRequest request{
        .prompt = positive.c_str(),
        .negative_prompt = negative.c_str(),
        .width = width,
        .height = height,
        .steps = steps,
        .guidance = guidance,
        .seed = seed,
        .strength = strength,
        .sampler = sampler,
        .schedule = schedule,
        .v_prediction = vPrediction == JNI_TRUE,
        .source_rgba = sourcePixels.rgba.empty() ? nullptr : sourcePixels.rgba.data(),
        .source_width = sourcePixels.width,
        .source_height = sourcePixels.height,
        .source_stride = sourcePixels.stride,
        .progress = progress ? reportProgress : nullptr,
        .progress_context = progress ? &progressContext : nullptr,
    };
    auto image = useSdxl
        ? generateSdxl(&request)
        : generateSd15(&request);
    const bool written = writeBitmap(env, output, image);
    freeImage(image);
    return written ? JNI_TRUE : JNI_FALSE;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeAbiVersion(JNIEnv*, jclass) {
    return static_cast<jint>(abiVersion());
}

JNIEXPORT jint JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeCountTokens(
    JNIEnv* env, jclass, jstring jsonPath, jstring text) {
    const auto path = fromJString(env, jsonPath);
    const auto prompt = fromJString(env, text);
    return static_cast<jint>(countTokens(path.c_str(), prompt.c_str()));
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeLoad(
    JNIEnv* env,
    jclass,
    jobject /* application */,
    jstring directory,
    jstring libraryDirectory,
    jstring skeletonDirectory,
    jint width,
    jint height) {
    const auto path = fromJString(env, directory);
    const auto libraries = fromJString(env, libraryDirectory);
    const auto skeletons = fromJString(env, skeletonDirectory);
    const bool loaded = loadSd15(
        path.c_str(), libraries.c_str(), skeletons.c_str(), width, height);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeLoadSdxl(
    JNIEnv* env,
    jclass,
    jobject /* application */,
    jstring directory,
    jstring libraryDirectory,
    jstring skeletonDirectory) {
    const auto path = fromJString(env, directory);
    const auto libraries = fromJString(env, libraryDirectory);
    const auto skeletons = fromJString(env, skeletonDirectory);
    const bool loaded = loadSdxl(path.c_str(), libraries.c_str(), skeletons.c_str());
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeUnload(JNIEnv*, jclass) {
    unloadModels();
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeCancel(JNIEnv*, jclass) {
    cancelGeneration();
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeWasCancelled(JNIEnv*, jclass) {
    return wasCancelled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeGenerateSd15(
    JNIEnv* env,
    jclass,
    jstring prompt,
    jstring negativePrompt,
    jint width,
    jint height,
    jint steps,
    jfloat guidance,
    jlong seed,
    jobject source,
    jfloat strength,
    jint sampler,
    jint schedule,
    jboolean vPrediction,
    jobject output,
    jobject progress) {
    return generate(
        env, false, prompt, negativePrompt, width, height, steps, guidance, seed, source,
        strength, sampler, schedule, vPrediction, output, progress);
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_QnnDiffusion_nativeGenerateSdxl(
    JNIEnv* env,
    jclass,
    jstring prompt,
    jstring negativePrompt,
    jint width,
    jint height,
    jint steps,
    jfloat guidance,
    jlong seed,
    jobject source,
    jfloat strength,
    jint sampler,
    jint schedule,
    jboolean vPrediction,
    jobject output,
    jobject progress) {
    return generate(
        env, true, prompt, negativePrompt, width, height, steps, guidance, seed, source,
        strength, sampler, schedule, vPrediction, output, progress);
}

}  // extern "C"
