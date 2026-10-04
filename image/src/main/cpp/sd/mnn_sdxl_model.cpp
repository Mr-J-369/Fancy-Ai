#include "mnn_sdxl_model.h"

#include "clip_tokenizer.h"
#include "mnn_clip.h"
#include "native_diffusion_pipeline.h"

#include <MNN/Interpreter.hpp>
#include <MNN/MNNForwardType.h>
#include <MNN/Tensor.hpp>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <fstream>
#include <memory>
#include <filesystem>
#include <cstdlib>
#include <utility>

#define TAG "fancysdXLmnn"
#if FANCY_INTEGRITY_REQUIRED
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

namespace aura {
namespace {

constexpr int kBatch = 1;
constexpr int kTokens = 77;
constexpr int kClip1Dim = 768;
constexpr int kClip2Dim = 1280;
constexpr int kContextDim = kClip1Dim + kClip2Dim;
constexpr int kPooledDim = 1280;
constexpr int kLatentChannels = 4;
constexpr int kLatentSide = 128;
constexpr int kImageChannels = 3;
constexpr int kImageSide = 1024;
constexpr int kEos = 49407;
constexpr int kLatentElements = kLatentChannels * kLatentSide * kLatentSide;
constexpr int kImageElements = kImageChannels * kImageSide * kImageSide;
constexpr float kVaeScale = 0.13025f;

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
        if (type == MNN_FORWARD_CPU) {
            config.numThread = std::max(1, std::min(6, static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN))));
        }
        backendConfig.power = MNN::BackendConfig::Power_High;
        backendConfig.precision = MNN::BackendConfig::Precision_Low;
        backendConfig.memory = memoryMode;
        config.backendConfig = &backendConfig;
        if (type == MNN_FORWARD_OPENCL) {
            config.mode = MNN_GPU_TUNING_FAST | MNN_GPU_MEMORY_BUFFER;
            cachePath = path + ".mnnc";
            net->setCacheFile(cachePath.c_str());
        }
        // Allocate and tune resizable graphs only after their actual input shapes are known.
        if (keepModelForResize) net->setSessionMode(MNN::Interpreter::Session_Resize_Defer);
        session = net->createSession(config);
        if (!session) {
            LOGE("createSession failed: %s", path.c_str());
            return false;
        }
        backend = type;
        if (type == MNN_FORWARD_OPENCL && !keepModelForResize) net->updateCacheFile(session);
        if (!keepModelForResize) releaseModelData();
        const char* memoryName = memoryMode == MNN::BackendConfig::Memory_Low ? "low-memory" :
            memoryMode == MNN::BackendConfig::Memory_High ? "speed-first" : "balanced";
        LOGI("loaded %s (%s, low precision, %s, buffer storage)", path.c_str(),
             type == MNN_FORWARD_OPENCL ? "OpenCL" : "CPU",
             memoryName);
        return true;
    }
};

template <typename T>
bool copyInput(MNN::Tensor* destination, const std::vector<T>& values, const std::vector<int>& shape) {
    if (!destination || destination->elementSize() != static_cast<int>(values.size())) return false;
    std::unique_ptr<MNN::Tensor> host(MNN::Tensor::create<T>(
        shape, const_cast<T*>(values.data()), MNN::Tensor::CAFFE));
    return destination->copyFromHostTensor(host.get());
}

bool copyOutput(MNN::Tensor* source, std::vector<float>& values, const std::vector<int>& shape) {
    if (!source || source->elementSize() != static_cast<int>(values.size())) return false;
    std::unique_ptr<MNN::Tensor> host(MNN::Tensor::create<float>(shape, values.data(), MNN::Tensor::CAFFE));
    return source->copyToHostTensor(host.get());
}

bool run(MNN::Interpreter& net, MNN::Session* session, const char* stage) {
    const auto code = net.runSession(session);
    if (code == MNN::NO_ERROR) return true;
    LOGE("%s runSession failed: %d", stage, static_cast<int>(code));
    return false;
}

void clip2Padding(std::vector<int32_t>& ids) {
    const auto eos = std::find(ids.begin(), ids.end(), kEos);
    if (eos != ids.end()) std::fill(eos + 1, ids.end(), 0);
}

}  // namespace

