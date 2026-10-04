#include "sdxl_model.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <sys/stat.h>
#include <unistd.h>

#if FANCY_INTEGRITY_REQUIRED
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "fancyqnn", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "fancyqnn", __VA_ARGS__)
#endif

namespace aura {

static constexpr float VAE_SCALE = 0.13025f;

static constexpr uint32_t CLIP_CACHE_MAGIC = 0x4C435831; 

static long fileSize(const std::string& p) {
    struct stat st{}; return (stat(p.c_str(), &st) == 0) ? static_cast<long>(st.st_size) : -1L;
}

static std::string clipCacheKey(const std::string& text, size_t chunks) {
    uint64_t h = 1469598103934665603ULL;                 
    for (unsigned char c : text) { h ^= c; h *= 1099511628211ULL; }
    h ^= chunks; h *= 1099511628211ULL;
    char buf[20]; snprintf(buf, sizeof buf, "%016llx", static_cast<unsigned long long>(h));
    return {buf};
}

bool SdxlModel::load(const std::string& dir, const std::string& libDir, const std::string& skelDir) {
    dir_ = dir;
    if (!QnnGraphRunner::initBackend(libDir, skelDir)) { LOGE("QNN SDXL init failed"); return false; }
    unetPath_ = dir + "/unet.bin";
    unet154Patch_ = dir + "/154.patch";
    unet231Patch_ = dir + "/231.patch";
    if (!tok_.load(dir + "/tokenizer.json")) { LOGE("SdxlModel: tokenizer failed"); return false; }
    // CLIP is loaded lazily in encodeText(), and only on a cache miss. Building it here
    // costs ~4.7 s of MNN weight dequant + ~100 MB that lowram frees again before denoise,
    // so on a cache hit (same prompt, e.g. reroll with a new seed) it was pure waste.
    LOGI("QNN SDXL path set (lowram — load on demand, free before VAE)");

    return true;
}

void SdxlModel::releaseUnet() {
    if (unetReady_) unet_.freeContext();
    unetReady_ = false;
    unetChunks_ = 0;
}

void SdxlModel::releaseVae() {
    if (vaeReady_) { vae_.freeContext(); vaeReady_ = false; }
    if (encReady_) { vaeEnc_.freeContext(); encReady_ = false; }
}

bool SdxlModel::loadClip() {
    if (clipReady_) return true;
    if (!clip1_.loadAs(dir_, "clip.mnn",   "token_emb.bin",   "pos_emb.bin",   768,  false)) { LOGE("SdxlModel: clip1 failed"); return false; }
    if (!clip2_.loadAs(dir_, "clip_2.mnn", "token_emb_2.bin", "pos_emb_2.bin", 1280, true))  { LOGE("SdxlModel: clip2 failed"); return false; }
    clipReady_ = true;
    return true;
}

void SdxlModel::freeClip() {
    if (!clipReady_) return;
    clip1_.free(); clip2_.free(); clipReady_ = false;
}

bool SdxlModel::ensureUnet(size_t chunks) {
    if (clipReady_) { freeClip(); LOGI("SdxlModel: CLIP released (lowram), entering denoise loop"); }
    if (vaeReady_ || encReady_) { releaseVae(); LOGI("SdxlModel: VAE released (lowram)"); }
    if (chunks < 1 || chunks > 3 || supportedChunkCount(chunks) != chunks) return false;
    if (unetReady_ && unetChunks_ != chunks) releaseUnet();
    if (!unetReady_) {
        const bool loaded = chunks == 1 || fileSize(dir_ + "/qnn_context.txt") > 0
            ? unet_.loadContext(unetPath_)
            : unet_.loadPatchedContext(unetPath_, chunks == 2 ? unet154Patch_ : unet231Patch_);
        if (!loaded) { LOGE("QNN SDXL %zu-chunk UNet load failed", chunks); return false; }
        const auto inputs = unet_.inputs();
        const auto context = std::find_if(inputs.begin(), inputs.end(), [](const TensorDesc& input) {
            return input.name.find("encoder_hidden_states") != std::string::npos;
        });
        const auto expectedTokens = static_cast<int64_t>(chunks * TOKENS);
        if (context == inputs.end() || context->dims.size() < 3 ||
            context->dims[context->dims.size() - 2] != expectedTokens ||
            context->dims.back() != CONTEXT_DIM) {
            LOGE("QNN SDXL UNet context shape mismatch for %lld tokens", static_cast<long long>(expectedTokens));
            unet_.freeContext();
            return false;
        }
        LOGI("QNN SDXL %zu-chunk UNet loaded (lowram) ↓", chunks); unet_.logIo();
        unetReady_ = true;
        unetChunks_ = chunks;
    }
    return true;
}

bool SdxlModel::loadClipCache(const std::string& text, size_t chunks, std::vector<float>& out) const {
    std::ifstream f(dir_ + "/clip_cache/" + clipCacheKey(text, chunks) + ".bin", std::ios::binary);
    if (!f) return false;
    uint32_t magic = 0, tlen = 0, fcount = 0; uint64_t clip2 = 0;
    f.read(reinterpret_cast<char*>(&magic), 4);
    f.read(reinterpret_cast<char*>(&clip2), 8);
    if (!f || magic != CLIP_CACHE_MAGIC || clip2 != static_cast<uint64_t>(fileSize(dir_ + "/clip_2.mnn"))) return false;
    f.read(reinterpret_cast<char*>(&tlen), 4);
    if (!f || tlen != text.size()) return false;
    std::string stored(tlen, '\0');
    if (tlen) f.read(&stored[0], static_cast<std::streamsize>(tlen));
    f.read(reinterpret_cast<char*>(&fcount), 4);
    const size_t expected = chunks * static_cast<size_t>(CTX) + POOL;
    if (!f || stored != text || fcount != static_cast<uint32_t>(expected)) return false;
    out.resize(fcount);
    f.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(fcount) * static_cast<std::streamsize>(sizeof(float)));
    return static_cast<bool>(f);
}

