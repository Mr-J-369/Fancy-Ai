#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <MNN/Interpreter.hpp>
#include <MNN/ImageProcess.hpp>
#include <MNN/Matrix.h>
#include <MNN/Tensor.hpp>
#include <MNN/MNNForwardType.h>

#define TAG "fancysdup"
#if FANCY_INTEGRITY_REQUIRED
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

using namespace MNN;

namespace {
constexpr uint32_t kAbiVersion = 2u;
constexpr int kScale = 4;

const char* backendName(MNNForwardType t) {
    return t == MNN_FORWARD_OPENCL ? "OpenCL" : "CPU";
}

MNNForwardType pickGpuBackend() {
    ScheduleConfig cfg; cfg.type = MNN_FORWARD_OPENCL;
    auto rt = Interpreter::createRuntime({cfg});
    if (rt.first.find(MNN_FORWARD_OPENCL) != rt.first.end()) { LOGI("GPU probe: OpenCL"); return MNN_FORWARD_OPENCL; }
    LOGI("GPU probe: CPU"); return MNN_FORWARD_CPU;
}

struct Up {
    std::shared_ptr<Interpreter> interp;
    Session* session = nullptr;
    int side = 0;
    std::string cacheBase;
    std::unique_ptr<MNN::CV::ImageProcess> imageProcess;
    std::unique_ptr<Tensor> outputHost;

    bool create(const std::string& path, MNNForwardType type, int tileSide) {
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) { LOGE("open failed: %s", path.c_str()); return false; }
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); return false; }
        auto sz = static_cast<size_t>(st.st_size);
        void* map = mmap(nullptr, sz, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (map == MAP_FAILED) { LOGE("mmap failed"); return false; }
        madvise(map, sz, MADV_SEQUENTIAL);
        interp.reset(Interpreter::createFromBuffer(map, sz));
        munmap(map, sz);
        if (!interp) { LOGE("createFromBuffer failed"); return false; }
        interp->setExternalFile((path + ".weight").c_str());

        ScheduleConfig cfg; BackendConfig bk;
        const bool isGpu = (type != MNN_FORWARD_CPU);
        cfg.type = type;
        if (isGpu) {
            cacheBase = path + ".mnnc";
            interp->setCacheFile(cacheBase.c_str());
            if (type == MNN_FORWARD_OPENCL) cfg.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
            bk.precision = BackendConfig::Precision_Low;
            bk.memory = BackendConfig::Memory_Low;
        } else {
            cfg.numThread = std::min(6, (int)sysconf(_SC_NPROCESSORS_ONLN));
        }
        bk.power = BackendConfig::Power_High;
        cfg.backendConfig = &bk;

        session = interp->createSession(cfg);
        if (!session) { LOGE("createSession failed"); return false; }
        side = tileSide;
        Tensor* in = interp->getSessionInput(session, nullptr);
        if (!in) { LOGE("no input tensor"); return false; }
        interp->resizeTensor(in, {1, 3, side, side});
        interp->resizeSession(session);
        MNN::CV::ImageProcess::Config imageConfig;
        // Kotlin IntArray stores ARGB words; on Android arm64 their byte order is BGRA.
        imageConfig.sourceFormat = MNN::CV::BGRA;
        imageConfig.destFormat = MNN::CV::RGB;
        imageConfig.filterType = MNN::CV::BILINEAR;
        imageConfig.wrap = MNN::CV::CLAMP_TO_EDGE;
        for (int channel = 0; channel < 3; ++channel) imageConfig.normal[channel] = 1.0f / 255.0f;
        imageProcess.reset(MNN::CV::ImageProcess::create(imageConfig, in));
        const int outSide = side * kScale;
        outputHost.reset(Tensor::create<float>(
            {1, 3, outSide, outSide}, nullptr, Tensor::CAFFE));
        if (!imageProcess || !outputHost || !outputHost->host<float>()) {
            LOGE("could not allocate reusable ESRGAN image I/O");
            return false;
        }
        if (isGpu) interp->updateCacheFile(session);
        interp->releaseModel();
        return true;
    }
    void reset() {
        outputHost.reset();
        imageProcess.reset();
        if (interp && session) interp->releaseSession(session);
        session = nullptr;
        interp.reset();
    }
    ~Up() { reset(); }
};