struct MnnSdxlModel::Impl {
    std::string directory;
    ClipTokenizer tokenizer;
    MnnClip clip1;
    MnnClip clip2;
    Stage unet;
    Stage vaeEncoder;
    Stage vae;
    int unetContextTokens = 0;
    int backend = 0;
    int memoryPolicy = 0;
    int vaeTilePixels = 640;

    [[nodiscard]] MNN::BackendConfig::MemoryMode backendMemory() const {
        if (memoryPolicy == 2) return MNN::BackendConfig::Memory_High;
        if (memoryPolicy == 1) return MNN::BackendConfig::Memory_Normal;
        return MNN::BackendConfig::Memory_Low;
    }

    void resetText() {
        clip1.free();
        clip2.free();
    }

    void unloadStages() {
        resetText();
        unet.reset();
        vaeEncoder.reset();
        vae.reset();
        unetContextTokens = 0;
    }

    bool ensureText() {
        if (clip1.ready() && clip2.ready()) return true;
        resetText();
        if (memoryPolicy != 2) {
            unet.reset();
            vaeEncoder.reset();
            vae.reset();
        }
        if (!clip1.loadAs(
                directory,
                "clip.mnn",
                "token_emb.bin",
                "pos_emb.bin",
                kClip1Dim,
                false)) {
            return false;
        }
        if (clip2.loadAs(
                directory,
                "clip_2.mnn",
                "token_emb_2.bin",
                "pos_emb_2.bin",
                kClip2Dim,
                true)) {
            return true;
        }
        resetText();
        return false;
    }

    bool ensureUnet() {
        if (unet.session) return true;
        if (memoryPolicy == 0) resetText();
        if (memoryPolicy != 2) {
            vaeEncoder.reset();
            vae.reset();
        }
        unetContextTokens = 0;
        return unet.open(directory + "/unet.mnn", backend, backendMemory(), true);
    }

    bool ensureVae() {
        if (vae.session) return true;
        if (memoryPolicy == 0) resetText();
        if (memoryPolicy != 2) {
            unet.reset();
            vaeEncoder.reset();
        }
        return vae.open(
            directory + "/vae_decoder.mnn",
            backend,
            backendMemory());
    }

    bool ensureVaeEncoder() {
        if (vaeEncoder.session) return true;
        if (memoryPolicy == 0) resetText();
        if (memoryPolicy != 2) {
            unet.reset();
            vae.reset();
        }
        return vaeEncoder.open(
            directory + "/vae_encoder.mnn",
            backend,
            backendMemory());
    }
};

MnnSdxlModel::MnnSdxlModel() : impl_(std::make_unique<Impl>()) {}
MnnSdxlModel::~MnnSdxlModel() = default;

bool MnnSdxlModel::load(const std::string& directory) {
    impl_->unloadStages();
    impl_->directory = directory;
    std::ifstream tileSizeFile(directory + "/vae_tile_size.txt");
    int tileSize = 640;
    if (tileSizeFile.is_open()) {
        if (!(tileSizeFile >> tileSize) || (tileSize != 640 && tileSize != 1024)) {
            LOGE("SDXL package requires 640px or 1024px VAE graphs, got %d", tileSize);
            return false;
        }
    }
    impl_->vaeTilePixels = tileSize;
    if (!impl_->tokenizer.load(directory + "/tokenizer.json")) {
        LOGE("SDXL tokenizer load failed: %s", directory.c_str());
        return false;
    }
    return impl_->ensureText();
}

void MnnSdxlModel::setBackend(int backend) {
    const int normalized = std::clamp(backend, 0, 2);
    if (impl_->backend == normalized) return;
    impl_->unloadStages();
    impl_->backend = normalized;
    LOGI("MNN SDXL processor set to %s",
         normalized == 1 ? "CPU" :
         normalized == 2 ? "OpenCL with CPU fallback" : "Auto");
}

void MnnSdxlModel::setMemoryPolicy(int policy) {
    const int normalized = std::clamp(policy, 0, 2);
    if (impl_->memoryPolicy == normalized) return;
    impl_->unloadStages();
    impl_->memoryPolicy = normalized;
    LOGI("MNN SDXL memory policy set to %s",
         normalized == 0 ? "low-memory" : normalized == 1 ? "balanced" : "speed-first");
}