void SdxlModel::saveClipCache(const std::string& text, size_t chunks, const std::vector<float>& out) const {
    const std::string cacheDir = dir_ + "/clip_cache";
    mkdir(cacheDir.c_str(), 0700);                        
    const std::string path = cacheDir + "/" + clipCacheKey(text, chunks) + ".bin";
    const std::string tmp  = path + ".tmp";
    {\
        std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
        if (!f) return;
        uint32_t magic = CLIP_CACHE_MAGIC, tlen = static_cast<uint32_t>(text.size()), fcount = static_cast<uint32_t>(out.size());
        auto clip2 = static_cast<uint64_t>(fileSize(dir_ + "/clip_2.mnn"));
        f.write(reinterpret_cast<const char*>(&magic), 4);
        f.write(reinterpret_cast<const char*>(&clip2), 8);
        f.write(reinterpret_cast<const char*>(&tlen), 4);
        f.write(text.data(), static_cast<std::streamsize>(tlen));
        f.write(reinterpret_cast<const char*>(&fcount), 4);
        f.write(reinterpret_cast<const char*>(out.data()), static_cast<std::streamsize>(fcount) * static_cast<std::streamsize>(sizeof(float)));
        if (!f.good()) { f.close(); unlink(tmp.c_str()); return; }
    }
    rename(tmp.c_str(), path.c_str());                    
}

size_t SdxlModel::textChunkCount(const std::string& text) const {
    return tok_.chunkCount(text);
}

size_t SdxlModel::supportedChunkCount(size_t requested) const {
    const bool has154 = fileSize(unet154Patch_) > 0;
    if (fileSize(dir_ + "/qnn_context.txt") > 0 || (requested >= 3 && has154 && fileSize(unet231Patch_) > 0)) return 3;
    if (requested >= 2 && has154) return 2;
    return 1;
}

