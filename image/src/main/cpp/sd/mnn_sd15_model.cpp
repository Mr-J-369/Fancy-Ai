#include "mnn_sd15_model.h"

#include "clip_tokenizer.h"
#include "mnn_clip.h"

#include <MNN/Interpreter.hpp>
#include <MNN/ImageProcess.hpp>
#include <MNN/MNNForwardType.h>
#include <MNN/Matrix.h>
#include <MNN/Tensor.hpp>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <cstring>
#include <memory>
#include <filesystem>
#include <cstdlib>
#include <utility>

#define TAG "fancysdmnn"
#if FANCY_INTEGRITY_REQUIRED
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

namespace aura {
namespace {

constexpr int kTextBatch = 1;
constexpr int kTokens = 77;
constexpr int kTextDim = 768;
constexpr int kLatentChannels = 4;
constexpr int kLatentSide = 64;
constexpr int kImageChannels = 3;
constexpr int kImageSide = 512;
constexpr int kContextElements = kTokens * kTextDim;
constexpr int kLatentElements = kLatentChannels * kLatentSide * kLatentSide;
constexpr int kImageElements = kImageChannels * kImageSide * kImageSide;
constexpr float kVaeScale = 0.18215f;

bool openClAvailable() {
    static const bool available = [] {
        MNN::ScheduleConfig probe;
        probe.type = MNN_FORWARD_OPENCL;
        const auto runtimes = MNN::Interpreter::createRuntime({probe});
        return runtimes.first.find(MNN_FORWARD_OPENCL) != runtimes.first.end();
    }();
    return available;
}

struct Stage {
    std::shared_ptr<MNN::Interpreter> net;
    MNN::Session* session = nullptr;
    std::string cachePath;
    std::string memoryPath;
    MNNForwardType backend = MNN_FORWARD_CPU;
    bool modelReleased = false;

    void reset() {
        if (net && session) net->releaseSession(session);
        session = nullptr;
        net.reset();
        if (!memoryPath.empty()) {
            std::error_code error;
            std::filesystem::remove_all(memoryPath, error);
            if (error) LOGE("mmap cleanup: %s", error.message().c_str());
            memoryPath.clear();
        }
        cachePath.clear();
        backend = MNN_FORWARD_CPU;
        modelReleased = false;
    }

    ~Stage() { reset(); }

    bool open(
        const std::string& path,
        int selectedBackend,
        MNN::BackendConfig::MemoryMode memoryMode,
        bool keepModelForResize = false) {
        reset();
        const auto wanted = selectedBackend != 1 && openClAvailable() ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
        if (openWithBackend(path, wanted, memoryMode, keepModelForResize)) return true;
        if (wanted == MNN_FORWARD_OPENCL) {
            LOGI("%s: OpenCL session unavailable, falling back to CPU", path.c_str());
            reset();
            return openWithBackend(path, MNN_FORWARD_CPU, memoryMode, keepModelForResize);
        }
        return false;
    }

    void releaseModelData() {
        if (!net || modelReleased) return;
        net->releaseModel();
        modelReleased = true;
    }

    bool resizeInput(const char* name, const std::vector<int>& shape, const char* stage) {
        if (!net || !session || modelReleased) return false;
        auto* input = net->getSessionInput(session, name);
        if (!input) {
            LOGE("%s input '%s' is missing", stage, name);
            return false;
        }
        net->resizeTensor(input, shape);
        net->resizeSession(session);
        input = net->getSessionInput(session, name);
        if (!input || input->shape() != shape) {
            LOGE("%s input '%s' resize failed", stage, name);
            return false;
        }
        if (backend == MNN_FORWARD_OPENCL) net->updateCacheFile(session);
        releaseModelData();
        return true;
    }

