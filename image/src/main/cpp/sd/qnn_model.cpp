#include "qnn_model.h"
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <cctype>
#include <sys/stat.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "fancyqnn", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "fancyqnn", __VA_ARGS__)

namespace aura {

static constexpr float VAE_SCALE = 0.18215f;

static long fileSize(const std::string& p) {
    struct stat st{};
    return (stat(p.c_str(), &st) == 0) ? static_cast<long>(st.st_size) : -1L;
}

static std::string lower(std::string s) {
    for (char& ch : s) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    return s;
}

static std::vector<float> nchwToNhwc(const std::vector<float>& src, int c, int h, int w) {
    const size_t n = static_cast<size_t>(c) * h * w;
    if (src.size() < n) return src;
    std::vector<float> dst(n);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int ch = 0; ch < c; ++ch) {
                dst[(static_cast<size_t>(y) * w + x) * c + ch] =
                    src[(static_cast<size_t>(ch) * h + y) * w + x];
            }
        }
    }
    return dst;
}

static std::vector<float> nhwcToNchw(const std::vector<float>& src, int c, int h, int w) {
    const size_t n = static_cast<size_t>(c) * h * w;
    if (src.size() < n) return src;
    std::vector<float> dst(n);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int ch = 0; ch < c; ++ch) {
                dst[(static_cast<size_t>(ch) * h + y) * w + x] =
                    src[(static_cast<size_t>(y) * w + x) * c + ch];
            }
        }
    }
    return dst;
}

static bool isNhwc(const TensorDesc& d, int c, int h, int w) {
    return d.dims.size() == 4 && d.dims[1] == h && d.dims[2] == w && d.dims[3] == c;
}

bool SdModel::load(const std::string& dir, const std::string& libDir, const std::string& skelDir,
                   int width, int height) {
    dir_ = dir;
    if (!QnnGraphRunner::initBackend(libDir, skelDir)) { LOGE("SdModel: QNN backend init failed"); return false; }
    if (!tok_.load(dir + "/tokenizer.json")) { LOGE("SdModel: tokenizer load failed"); return false; }
    width_ = width;
    height_ = height;
    latElems_ = 4 * (width / 8) * (height / 8);
    LOGI("SdModel: ready (lowram stages load on demand, one at a time)");
    return true;
}

bool SdModel::ensureClip() {
    if (unet_.loaded()) { unet_.freeContext(); LOGI("SdModel: UNet released, loading CLIP"); }
    if (vae_.loaded()) { vae_.freeContext(); LOGI("SdModel: VAE decoder released, loading CLIP"); }
    if (vaeEnc_.loaded()) { vaeEnc_.freeContext(); LOGI("SdModel: VAE encoder released, loading CLIP"); }
    if (clip_.ready()) return true;
    if (!clip_.load(dir_)) { LOGE("SdModel: CLIP(MNN) load failed"); return false; }
    LOGI("SdModel: CLIP loaded (lowram)");
    return true;
}

bool SdModel::ensureUnet() {
    if (clip_.ready()) { clip_.free(); LOGI("SdModel: CLIP released, loading UNet"); }
    if (vae_.loaded()) { vae_.freeContext(); LOGI("SdModel: VAE decoder released, loading UNet"); }
    if (vaeEnc_.loaded()) { vaeEnc_.freeContext(); LOGI("SdModel: VAE encoder released, loading UNet"); }
    if (unet_.loaded()) return true;

    bool ok;
    if (width_ == 512 && height_ == 512) {
        ok = unet_.loadContext(dir_ + "/unet.bin");
    } else {
        const std::string patch = dir_ + "/" + std::to_string(width_) + "x" + std::to_string(height_) + ".patch";
        LOGI("SdModel: loading UNet %dx%d via patch %s", width_, height_, patch.c_str());
        ok = unet_.loadPatchedContext(dir_ + "/unet.bin", patch);
    }
    if (!ok) { LOGE("SdModel: UNet load failed (%dx%d)", width_, height_); return false; }
    LOGI("SdModel: UNet loaded (lowram) ↓");
    unet_.logIo();
    return true;
}