std::vector<float> SdxlModel::encodeText(const std::string& text, size_t chunks) {
    chunks = supportedChunkCount(std::max<size_t>(1, chunks));
    std::vector<float> cached;
    if (loadClipCache(text, chunks, cached)) { LOGI("SdxlModel: CLIP cache hit — skipped load+encode"); return cached; }
    if (!loadClip()) { LOGE("SdxlModel: CLIP reload failed"); return {}; }
    auto ids = tok_.encodeChunks(text, chunks);
    ids.resize(chunks);
    std::vector<float> out(chunks * static_cast<size_t>(CTX) + POOL);
    for (size_t chunk = 0; chunk < chunks; ++chunk) {
        const auto h1 = clip1_.encode(ids[chunk]);
        std::vector<float> h2, pooledAll;
        if (!clip2_.encodePooled(ids[chunk], h2, pooledAll) ||
            h1.size() < static_cast<size_t>(TOKENS) * 768 ||
            h2.size() < static_cast<size_t>(TOKENS) * 1280 ||
            pooledAll.size() < static_cast<size_t>(TOKENS) * 1280) {
            LOGE("SdxlModel: CLIP output undersized for chunk %zu", chunk + 1);
            return {};
        }
        for (int token = 0; token < TOKENS; ++token) {
            const size_t destination = (chunk * TOKENS + static_cast<size_t>(token)) * CONTEXT_DIM;
            std::memcpy(out.data() + destination, h1.data() + static_cast<size_t>(token) * 768, 768 * sizeof(float));
            std::memcpy(out.data() + destination + 768, h2.data() + static_cast<size_t>(token) * 1280, 1280 * sizeof(float));
        }
        if (chunk + 1 == (fileSize(dir_ + "/qnn_context.txt") > 0 ? std::min(tok_.chunkCount(text), chunks) : chunks)) {
            int eosIdx = 0;
            for (int token = 0; token < static_cast<int>(ids[chunk].size()); ++token) {
                if (ids[chunk][token] == 49407) { eosIdx = token; break; }
            }
            std::memcpy(out.data() + chunks * static_cast<size_t>(CTX),
                        pooledAll.data() + static_cast<size_t>(eosIdx) * POOL, POOL * sizeof(float));
        }
    }
    saveClipCache(text, chunks, out);
    LOGI("SdxlModel: encoded prompt as %zu chunk(s)", chunks);
    return out;
}

std::vector<std::vector<float>> SdxlModel::runUnetInputs(QnnGraphRunner& g, const std::vector<float>& latent,
        int timestep, const std::vector<float>& context, const std::vector<float>& pooled,
        const std::vector<float>& timeIds, size_t activeChunks) {

    auto descs = g.inputs();
    std::vector<std::vector<float>> in(descs.size());
    for (size_t k = 0; k < descs.size(); ++k) {
        const std::string& nm = descs[k].name;
        size_t n = 1; for (auto d : descs[k].dims) n *= static_cast<size_t>(std::max<int64_t>(d, 1));
        if      (nm.find("encoder_hidden_states") != std::string::npos) in[k] = context;
        else if (nm.find("encoder_attention_mask") != std::string::npos) { in[k].resize(n); std::fill_n(in[k].begin(), std::min(n, activeChunks * TOKENS), 1.0f); }
        else if (nm.find("text_embeds")           != std::string::npos) in[k] = pooled;
        else if (nm.find("time_ids")              != std::string::npos) in[k] = timeIds;
        else if (nm.find("sample")                != std::string::npos) in[k] = latent;
        else if (nm.find("timestamp")             != std::string::npos || nm.find("timestep") != std::string::npos)
            in[k] = std::vector<float>(std::max<size_t>(n, 1), static_cast<float>(timestep));
        else {

            if      (n == context.size())             in[k] = context;
            else if (n == latent.size())              in[k] = latent;
            else if (n == static_cast<size_t>(POOL)) in[k] = pooled;
            else if (n == static_cast<size_t>(TIMEIDS)) in[k] = timeIds;
            else in[k] = std::vector<float>(std::max<size_t>(n, 1), static_cast<float>(timestep));
        }
    }
    std::vector<std::vector<float>> out;
    if (!g.execute(in, out)) { LOGE("sdxl unet: execute failed"); return {}; }
    return out;
}

