#if FANCY_MNN_SDXL
#include "mnn_sdxl_model.h"
#else
#include "mnn_sd15_model.h"
#endif
#include "native_diffusion_pipeline.h"

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#if FANCY_MNN_SDXL
#define TAG "fancysdXLmnn"
#else
#define TAG "fancysdmnn"
#endif
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


std::mutex gMutex;
#if FANCY_MNN_SDXL
std::unique_ptr<aura::MnnSdxlModel> gModel;
#else
std::unique_ptr<aura::MnnSd15Model> gModel;
#endif
int gBackend = 0;
int gMemoryPolicy = 0;
std::atomic_bool gCancelled{false};
std::string gPositive;
std::string gNegative;
bool gGuided = false;
#if FANCY_MNN_SDXL
aura::SdxlConditioning gConditional;
aura::SdxlConditioning gUnconditional;
#else
std::vector<float> gConditional;
std::vector<float> gUnconditional;
#endif

void clearConditioning() {
    gPositive.clear();
    gNegative.clear();
    gGuided = false;
#if FANCY_MNN_SDXL
    gConditional = {};
    gUnconditional = {};
#else
    gConditional.clear();
    gUnconditional.clear();
#endif
}

uint32_t abiVersion() {
    return kAbiVersion;
}

bool loadModel(const char* directory) {
    std::lock_guard<std::mutex> lock(gMutex);
    gModel.reset();
    clearConditioning();
    if (!directory || directory[0] == '\0') return false;
#if FANCY_MNN_SDXL
    auto model = std::make_unique<aura::MnnSdxlModel>();
#else
    auto model = std::make_unique<aura::MnnSd15Model>();
#endif
    model->setBackend(gBackend);
    model->setMemoryPolicy(gMemoryPolicy);
    if (!model->load(directory)) return false;
    gModel = std::move(model);
    return true;
}

void setBackend(int backend) {
    std::lock_guard<std::mutex> lock(gMutex);
    gBackend = std::clamp(backend, 0, 3);
    if (gModel) gModel->setBackend(gBackend);
}

void setMemoryPolicy(int policy) {
    std::lock_guard<std::mutex> lock(gMutex);
    gMemoryPolicy = std::clamp(policy, 0, 2);
    if (gModel) gModel->setMemoryPolicy(gMemoryPolicy);
}

void finishRequest() {
    std::lock_guard<std::mutex> lock(gMutex);
    clearConditioning();
    if (gModel) gModel->finishRequest();
}

void unloadModel() {
    std::lock_guard<std::mutex> lock(gMutex);
    clearConditioning();
    gModel.reset();
}

ImageBuffer generateImage(const DiffusionRequest* request) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gModel || !request || !request->prompt || request->steps <= 0) return {nullptr, 0, 0, 0, 0};
    gCancelled.store(false, std::memory_order_release);
    const bool guided = request->guidance != 1.0f;
    const std::string positive = request->prompt;
    const std::string negative = request->negative_prompt ? request->negative_prompt : "";