std::unique_ptr<Up> g;
std::mutex gMutex;

std::vector<int> cover(int size, int tile) {
    if (size <= tile) return {0};
    std::vector<int> origins;
    for (int origin = 0;; origin += tile) {
        const int next = origin + tile >= size ? size - tile : origin;
        if (origins.empty() || origins.back() != next) origins.push_back(next);
        if (next + tile >= size) break;
    }
    return origins;
}

jintArray emptyIntArray(JNIEnv* env) {
    return env->NewIntArray(0);
}

uint32_t abiVersion() {
    return kAbiVersion;
}

bool loadModel(const char* modelPath, int backend, int tileSide) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!modelPath || modelPath[0] == '\0' || tileSide <= 0) return false;
    g = std::make_unique<Up>();
    MNNForwardType type = backend == 1 ? MNN_FORWARD_CPU : pickGpuBackend();
    if (!g->create(modelPath, type, tileSide)) {
        if (type != MNN_FORWARD_OPENCL) {
            g.reset();
            return false;
        }
        LOGI("OpenCL upscaler session unavailable, falling back to CPU");
        g = std::make_unique<Up>();
        type = MNN_FORWARD_CPU;
        if (!g->create(modelPath, type, tileSide)) {
            g.reset();
            return false;
        }
    }
    LOGI("ESRGAN loaded (%s, tile=%d)", backendName(type), tileSide);
    return true;
}

size_t upscale(
    const uint32_t* source,
    size_t sourceCount,
    int width,
    int height,
    int coreTile,
    int pad,
    uint32_t* result,
    size_t resultCapacity) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!g || !g->interp || !g->session || !g->imageProcess || !source || !result ||
        width <= 0 || height <= 0 || coreTile <= 0 || pad < 0 ||
        coreTile + 2 * pad != g->side ||
        sourceCount < static_cast<size_t>(width) * height) return 0;
    const int outWidth = width * kScale;
    const int outHeight = height * kScale;
    const int64_t outputCount = static_cast<int64_t>(outWidth) * outHeight;
    if (outputCount <= 0 || outputCount > INT32_MAX ||
        resultCapacity < static_cast<size_t>(outputCount)) return 0;
    Tensor* input = g->interp->getSessionInput(g->session, nullptr);
    Tensor* output = g->interp->getSessionOutput(g->session, nullptr);
    if (!input || !output) return 0;

    const int side = g->side;
    const int outSide = side * kScale;
    const int outPlane = outSide * outSide;
    const auto xs = cover(width, std::min(static_cast<int>(coreTile), static_cast<int>(width)));
    const auto ys = cover(height, std::min(static_cast<int>(coreTile), static_cast<int>(height)));
    const auto started = std::chrono::steady_clock::now();
    int completedTiles = 0;
    bool failed = false;
    for (const int oy : ys) {
        for (const int ox : xs) {
            // Map tile-tensor coordinates directly into the padded source-image region.
            MNN::CV::Matrix transform;
            transform.setTranslate(static_cast<float>(ox - pad), static_cast<float>(oy - pad));
            g->imageProcess->setMatrix(transform);
            const auto converted = g->imageProcess->convert(
                reinterpret_cast<const uint8_t*>(source),
                width,
                height,
                width * static_cast<int>(sizeof(uint32_t)),
                input);
            if (converted != MNN::NO_ERROR ||
                g->interp->runSession(g->session) != MNN::NO_ERROR ||
                !output->copyToHostTensor(g->outputHost.get())) {
                LOGE("native ESRGAN tile failed at (%d,%d), ImageProcess=%d", ox, oy,
                     static_cast<int>(converted));
                failed = true;
                break;
            }
            const float* tile = g->outputHost->host<float>();
            const int coreWidth = std::min(static_cast<int>(coreTile), static_cast<int>(width) - ox);
            const int coreHeight = std::min(static_cast<int>(coreTile), static_cast<int>(height) - oy);
            for (int ty = 0; ty < coreHeight * kScale; ++ty) {
                for (int tx = 0; tx < coreWidth * kScale; ++tx) {
                    const int tileX = pad * kScale + tx;
                    const int tileY = pad * kScale + ty;
                    const int tileIndex = tileY * outSide + tileX;
                    const int red = std::clamp(
                        static_cast<int>(tile[tileIndex] * 255.0f), 0, 255);
                    const int green = std::clamp(
                        static_cast<int>(tile[outPlane + tileIndex] * 255.0f), 0, 255);
                    const int blue = std::clamp(
                        static_cast<int>(tile[2 * outPlane + tileIndex] * 255.0f), 0, 255);
                    const int outX = ox * kScale + tx;
                    const int outY = oy * kScale + ty;
                    result[static_cast<size_t>(outY) * outWidth + outX] =
                        0xFF000000u | (red << 16) | (green << 8) | blue;
                }
            }
            ++completedTiles;
        }
        if (failed) break;
    }
    if (failed) return 0;
    const double elapsedMs = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - started).count();
    LOGI(
        "ESRGAN native image I/O: %dx%d -> %dx%d, tiles=%d, %.2fms; "
        "one ABI input/output buffer, no per-tile managed FloatArray",
        width, height, outWidth, outHeight, completedTiles, elapsedMs);
    return static_cast<size_t>(outputCount);
}