std::vector<float> SdxlModel::unet(const std::vector<float>& latent, int timestep,
                                   const std::vector<float>& context,
                                   const std::vector<float>& pooled,
                                   const std::vector<float>& timeIds, size_t activeChunks) {
    if (context.empty() || context.size() % static_cast<size_t>(CTX) != 0 || pooled.size() != POOL) return {};
    const size_t chunks = context.size() / static_cast<size_t>(CTX);
    if (!ensureUnet(chunks)) return {};
    auto out = runUnetInputs(unet_, latent, timestep, context, pooled, timeIds, activeChunks);
    if (out.empty()) { LOGE("sdxl unet: no output"); return {}; }
    return out[0];
}

std::vector<float> SdxlModel::vaeDecode(const std::vector<float>& latent) {
    if (unetReady_) { releaseUnet(); LOGI("SdxlModel: UNet released (lowram), loading VAE"); }
    std::vector<float> scaled(latent.size());
    for (size_t i = 0; i < latent.size(); ++i) scaled[i] = latent[i] / VAE_SCALE;

    if (!vaeReady_) {
        if (!vae_.loadContext(dir_ + "/vae_decoder.bin")) { LOGE("sdxl vae: load failed"); return {}; }
        LOGI("SdxlModel: VAE I/O ↓"); vae_.logIo();
        vaeReady_ = true;
    }
    std::vector<std::vector<float>> out;
    if (!vae_.execute({scaled}, out) || out.empty()) { LOGE("sdxl vae: execute failed"); return {}; }
    std::vector<float>& px = out[0];
    for (auto& v : px) v = (v * 0.5f + 0.5f) * 255.0f;
    return px;
}

std::vector<float> SdxlModel::vaeEncode(const std::vector<float>& imageNorm) {
    if (clipReady_) { freeClip(); LOGI("SdxlModel: CLIP released (lowram), loading VAE encoder"); }
    if (unetReady_) { releaseUnet(); LOGI("SdxlModel: UNet released (lowram), loading VAE encoder"); }
    if (vaeReady_) { vae_.freeContext(); vaeReady_ = false; LOGI("SdxlModel: VAE decoder released (lowram), loading encoder"); }
    if (!encReady_) {
        if (!vaeEnc_.loadContext(dir_ + "/vae_encoder.bin")) { LOGE("sdxl vaeEncode: no encoder"); return {}; }
        LOGI("SdxlModel: VAE-ENC I/O ↓"); vaeEnc_.logIo();
        encReady_ = true;
    }
    std::vector<std::vector<float>> out;
    if (!vaeEnc_.execute({imageNorm}, out) || out.size() < 2) { LOGE("sdxl vaeEncode: execute failed"); return {}; }
    auto descs = vaeEnc_.outputs();
    int meanIdx = 0, stdIdx = 1;
    for (size_t k = 0; k < descs.size() && k < out.size(); ++k) {
        if (descs[k].name.find("mean") != std::string::npos) meanIdx = static_cast<int>(k);
        else if (descs[k].name.find("std") != std::string::npos) stdIdx = static_cast<int>(k);
    }
    std::vector<float> res;
    res.reserve(out[meanIdx].size() + out[stdIdx].size());
    res.insert(res.end(), out[meanIdx].begin(), out[meanIdx].end());
    res.insert(res.end(), out[stdIdx].begin(), out[stdIdx].end());
    return res;
}

}