#if FANCY_MNN_SDXL
    if (positive != gPositive || negative != gNegative || guided != gGuided || gConditional.context.empty()) {
#else
    if (positive != gPositive || negative != gNegative || guided != gGuided || gConditional.empty()) {
#endif
        const size_t chunks = guided
            ? std::max(gModel->textChunkCount(positive), gModel->textChunkCount(negative))
            : gModel->textChunkCount(positive);
        gConditional = gModel->encodeText(positive, std::max<size_t>(1, chunks));
#if FANCY_MNN_SDXL
        gUnconditional = guided
            ? gModel->encodeText(negative, std::max<size_t>(1, chunks))
            : aura::SdxlConditioning{};
        if (gConditional.context.empty() || gConditional.pooled.empty() ||
            (guided && (gUnconditional.context.size() != gConditional.context.size() ||
                        gUnconditional.pooled.size() != gConditional.pooled.size()))) {
#else
        gUnconditional = guided
            ? gModel->encodeText(negative, std::max<size_t>(1, chunks))
            : std::vector<float>{};
        if (gConditional.empty() || (guided && gUnconditional.size() != gConditional.size())) {
#endif
            clearConditioning();
            return {nullptr, 0, 0, 0, 0};
        }
        gPositive = positive;
        gNegative = negative;
        gGuided = guided;
    }
    aura::diffusion::Request nativeRequest{
#if FANCY_MNN_SDXL
        .width = 1024,
        .height = 1024,
#else
        .width = 512,
        .height = 512,
#endif
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
#if !FANCY_MNN_SDXL
        .localDreamSd15 = true,
#endif
    };
    auto rgba = aura::diffusion::generate(
        nativeRequest,
#if FANCY_MNN_SDXL
        0.13025f,
#else
        0.18215f,
#endif
        [&](const uint8_t* pixels, int width, int height, int stride) {
            return gModel->vaeEncodeRgba(pixels, width, height, stride);
        },
        [&](const std::vector<float>& latent, int timestep) {
            return guided
                ? gModel->unetGuided(latent, timestep, gConditional, gUnconditional, request->guidance)
                : gModel->unet(latent, timestep, gConditional);
        },
        [&](const std::vector<float>& latent) { return gModel->vaeDecode(latent); },
        [&](int percent) {
            if (request->progress) request->progress(request->progress_context, percent);
        },
        [&] { return gCancelled.load(std::memory_order_acquire); });
    if (rgba.empty()) return {nullptr, 0, 0, 0, 0};
    auto* data = static_cast<uint8_t*>(std::malloc(rgba.size()));
    if (!data) return {nullptr, 0, 0, 0, 0};
    std::memcpy(data, rgba.data(), rgba.size());
#if FANCY_MNN_SDXL
    return {data, rgba.size(), 1024, 1024, 1024 * 4};
#else
    return {data, rgba.size(), 512, 512, 512 * 4};
#endif
}

void cancelGeneration() {
    gCancelled.store(true, std::memory_order_release);
}

bool wasCancelled() {
    return gCancelled.load(std::memory_order_acquire);
}

void freeImage(ImageBuffer buffer) {
    std::free(buffer.data);
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
#if FANCY_MNN_SDXL
        LOGE("MNN SDXL source must be RGBA_8888");
#else
        LOGE("MNN diffusion source must be RGBA_8888");
#endif
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
#if FANCY_MNN_SDXL
        LOGE("MNN SDXL could not lock source bitmap");
#else
        LOGE("MNN diffusion could not lock source bitmap");
#endif
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
    if (context && context->callback && context->method) {
        context->env->CallVoidMethod(context->callback, context->method, static_cast<jint>(percent));
    }
}

}  // namespace

extern "C" {

#if FANCY_MNN_SDXL
#define FANCY_JNI_METHOD(name) Java_com_mrj_fancyai_sd_MnnSdxlDiffusion_##name
#else
#define FANCY_JNI_METHOD(name) Java_com_mrj_fancyai_sd_MnnSd15Diffusion_##name
#endif

JNIEXPORT jint JNICALL
FANCY_JNI_METHOD(nativeAbiVersion)(JNIEnv*, jobject) {
    return static_cast<jint>(abiVersion());
}

JNIEXPORT jboolean JNICALL
FANCY_JNI_METHOD(nativeLoad)(
    JNIEnv* env, jobject, jobject /* application */, jstring directory) {
    const auto path = fromJString(env, directory);
    return loadModel(path.c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
FANCY_JNI_METHOD(nativeSetBackend)(JNIEnv*, jobject, jint backend) {
    setBackend(static_cast<int>(backend));
}

JNIEXPORT void JNICALL
FANCY_JNI_METHOD(nativeSetMemoryPolicy)(JNIEnv*, jobject, jint policy) {
    setMemoryPolicy(static_cast<int>(policy));
}

JNIEXPORT void JNICALL
FANCY_JNI_METHOD(nativeFinishRequest)(JNIEnv*, jobject) {
    finishRequest();
}

JNIEXPORT void JNICALL
FANCY_JNI_METHOD(nativeUnload)(JNIEnv*, jobject) {
    unloadModel();
}

JNIEXPORT void JNICALL
FANCY_JNI_METHOD(nativeCancel)(JNIEnv*, jobject) {
    cancelGeneration();
}

JNIEXPORT jboolean JNICALL
FANCY_JNI_METHOD(nativeWasCancelled)(JNIEnv*, jobject) {
    return wasCancelled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
FANCY_JNI_METHOD(nativeGenerate)(
    JNIEnv* env,
    jobject,
    jstring prompt,
    jstring negativePrompt,
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
    auto image = generateImage(&request);
    const bool written = writeBitmap(env, output, image);
    freeImage(image);
    return written ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