    bool openWithBackend(
        const std::string& path,
        MNNForwardType type,
        MNN::BackendConfig::MemoryMode memoryMode,
        bool keepModelForResize) {
        net.reset(MNN::Interpreter::createFromFile(path.c_str()));
        if (!net) {
            LOGE("createFromFile failed: %s", path.c_str());
            return false;
        }
        net->setExternalFile((path + ".weight").c_str());
        if (memoryMode == MNN::BackendConfig::Memory_Low) {
            std::string directory = path + ".aura-memory-XXXXXX";
            const char* created = mkdtemp(directory.data());
            if (!created) {
                LOGE("Could not create MNN backing directory for %s", path.c_str());
                return false;
            }
            memoryPath = created;
            net->setExternalFile(memoryPath.c_str(), MNN::Interpreter::EXTERNAL_FEATUREMAP_DIR);
            net->setExternalFile(memoryPath.c_str(), MNN::Interpreter::EXTERNAL_WEIGHT_DIR);
            LOGI("CPU weights/features use file backing: %s", memoryPath.c_str());
        }

        MNN::ScheduleConfig config;
        MNN::BackendConfig backendConfig;
        config.type = type;
        config.backupType = MNN_FORWARD_CPU;
        backendConfig.power = MNN::BackendConfig::Power_High;
        backendConfig.memory = memoryMode;
        config.backendConfig = &backendConfig;
        if (type == MNN_FORWARD_OPENCL) {
            config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
            backendConfig.precision = MNN::BackendConfig::Precision_Low;
            cachePath = path + ".mnnc.512";
            net->setCacheFile(cachePath.c_str());
        } else if (type == MNN_FORWARD_CPU) {
            config.numThread = std::max(1, std::min(6, static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN))));
        }
        session = net->createSession(config);
        if (!session) {
            LOGE("createSession failed: %s", path.c_str());
            return false;
        }
        backend = type;
        if (type == MNN_FORWARD_OPENCL) net->updateCacheFile(session);
        if (!keepModelForResize) releaseModelData();
        const char* memoryName = memoryMode == MNN::BackendConfig::Memory_Low ? "low-memory" :
            memoryMode == MNN::BackendConfig::Memory_High ? "speed-first" : "balanced";
        LOGI("loaded %s (%s, %s)", path.c_str(),
             type == MNN_FORWARD_OPENCL ? "OpenCL low precision" : "CPU", memoryName);
        return true;
    }
};

template <typename T>
bool copyInput(MNN::Tensor* destination, const std::vector<T>& values, const std::vector<int>& shape) {
    if (!destination || destination->elementSize() != static_cast<int>(values.size())) return false;
    std::unique_ptr<MNN::Tensor> host(MNN::Tensor::create<T>(shape, nullptr, MNN::Tensor::CAFFE));
    if (!host || !host->host<T>()) return false;
    std::memcpy(host->host<T>(), values.data(), values.size() * sizeof(T));
    return destination->copyFromHostTensor(host.get());
}

bool copyOutput(MNN::Tensor* source, std::vector<float>& values, const std::vector<int>& shape, const char* stage) {
    if (!source || source->elementSize() != static_cast<int>(values.size())) return false;
    std::unique_ptr<MNN::Tensor> host(MNN::Tensor::create<float>(shape, nullptr, MNN::Tensor::CAFFE));
    if (!host || !host->host<float>() || !source->copyToHostTensor(host.get())) return false;
    std::memcpy(values.data(), host->host<float>(), values.size() * sizeof(float));
#if !FANCY_INTEGRITY_REQUIRED
    // Observe raw graph values before scaling or pixel conversion; never alter the output.
    float minimum = std::numeric_limits<float>::infinity();
    float maximum = -std::numeric_limits<float>::infinity();
    double sum = 0.0;
    size_t nonfinite = 0;
    for (float value : values) {
        if (!std::isfinite(value)) {
            ++nonfinite;
            continue;
        }
        minimum = std::min(minimum, value);
        maximum = std::max(maximum, value);
        sum += value;
    }
    LOGI("%s readback: count=%zu finite=%zu min=%g max=%g sum=%.9g",
         stage, values.size(), values.size() - nonfinite, minimum, maximum, sum);
#endif
    return true;
}