bool SdModel::ensureVaeDecoder() {
    if (clip_.ready()) { clip_.free(); LOGI("SdModel: CLIP released, loading VAE decoder"); }
    if (unet_.loaded()) { unet_.freeContext(); LOGI("SdModel: UNet released, loading VAE decoder"); }
    if (vaeEnc_.loaded()) { vaeEnc_.freeContext(); LOGI("SdModel: VAE encoder released, loading VAE decoder"); }
    if (vae_.loaded()) return true;
    if (!vae_.loadContext(dir_ + "/vae_decoder.bin")) { LOGE("SdModel: VAE decoder load failed"); return false; }
    LOGI("SdModel: VAE decoder loaded (lowram) ↓");
    vae_.logIo();
    return true;
}

bool SdModel::ensureVaeEncoder() {
    if (clip_.ready()) { clip_.free(); LOGI("SdModel: CLIP released, loading VAE encoder"); }
    if (unet_.loaded()) { unet_.freeContext(); LOGI("SdModel: UNet released, loading VAE encoder"); }
    if (vae_.loaded()) { vae_.freeContext(); LOGI("SdModel: VAE decoder released, loading VAE encoder"); }
    if (vaeEnc_.loaded()) return true;
    if (!vaeEnc_.loadContext(dir_ + "/vae_encoder.bin")) {
        LOGE("SdModel: no/failed vae_encoder.bin (img2img unavailable for this model)");
        return false;
    }
    LOGI("SdModel: VAE encoder loaded (lowram) ↓");
    vaeEnc_.logIo();
    return true;
}

size_t SdModel::textChunkCount(const std::string& text) const {
    return tok_.chunkCount(text);
}

size_t SdModel::supportedChunkCount() const {
    return fileSize(dir_ + "/qnn_context.txt") > 0 ? MAX_CHUNKS : 1u;
}

std::vector<float> SdModel::encodeText(const std::string& text, size_t chunks) {
    if (!ensureClip()) return {};
    chunks = std::max<size_t>(1, std::min(chunks, supportedChunkCount()));
    auto ids = tok_.encodeChunks(text, chunks);
    ids.resize(chunks);
    std::vector<float> out;
    out.reserve(chunks * static_cast<size_t>(EMB));
    for (size_t i = 0; i < chunks; ++i) {
        const auto part = clip_.encode(ids[i]);
        if (part.size() != static_cast<size_t>(EMB)) {
            LOGE("SdModel: CLIP output size mismatch for chunk %zu", i + 1);
            return {};
        }
        out.insert(out.end(), part.begin(), part.end());
    }
    return out;
}

std::vector<float> SdModel::unet(const std::vector<float>& latent, int timestep,
                                 const std::vector<float>& textEmb, size_t activeChunks) {
    if (!ensureUnet()) return {};

    auto descs = unet_.inputs();
    std::vector<std::vector<float>> in(descs.size());
    const std::vector<float> latentNhwc = nchwToNhwc(latent, 4, height_ / 8, width_ / 8);
    for (size_t k = 0; k < descs.size(); ++k) {
        size_t n = 1; for (auto d : descs[k].dims) n *= static_cast<size_t>(d);
        const std::string name = lower(descs[k].name);
        const bool isTimestepInput = name.find("time") != std::string::npos ||
                                     name.find("timestep") != std::string::npos;
        if (name.find("lora_alpha") != std::string::npos) {
            in[k] = std::vector<float>(std::max<size_t>(n, 1), 1.0f);
        } else if (name.find("encoder_attention_mask") != std::string::npos) {
            in[k].resize(n, 0.0f);
            std::fill_n(in[k].begin(), std::min(n, activeChunks * static_cast<size_t>(TOKENS)), 1.0f);
        } else if (name.find("encoder_hidden_states") != std::string::npos ||
                   (!isTimestepInput && n == textEmb.size())) {
            if (textEmb.size() != n) {
                LOGE("SdModel: UNet context size mismatch got=%zu expected=%zu", textEmb.size(), n);
                return {};
            }
            in[k] = textEmb;
        } else if (!isTimestepInput && n == static_cast<size_t>(latElems_)) {
            in[k] = isNhwc(descs[k], 4, height_ / 8, width_ / 8) ? latentNhwc : latent;
        } else {
            in[k] = std::vector<float>(std::max<size_t>(n, 1), static_cast<float>(timestep));
        }
    }
    std::vector<std::vector<float>> out;
    if (!unet_.execute(in, out) || out.empty()) { LOGE("unet: execute failed"); return {}; }
    auto outDescs = unet_.outputs();
    if (!outDescs.empty() && isNhwc(outDescs[0], 4, height_ / 8, width_ / 8)) {
        return nhwcToNchw(out[0], 4, height_ / 8, width_ / 8);
    }
    return out[0];
}