int scale() {
    return kScale;
}

void unloadModel() {
    std::lock_guard<std::mutex> lock(gMutex);
    g.reset();
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_mrj_fancyai_sd_MnnUpscaler_nativeAbiVersion(JNIEnv*, jclass) {
    return static_cast<jint>(abiVersion());
}

JNIEXPORT jboolean JNICALL
Java_com_mrj_fancyai_sd_MnnUpscaler_nativeLoad(
    JNIEnv* env, jclass, jobject /* application */, jstring path, jint backend, jint tileSide) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    const bool loaded = loadModel(
        chars ? chars : "", static_cast<int>(backend), static_cast<int>(tileSide));
    if (chars) env->ReleaseStringUTFChars(path, chars);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_mrj_fancyai_sd_MnnUpscaler_nativeUpscaleImage(
    JNIEnv* env,
    jclass,
    jintArray argb,
    jint width,
    jint height,
    jint coreTile,
    jint pad) {
    if (!argb || width <= 0 || height <= 0) return emptyIntArray(env);
    const auto sourceCount = static_cast<size_t>(env->GetArrayLength(argb));
    const size_t outputCount = static_cast<size_t>(width) * height * kScale * kScale;
    auto* source = env->GetIntArrayElements(argb, nullptr);
    if (!source) return emptyIntArray(env);
    std::vector<uint32_t> output(outputCount);
    const size_t written = upscale(
        reinterpret_cast<const uint32_t*>(source), sourceCount,
        static_cast<int>(width), static_cast<int>(height),
        static_cast<int>(coreTile), static_cast<int>(pad), output.data(), output.size());
    env->ReleaseIntArrayElements(argb, source, JNI_ABORT);
    if (written == 0 || written > static_cast<size_t>(INT32_MAX)) return emptyIntArray(env);
    auto result = env->NewIntArray(static_cast<jsize>(written));
    if (result) {
        env->SetIntArrayRegion(
            result, 0, static_cast<jsize>(written), reinterpret_cast<const jint*>(output.data()));
    }
    return result ? result : emptyIntArray(env);
}

JNIEXPORT jint JNICALL
Java_com_mrj_fancyai_sd_MnnUpscaler_nativeScale(JNIEnv*, jclass) {
    return static_cast<jint>(scale());
}

JNIEXPORT void JNICALL
Java_com_mrj_fancyai_sd_MnnUpscaler_nativeUnload(JNIEnv*, jclass) {
    unloadModel();
}

}