bool run(MNN::Interpreter& net, MNN::Session* session, const char* stage) {
    const auto code = net.runSession(session);
    if (code == MNN::NO_ERROR) return true;
    LOGE("%s runSession failed: %d", stage, static_cast<int>(code));
    return false;
}

}  // namespace

struct MnnSd15Model::Impl {
    std::string directory;
    ClipTokenizer tokenizer;
    MnnClip text;
    Stage unet;
    Stage vaeEncoder;
    Stage vae;
    std::unique_ptr<MNN::CV::ImageProcess> vaeImageProcess;
    int unetContextTokens = 0;
    int backend = 0;
    int memoryPolicy = 0;

    [[nodiscard]] MNN::BackendConfig::MemoryMode backendMemory() const {
        if (memoryPolicy == 2) return MNN::BackendConfig::Memory_High;
        if (memoryPolicy == 1) return MNN::BackendConfig::Memory_Normal;
        return MNN::BackendConfig::Memory_Low;
    }

    void resetVaeEncoder() {
        vaeImageProcess.reset();
        vaeEncoder.reset();
    }

    void unloadStages() {
        text.free();
        unet.reset();
        resetVaeEncoder();
        vae.reset();
        unetContextTokens = 0;
    }

    bool ensureText() {
        if (text.ready()) return true;
        if (memoryPolicy != 2) {
            unet.reset();
            resetVaeEncoder();
            vae.reset();
        }
        return text.load(directory);
    }

    bool ensureUnet() {
        if (unet.session) return true;
        if (memoryPolicy == 0) text.free();
        if (memoryPolicy != 2) {
            resetVaeEncoder();
            vae.reset();
        }
        unetContextTokens = 0;
        // The exported sequence axis is dynamic. MNN needs the serialized graph data for the
        // first resizeSession(), so releaseModel() must wait until the prompt length is finalized.
        return unet.open(directory + "/unet.mnn", backend, backendMemory(), true);
    }

    bool ensureVae() {
        if (vae.session) return true;
        if (memoryPolicy == 0) text.free();
        if (memoryPolicy != 2) {
            unet.reset();
            resetVaeEncoder();
        }
        if (!vae.open(
                directory + "/vae_decoder.mnn",
                backend,
                backendMemory(),
                true)) {
            return false;
        }
        if (vae.resizeInput(
                "latent_sample",
                {1, kLatentChannels, kLatentSide, kLatentSide},
                "converted VAE decoder")) {
            return true;
        }
        vae.reset();
        return false;
    }

    bool ensureVaeEncoder() {
        if (vaeEncoder.session) return true;
        if (memoryPolicy == 0) text.free();
        if (memoryPolicy != 2) {
            unet.reset();
            vae.reset();
        }
        if (!vaeEncoder.open(
                directory + "/vae_encoder.mnn",
                backend,
                backendMemory(),
                true)) {
            return false;
        }
        if (vaeEncoder.resizeInput(
                "input",
                {1, kImageChannels, kImageSide, kImageSide},
                "converted VAE encoder")) {
            return true;
        }
        resetVaeEncoder();
        return false;
    }
};

MnnSd15Model::MnnSd15Model() : impl_(std::make_unique<Impl>()) {}
MnnSd15Model::~MnnSd15Model() = default;

bool MnnSd15Model::load(const std::string& directory) {
    impl_->unloadStages();
    impl_->directory = directory;
    if (!impl_->tokenizer.load(directory + "/tokenizer.json")) {
        LOGE("tokenizer load failed: %s", directory.c_str());
        return false;
    }
    LOGI("MNN SD 1.5 package: Local Dream graph contract");
    // Open the first graph now so model-load failures are reported before generation starts.
    return impl_->ensureText();
}