void MnnSdxlModel::finishRequest() {
    if (impl_->memoryPolicy == 0) {
        impl_->unloadStages();
    } else if (impl_->memoryPolicy == 1) {
        impl_->unet.reset();
        impl_->vaeEncoder.reset();
        impl_->vae.reset();
        impl_->unetContextTokens = 0;
        LOGI("MNN SDXL balanced policy retained CLIP between requests");
    } else {
        LOGI("MNN SDXL speed-first policy retained all stages between requests");
    }
}

size_t MnnSdxlModel::textChunkCount(const std::string& text) const {
    return impl_->tokenizer.chunkCount(text);
}

SdxlConditioning MnnSdxlModel::encodeText(const std::string& text, size_t minimumChunks) {
    const size_t chunks = std::max(textChunkCount(text), std::max<size_t>(1, minimumChunks));
    const auto ids = impl_->tokenizer.encodeChunks(text, chunks);
    if (ids.size() != chunks || !impl_->ensureText()) return {};

    SdxlConditioning result;
    result.context.resize(chunks * kTokens * kContextDim);
    result.pooled.assign(kPooledDim, 0.0f);
    for (size_t chunk = 0; chunk < chunks; ++chunk) {
        const auto hidden1 = impl_->clip1.encode(ids[chunk]);
        auto ids2 = ids[chunk];
        clip2Padding(ids2);
        std::vector<float> hidden2;
        std::vector<float> pooled2;
        if (hidden1.size() != static_cast<size_t>(kTokens) * kClip1Dim ||
            !impl_->clip2.encodePooled(ids2, hidden2, pooled2) ||
            hidden2.size() != static_cast<size_t>(kTokens) * kClip2Dim ||
            pooled2.size() != static_cast<size_t>(kTokens) * kPooledDim) {
            if (impl_->memoryPolicy == 0) impl_->resetText();
            return {};
        }

        for (int token = 0; token < kTokens; ++token) {
            const size_t destination = (chunk * kTokens + token) * kContextDim;
            const size_t clip1Source = static_cast<size_t>(token) * kClip1Dim;
            const size_t clip2Source = static_cast<size_t>(token) * kClip2Dim;
            std::copy_n(hidden1.begin() + static_cast<std::ptrdiff_t>(clip1Source), kClip1Dim,
                        result.context.begin() + static_cast<std::ptrdiff_t>(destination));
            std::copy_n(hidden2.begin() + static_cast<std::ptrdiff_t>(clip2Source), kClip2Dim,
                        result.context.begin() + static_cast<std::ptrdiff_t>(destination + kClip1Dim));
        }

        if (chunk + 1 == chunks) {
            const auto eos = std::find(ids2.begin(), ids2.end(), kEos);
            if (eos == ids2.end()) {
                if (impl_->memoryPolicy == 0) impl_->resetText();
                return {};
            }
            const auto eosIndex = static_cast<size_t>(std::distance(ids2.begin(), eos));
            std::copy_n(
                pooled2.begin() + static_cast<std::ptrdiff_t>(eosIndex * kPooledDim),
                kPooledDim,
                result.pooled.begin());
        }
    }
    if (impl_->memoryPolicy == 0) impl_->resetText();
    LOGI("encoded dual CLIP prompt as %zu chunk(s), %zu context tokens", chunks, chunks * kTokens);
    return result;
}