std::vector<float> SdModel::vaeDecode(const std::vector<float>& latent) {
    if (!ensureVaeDecoder()) return {};
    const int latW = (width_ > 512 || height_ > 512) ? 64 : width_ / 8;
    const int latH = (width_ > 512 || height_ > 512) ? 64 : height_ / 8;
    const int imgW = latW * 8;
    const int imgH = latH * 8;
    std::vector<float> scaled(latent.size());
    for (size_t i = 0; i < latent.size(); ++i) scaled[i] = latent[i] / VAE_SCALE;
    auto inDescs = vae_.inputs();
    std::vector<float> vaeInput = (!inDescs.empty() && isNhwc(inDescs[0], 4, latH, latW))
        ? nchwToNhwc(scaled, 4, latH, latW)
        : scaled;
    std::vector<std::vector<float>> out;
    if (!vae_.execute({vaeInput}, out) || out.empty()) {
        LOGE("vae: execute failed"); return {};
    }

    auto outDescs = vae_.outputs();
    std::vector<float> px = (!outDescs.empty() && isNhwc(outDescs[0], 3, imgH, imgW))
        ? nhwcToNchw(out[0], 3, imgH, imgW)
        : out[0];
    for (auto& v : px) v = (v * 0.5f + 0.5f) * 255.0f;
    return px;
}

std::vector<float> SdModel::vaeEncode(const std::vector<float>& imageNorm) {
    if (!ensureVaeEncoder()) return {};

    const int imgW = (width_ > 512 || height_ > 512) ? 512 : width_;
    const int imgH = (width_ > 512 || height_ > 512) ? 512 : height_;
    const int latW = imgW / 8;
    const int latH = imgH / 8;
    std::vector<std::vector<float>> out;
    auto inDescs = vaeEnc_.inputs();
    std::vector<float> imageInput = (!inDescs.empty() && isNhwc(inDescs[0], 3, imgH, imgW))
        ? nchwToNhwc(imageNorm, 3, imgH, imgW)
        : imageNorm;
    if (!vaeEnc_.execute({imageInput}, out) || out.size() < 2) {
        LOGE("vaeEncode: execute failed"); return {};
    }

    auto descs = vaeEnc_.outputs();
    int meanIdx = 0, stdIdx = 1;
    for (size_t k = 0; k < descs.size() && k < out.size(); ++k) {
        if (descs[k].name.find("mean") != std::string::npos) meanIdx = static_cast<int>(k);
        else if (descs[k].name.find("std") != std::string::npos) stdIdx = static_cast<int>(k);
    }
    if (meanIdx < static_cast<int>(descs.size()) && isNhwc(descs[meanIdx], 4, latH, latW)) {
        out[meanIdx] = nhwcToNchw(out[meanIdx], 4, latH, latW);
    }
    if (stdIdx < static_cast<int>(descs.size()) && isNhwc(descs[stdIdx], 4, latH, latW)) {
        out[stdIdx] = nhwcToNchw(out[stdIdx], 4, latH, latW);
    }
    std::vector<float> res;
    res.reserve(out[meanIdx].size() + out[stdIdx].size());
    res.insert(res.end(), out[meanIdx].begin(), out[meanIdx].end());
    res.insert(res.end(), out[stdIdx].begin(), out[stdIdx].end());
    return res;
}

}