void MnnSd15Model::setBackend(int backend) {
    const int normalized = std::max(0, std::min(2, backend));
    if (impl_->backend == normalized) return;
    impl_->unloadStages();
    impl_->backend = normalized;
    LOGI("MNN SD 1.5 processor set to %s",
         normalized == 1 ? "CPU" :
         normalized == 2 ? "OpenCL with CPU fallback" : "Auto");
}

void MnnSd15Model::setMemoryPolicy(int policy) {
    const int normalized = std::max(0, std::min(2, policy));
    if (impl_->memoryPolicy == normalized) return;
    impl_->unloadStages();
    impl_->memoryPolicy = normalized;
    LOGI("MNN SD 1.5 memory policy set to %s",
         normalized == 0 ? "low-memory" : normalized == 1 ? "balanced" : "speed-first");
}

void MnnSd15Model::finishRequest() {
    if (impl_->memoryPolicy == 0) {
        impl_->unloadStages();
    } else if (impl_->memoryPolicy == 1) {
        impl_->unet.reset();
        impl_->resetVaeEncoder();
        impl_->vae.reset();
        impl_->unetContextTokens = 0;
        LOGI("MNN SD 1.5 balanced policy retained CLIP between requests");
    } else {
        LOGI("MNN SD 1.5 speed-first policy retained all stages between requests");
    }
}

size_t MnnSd15Model::textChunkCount(const std::string& text) const {
    return impl_->tokenizer.chunkCount(text);
}

std::vector<float> MnnSd15Model::encodeText(
    const std::string& textValue,
    size_t minimumChunks) {
    if (!impl_->ensureText()) return {};
    const auto chunks = impl_->tokenizer.encodeChunks(textValue, minimumChunks);
    std::vector<float> context;
    context.reserve(chunks.size() * kContextElements);
    for (const auto& ids : chunks) {
        if (ids.size() != kTokens) return {};
        auto embedding = impl_->text.encode(ids);
        if (embedding.size() != kContextElements) return {};
        context.insert(context.end(), embedding.begin(), embedding.end());
    }
    LOGI("encoded CLIP prompt as %zu chunk(s), %zu context tokens", chunks.size(), chunks.size() * kTokens);
    return context;
}