std::vector<float> MnnSdxlModel::unet(
    const std::vector<float>& latent,
    int32_t timestep,
    const SdxlConditioning& conditioning) {
    if (latent.size() != kLatentElements || conditioning.context.empty() ||
        conditioning.context.size() % kContextDim != 0 || conditioning.pooled.size() != kPooledDim) return {};
    const int contextTokens = static_cast<int>(conditioning.context.size() / kContextDim);
    if (impl_->unet.session && impl_->unet.modelReleased && impl_->unetContextTokens != contextTokens) {
        impl_->unet.reset();
        impl_->unetContextTokens = 0;
    }
    if (!impl_->ensureUnet()) return {};
    auto* sample = impl_->unet.net->getSessionInput(impl_->unet.session, "sample");
    auto* time = impl_->unet.net->getSessionInput(impl_->unet.session, "timestamp");
    auto* context = impl_->unet.net->getSessionInput(impl_->unet.session, "encoder_hidden_states");
    auto* pooled = impl_->unet.net->getSessionInput(impl_->unet.session, "text_embeds");
    auto* timeIds = impl_->unet.net->getSessionInput(impl_->unet.session, "time_ids");
    if (!sample || !time || !context || !pooled || !timeIds) return {};
    if (!impl_->unet.modelReleased || context->dimensions() != 3 || context->length(1) != contextTokens) {
        impl_->unet.net->resizeTensor(context, {kBatch, contextTokens, kContextDim});
        impl_->unet.net->resizeSession(impl_->unet.session);
        sample = impl_->unet.net->getSessionInput(impl_->unet.session, "sample");
        time = impl_->unet.net->getSessionInput(impl_->unet.session, "timestamp");
        context = impl_->unet.net->getSessionInput(impl_->unet.session, "encoder_hidden_states");
        pooled = impl_->unet.net->getSessionInput(impl_->unet.session, "text_embeds");
        timeIds = impl_->unet.net->getSessionInput(impl_->unet.session, "time_ids");
        if (!sample || !time || !context || !pooled || !timeIds || context->length(1) != contextTokens) {
            LOGE("SDXL UNet context resize failed for [1,%d,%d]", contextTokens, kContextDim);
            return {};
        }
        if (impl_->unet.backend == MNN_FORWARD_OPENCL) impl_->unet.net->updateCacheFile(impl_->unet.session);
        LOGI("resized SDXL UNet context to [1,%d,%d]", contextTokens, kContextDim);
    }
    impl_->unetContextTokens = contextTokens;
    impl_->unet.releaseModelData();
    const std::vector<int32_t> times{timestep};
    const std::vector<float> addTimeIds{1024.0f, 1024.0f, 0.0f, 0.0f, 1024.0f, 1024.0f};
    auto* output = impl_->unet.net->getSessionOutput(impl_->unet.session, "output");
    if (!copyInput(sample, latent, {kBatch, kLatentChannels, kLatentSide, kLatentSide}) ||
        !copyInput(time, times, {kBatch}) ||
        !copyInput(context, conditioning.context, {kBatch, contextTokens, kContextDim}) ||
        !copyInput(pooled, conditioning.pooled, {kBatch, kPooledDim}) ||
        !copyInput(timeIds, addTimeIds, {kBatch, 6}) ||
        !run(*impl_->unet.net, impl_->unet.session, "SDXL UNet")) return {};
    std::vector<float> result(kLatentElements);
    if (!copyOutput(output, result, {kBatch, kLatentChannels, kLatentSide, kLatentSide})) return {};
    return result;
}

std::vector<float> MnnSdxlModel::unetGuided(
    const std::vector<float>& latent,
    int32_t timestep,
    const SdxlConditioning& conditional,
    const SdxlConditioning& unconditional,
    float guidance) {
    if (conditional.context.size() != unconditional.context.size()) return {};
    auto result = unet(latent, timestep, unconditional);
    const auto cond = unet(latent, timestep, conditional);
    if (result.size() != kLatentElements || cond.size() != kLatentElements) return {};
    for (int i = 0; i < kLatentElements; ++i) result[i] += guidance * (cond[i] - result[i]);
    return result;
}