std::vector<float> MnnSd15Model::unet(
    const std::vector<float>& latent,
    int32_t timestep,
    const std::vector<float>& context) {
    if (latent.size() != kLatentElements || context.empty() ||
        context.size() % kContextElements != 0) return {};

    const int contextTokens = static_cast<int>(context.size() / kTextDim);
    // A released MNN model cannot resize an existing session. This normally cannot happen inside
    // one generation, but reopening makes a retained speed-mode session safe for a later prompt
    // with a different number of CLIP chunks.
    if (impl_->unet.session && impl_->unet.modelReleased &&
        impl_->unetContextTokens != contextTokens) {
        impl_->unet.reset();
        impl_->unetContextTokens = 0;
    }
    if (!impl_->ensureUnet()) return {};

    auto* sample = impl_->unet.net->getSessionInput(impl_->unet.session, "sample");
    auto* time = impl_->unet.net->getSessionInput(impl_->unet.session, "timestep");
    auto* encoder = impl_->unet.net->getSessionInput(impl_->unet.session, "encoder_hidden_states");
    if (!sample || !time || !encoder) return {};
    const bool sampleMatches = sample->dimensions() == 4 &&
        sample->length(0) == kTextBatch && sample->length(1) == kLatentChannels &&
        sample->length(2) == kLatentSide && sample->length(3) == kLatentSide;
    const bool timeMatches = time->elementSize() == kTextBatch;
    const bool encoderMatches = encoder->dimensions() == 3 &&
        encoder->length(0) == kTextBatch && encoder->length(1) == contextTokens &&
        encoder->length(2) == kTextDim;
    if (!sampleMatches || !timeMatches || !encoderMatches) {
        impl_->unet.net->resizeTensor(
            sample,
            {kTextBatch, kLatentChannels, kLatentSide, kLatentSide});
        impl_->unet.net->resizeTensor(time, {kTextBatch});
        impl_->unet.net->resizeTensor(encoder, {kTextBatch, contextTokens, kTextDim});
        impl_->unet.net->resizeSession(impl_->unet.session);
        sample = impl_->unet.net->getSessionInput(impl_->unet.session, "sample");
        time = impl_->unet.net->getSessionInput(impl_->unet.session, "timestep");
        encoder = impl_->unet.net->getSessionInput(impl_->unet.session, "encoder_hidden_states");
        if (!sample || !time || !encoder || sample->dimensions() != 4 ||
            sample->length(0) != kTextBatch || sample->length(1) != kLatentChannels ||
            sample->length(2) != kLatentSide || sample->length(3) != kLatentSide ||
            time->elementSize() != kTextBatch || encoder->dimensions() != 3 ||
            encoder->length(0) != kTextBatch || encoder->length(1) != contextTokens ||
            encoder->length(2) != kTextDim) {
            LOGE("UNet input resize failed for latent [1,4,64,64], context [1,%d,%d]",
                 contextTokens, kTextDim);
            return {};
        }
        if (impl_->unet.backend == MNN_FORWARD_OPENCL) {
            impl_->unet.net->updateCacheFile(impl_->unet.session);
        }
        LOGI("resized UNet context to [1,%d,%d]", contextTokens, kTextDim);
    }
    impl_->unetContextTokens = contextTokens;
    impl_->unet.releaseModelData();
    auto* output = impl_->unet.net->getSessionOutput(impl_->unet.session, "out_sample");
    const std::vector<int32_t> times{timestep};
    if (!copyInput(sample, latent, {kTextBatch, kLatentChannels, kLatentSide, kLatentSide}) ||
        !copyInput(time, times, {kTextBatch}) ||
        !copyInput(encoder, context, {kTextBatch, contextTokens, kTextDim}) ||
        !run(*impl_->unet.net, impl_->unet.session, "UNet")) {
        return {};
    }
    std::vector<float> result(kLatentElements);
    if (!copyOutput(output, result, {kTextBatch, kLatentChannels, kLatentSide, kLatentSide}, "UNet output")) return {};
    return result;
}

std::vector<float> MnnSd15Model::unetGuided(
    const std::vector<float>& latent,
    int32_t timestep,
    const std::vector<float>& conditional,
    const std::vector<float>& unconditional,
    float guidance) {
    if (conditional.size() != unconditional.size()) return {};
    auto result = unet(latent, timestep, unconditional);
    const auto cond = unet(latent, timestep, conditional);
    if (result.size() != kLatentElements || cond.size() != kLatentElements) return {};
    for (int i = 0; i < kLatentElements; ++i) {
        result[i] += guidance * (cond[i] - result[i]);
    }
    return result;
}

std::vector<float> MnnSd15Model::vaeDecode(const std::vector<float>& latent) {
    if (latent.size() != kLatentElements || !impl_->ensureVae()) return {};
    std::vector<float> scaled(kLatentElements);
    for (int i = 0; i < kLatentElements; ++i) scaled[i] = latent[i] / kVaeScale;
#if !FANCY_INTEGRITY_REQUIRED
    float minimum = std::numeric_limits<float>::infinity();
    float maximum = -std::numeric_limits<float>::infinity();
    double sum = 0.0;
    size_t nonfinite = 0;
    for (float value : scaled) {
        if (!std::isfinite(value)) {
            ++nonfinite;
            continue;
        }
        minimum = std::min(minimum, value);
        maximum = std::max(maximum, value);
        sum += value;
    }
    LOGI("VAE decoder input: count=%zu finite=%zu min=%g max=%g sum=%.9g",
         scaled.size(), scaled.size() - nonfinite, minimum, maximum, sum);
#endif
    auto* input = impl_->vae.net->getSessionInput(impl_->vae.session, "latent_sample");
    auto* output = impl_->vae.net->getSessionOutput(impl_->vae.session, "sample");
    if (!copyInput(input, scaled, {1, kLatentChannels, kLatentSide, kLatentSide}) ||
        !run(*impl_->vae.net, impl_->vae.session, "VAE decoder")) {
        return {};
    }
    std::vector<float> pixels(kImageElements);
    if (!copyOutput(output, pixels, {1, kImageChannels, kImageSide, kImageSide}, "VAE decoder output")) return {};
    for (float& value : pixels) value = (value * 0.5f + 0.5f) * 255.0f;
    return pixels;
}

std::vector<float> MnnSd15Model::vaeEncodeRgba(
    const uint8_t* pixels,
    int width,
    int height,
    int stride) {
    if (!pixels || width <= 0 || height <= 0 || stride < width * 4 ||
        !impl_->ensureVaeEncoder()) {
        return {};
    }
    auto* input = impl_->vaeEncoder.net->getSessionInput(impl_->vaeEncoder.session, "input");
    auto* output = impl_->vaeEncoder.net->getSessionOutput(impl_->vaeEncoder.session, "mean");
    if (!input || !output) return {};

    if (!impl_->vaeImageProcess) {
        MNN::CV::ImageProcess::Config config;
        config.sourceFormat = MNN::CV::RGBA;
        config.destFormat = MNN::CV::RGB;
        config.filterType = MNN::CV::BILINEAR;
        config.wrap = MNN::CV::CLAMP_TO_EDGE;
        for (int channel = 0; channel < 3; ++channel) {
            config.mean[channel] = 127.5f;
            config.normal[channel] = 1.0f / 127.5f;
        }
        impl_->vaeImageProcess.reset(MNN::CV::ImageProcess::create(config, input));
        if (!impl_->vaeImageProcess) return {};
    }

    // ImageProcess maps destination tensor coordinates directly into source pixels.
    MNN::CV::Matrix transform;
    transform.setScale(
        static_cast<float>(width) / static_cast<float>(kImageSide),
        static_cast<float>(height) / static_cast<float>(kImageSide));
    impl_->vaeImageProcess->setMatrix(transform);

    const auto started = std::chrono::steady_clock::now();
    const auto code = impl_->vaeImageProcess->convert(pixels, width, height, stride, input);
    const auto converted = std::chrono::steady_clock::now();
    if (code != MNN::NO_ERROR) {
        LOGE("MNN ImageProcess VAE input failed: %d", static_cast<int>(code));
        return {};
    }
    if (!run(*impl_->vaeEncoder.net, impl_->vaeEncoder.session, "VAE encoder")) return {};
    std::vector<float> latent(kLatentElements);
    if (!copyOutput(output, latent, {1, kLatentChannels, kLatentSide, kLatentSide}, "VAE encoder mean")) return {};
    auto* deviation = impl_->vaeEncoder.net->getSessionOutput(impl_->vaeEncoder.session, "std");
    std::vector<float> stddev(kLatentElements);
    if (!copyOutput(deviation, stddev, {1, kLatentChannels, kLatentSide, kLatentSide}, "VAE encoder deviation")) return {};
    latent.insert(latent.end(), stddev.begin(), stddev.end());
    const auto finished = std::chrono::steady_clock::now();
    const double preprocessMs =
        std::chrono::duration<double, std::milli>(converted - started).count();
    const double encodeMs =
        std::chrono::duration<double, std::milli>(finished - converted).count();
    LOGI(
        "MNN img2img I/O: Bitmap RGBA %dx%d stride=%d -> ImageProcess -> VAE tensor; "
        "preprocess=%.2fms encode=%.2fms, removed staged image + Java IntArray/FloatArray",
        width, height, stride, preprocessMs, encodeMs);
    return latent;
}

}  // namespace aura