std::vector<float> MnnSdxlModel::vaeEncodeRgba(
    const uint8_t* pixels,
    int width,
    int height,
    int stride) {
    if (!pixels || width <= 0 || height <= 0 || stride < width * 4) return {};
    const auto started = std::chrono::steady_clock::now();
    const int tilePixels = impl_->vaeTilePixels;
    const int overlapPixels = tilePixels >= kImageSide ? 0 : 256;
    const int tileLatentSide = tilePixels / 8;
    const size_t tileLatentElements = static_cast<size_t>(kLatentChannels) * tileLatentSide * tileLatentSide;
    const size_t tileImageElements = static_cast<size_t>(kImageChannels) * tilePixels * tilePixels;
    const int totalTiles = tilePixels >= kImageSide ? 1 : 4;
    int tileIndex = 0;
    auto encoded = diffusion::encodeTiled(
        pixels,
        width,
        height,
        stride,
        kImageSide,
        kImageSide,
        tilePixels,
        overlapPixels,
        [&](const std::vector<float>& tile) {
            const auto tileStarted = std::chrono::steady_clock::now();
            if (tile.size() != tileImageElements || !impl_->ensureVaeEncoder()) {
                return std::vector<float>{};
            }
            auto* input = impl_->vaeEncoder.net->getSessionInput(impl_->vaeEncoder.session, "input");
            auto* mean = impl_->vaeEncoder.net->getSessionOutput(impl_->vaeEncoder.session, "mean");
            auto* deviation = impl_->vaeEncoder.net->getSessionOutput(impl_->vaeEncoder.session, "std");
            if (!copyInput(input, tile, {kBatch, kImageChannels, tilePixels, tilePixels}) ||
                !run(*impl_->vaeEncoder.net, impl_->vaeEncoder.session, "SDXL VAE encoder")) {
                return std::vector<float>{};
            }
            std::vector<float> encoded(tileLatentElements);
            std::vector<float> stddev(tileLatentElements);
            if (!copyOutput(mean, encoded, {kBatch, kLatentChannels, tileLatentSide, tileLatentSide}) ||
                !copyOutput(deviation, stddev, {kBatch, kLatentChannels, tileLatentSide, tileLatentSide})) {
                return std::vector<float>{};
            }
            encoded.insert(encoded.end(), stddev.begin(), stddev.end());
            LOGI(
                "SDXL VAE encoder tile %d/%d: %.2fms",
                ++tileIndex,
                totalTiles,
                std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() - tileStarted).count());
            return encoded;
        });
    LOGI(
        "SDXL VAE encoder total: %.2fms",
        std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started).count());
    return encoded;
}

std::vector<float> MnnSdxlModel::vaeDecode(const std::vector<float>& latent) {
    if (latent.size() != kLatentElements) return {};
    std::vector<float> scaled(kLatentElements);
    for (int i = 0; i < kLatentElements; ++i) scaled[i] = latent[i] / kVaeScale;
    const auto started = std::chrono::steady_clock::now();
    const int tilePixels = impl_->vaeTilePixels;
    const int overlapPixels = tilePixels >= kImageSide ? 0 : 256;
    const int tileLatentSide = tilePixels / 8;
    const size_t tileLatentElements = static_cast<size_t>(kLatentChannels) * tileLatentSide * tileLatentSide;
    const size_t tileImageElements = static_cast<size_t>(kImageChannels) * tilePixels * tilePixels;
    const int totalTiles = tilePixels >= kImageSide ? 1 : 4;
    int tileIndex = 0;
    auto pixels = diffusion::decodeTiled(
        scaled,
        kImageSide,
        kImageSide,
        tilePixels,
        overlapPixels,
        [&](const std::vector<float>& tile) {
            const auto tileStarted = std::chrono::steady_clock::now();
            if (tile.size() != tileLatentElements || !impl_->ensureVae()) {
                return std::vector<float>{};
            }
            auto* input = impl_->vae.net->getSessionInput(impl_->vae.session, "input");
            auto* output = impl_->vae.net->getSessionOutput(impl_->vae.session, "output");
            if (!copyInput(
                    input,
                    tile,
                    {kBatch, kLatentChannels, tileLatentSide, tileLatentSide}) ||
                !run(*impl_->vae.net, impl_->vae.session, "SDXL VAE decoder")) {
                return std::vector<float>{};
            }
            std::vector<float> decoded(tileImageElements);
            if (!copyOutput(
                    output,
                    decoded,
                    {kBatch, kImageChannels, tilePixels, tilePixels})) {
                return std::vector<float>{};
            }
            LOGI(
                "SDXL VAE decoder tile %d/%d: %.2fms",
                ++tileIndex,
                totalTiles,
                std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() - tileStarted).count());
            return decoded;
        });
    if (pixels.size() != kImageElements) return {};
    LOGI(
        "SDXL VAE decoder total: %.2fms",
        std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started).count());
    for (float& value : pixels) value = (value * 0.5f + 0.5f) * 255.0f;
    return pixels;
}

}  // namespace aura
