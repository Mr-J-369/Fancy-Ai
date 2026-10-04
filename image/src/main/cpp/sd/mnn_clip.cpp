#include "mnn_clip.h"
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <memory>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>

#if FANCY_INTEGRITY_REQUIRED
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "fancyclip", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "fancyclip", __VA_ARGS__)
#endif

namespace aura {

namespace {

bool allFinite(const std::vector<float>& values, const char* output) {
    const bool valid = std::all_of(values.begin(), values.end(), [](float value) {
        return std::isfinite(value);
    });
    if (!valid) {
        LOGE("clip: non-finite value in %s", output);
    }
    return valid;
}

}  // namespace

static inline float h2f(uint16_t h) {
    auto s = static_cast<uint32_t>(h & 0x8000) << 16;
    auto e = static_cast<uint32_t>(h >> 10) & 0x1F;
    auto m = static_cast<uint32_t>(h & 0x3FF);
    uint32_t out;
    if (e == 0) {
        if (m == 0) out = s;
        else { e = 127 - 15 + 1; while (!(m & 0x400)) { m <<= 1; e--; } m &= 0x3FF; out = s | (e << 23) | (m << 13); }
    } else if (e == 31) {
        out = s | 0x7F800000u | (m << 13);
    } else {
        out = s | ((e - 15 + 127) << 23) | (m << 13);
    }
    float f; std::memcpy(&f, &out, 4); return f;
}

static const void* mapFileRO(const std::string& path, void*& mapOut, size_t& bytesOut) {
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return nullptr;
    off_t sz = lseek(fd, 0, SEEK_END); lseek(fd, 0, SEEK_SET);
    if (sz <= 0) { close(fd); return nullptr; }
    void* m = mmap(nullptr, static_cast<size_t>(sz), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return nullptr;
    mapOut = m; bytesOut = static_cast<size_t>(sz); return m;
}

bool MnnClip::loadAs(const std::string& dir, const std::string& mnnFile,
                     const std::string& tokenEmbFile, const std::string& posEmbFile,
                     int dim, bool wantPooled) {
    // A failed load can leave the first mmap standing (token_emb ok, pos_emb missing);
    // retrying would then overwrite the pointer and leak the mapping.
    free();
    dim_ = dim; wantPooled_ = wantPooled; mnnFile_ = mnnFile;
    if (!mapFileRO(dir + "/" + tokenEmbFile, tokenEmbMap_, tokenEmbBytes_)) {
        LOGE("clip(%s): mmap token_emb failed (%s)", mnnFile.c_str(), tokenEmbFile.c_str()); return false;
    }
    if (!mapFileRO(dir + "/" + posEmbFile, posEmbMap_, posEmbBytes_)) {
        LOGE("clip(%s): mmap pos_emb failed (%s)", mnnFile.c_str(), posEmbFile.c_str());
        free();
        return false;
    }
    tokenEmbBase_ = tokenEmbMap_;
    posEmbBase_   = posEmbMap_;

    auto isF32 = [](size_t bytes, size_t expectElems) -> bool {
        long f32 = static_cast<long>(bytes / 4), f16 = static_cast<long>(bytes / 2), e = static_cast<long>(expectElems);
        return std::labs(f32 - e) <= std::labs(f16 - e);
    };
    tokenEmbF32_ = isF32(tokenEmbBytes_, static_cast<size_t>(49408) * dim_);
    posEmbF32_   = isF32(posEmbBytes_,   static_cast<size_t>(seq_) * dim_);

    interp_ = MNN::Interpreter::createFromFile((dir + "/" + mnnFile).c_str());
    if (!interp_) {
        LOGE("clip(%s): createFromFile failed", mnnFile.c_str());
        free();
        return false;
    }
    // Memory_Low keeps int8/int4 weights packed and dequantizes per block in-kernel.
    // Without it MNN expands them back to float at session build and SDXL's bigG
    // encoder costs 1.26 GB instead of the 635 MB its weight file actually holds.
    MNN::ScheduleConfig cfg; cfg.type = MNN_FORWARD_CPU; cfg.numThread = 4;
    MNN::BackendConfig bk; bk.memory = MNN::BackendConfig::Memory_Low;
    cfg.backendConfig = &bk;
    session_ = reinterpret_cast<MNN::Interpreter*>(interp_)->createSession(cfg);
    if (!session_) {
        LOGE("clip(%s): createSession failed", mnnFile.c_str());
        free();
        return false;
    }
    LOGI("clip(%s): loaded dim=%d pooled=%d (tokenEmb=%zu[%s] posEmb=%zu[%s], mmap)",
         mnnFile.c_str(), dim_, static_cast<int>(wantPooled_),
         tokenEmbBytes_ / (tokenEmbF32_ ? 4 : 2), tokenEmbF32_ ? "f32" : "f16",
         posEmbBytes_ / (posEmbF32_ ? 4 : 2), posEmbF32_ ? "f32" : "f16");
    return true;
}

bool MnnClip::load(const std::string& dir) {
    return loadAs(dir, "clip_v2.mnn", "token_emb.bin", "pos_emb.bin", 768, false);
}

std::vector<float> MnnClip::buildInputEmb(const std::vector<int32_t>& ids) const {
    std::vector<float> emb((size_t)seq_ * dim_);
    const size_t tokRows = dim_ > 0 ? tokenEmbBytes_ / ((tokenEmbF32_ ? 4u : 2u) * (size_t)dim_) : 0;
    for (int t = 0; t < seq_; ++t) {
        int id = (t < (int)ids.size()) ? ids[t] : 0;
        if (id < 0 || (size_t)id >= tokRows) id = 0;
        const size_t toff = (size_t)id * dim_;
        const size_t poff = (size_t)t * dim_;
        float* dst = emb.data() + (size_t)t * dim_;
        for (int d = 0; d < dim_; ++d) {
            float tv = tokenEmbF32_ ? static_cast<const float*>(tokenEmbBase_)[toff + d]
                                    : h2f(static_cast<const uint16_t*>(tokenEmbBase_)[toff + d]);
            float pv = posEmbF32_   ? static_cast<const float*>(posEmbBase_)[poff + d]
                                    : h2f(static_cast<const uint16_t*>(posEmbBase_)[poff + d]);
            dst[d] = tv + pv;
        }
    }
    return emb;
}

std::vector<float> MnnClip::encode(const std::vector<int32_t>& ids) const {
    auto* interp = reinterpret_cast<MNN::Interpreter*>(interp_);
    auto* sess = reinterpret_cast<MNN::Session*>(session_);
    if (!interp || !sess) return {};
    auto emb = buildInputEmb(ids);
    auto* in = interp->getSessionInput(sess, nullptr);
    if (!in || in->elementSize() != static_cast<int>(emb.size())) {
        LOGE("clip: input tensor is missing or has the wrong shape");
        return {};
    }
    auto host = std::shared_ptr<MNN::Tensor>(MNN::Tensor::create<float>(in->shape(), emb.data(), MNN::Tensor::CAFFE));
    if (!host || !in->copyFromHostTensor(host.get())) {
        LOGE("clip: input copy failed");
        return {};
    }
    const auto code = interp->runSession(sess);
    if (code != MNN::NO_ERROR) {
        LOGE("clip: runSession failed: %d", static_cast<int>(code));
        return {};
    }
    auto* out = interp->getSessionOutput(sess, nullptr);
    if (!out) {
        LOGE("clip: output tensor is missing");
        return {};
    }
    auto outHost = std::shared_ptr<MNN::Tensor>(MNN::Tensor::create<float>(out->shape(), nullptr, MNN::Tensor::CAFFE));

    if (!outHost || !outHost->host<float>()) { LOGE("clip: output staging alloc failed — out of memory?"); return {}; }
    if (!out->copyToHostTensor(outHost.get())) { LOGE("clip: output copy failed"); return {}; }
    int n = outHost->elementSize();
    std::vector<float> result(n);
    std::memcpy(result.data(), outHost->host<float>(), (size_t)n * sizeof(float));
    return allFinite(result, "hidden state") ? result : std::vector<float>{};
}

bool MnnClip::encodePooled(const std::vector<int32_t>& ids,
                           std::vector<float>& hidden, std::vector<float>& pooledAllTokens) const {
    auto* interp = reinterpret_cast<MNN::Interpreter*>(interp_);
    auto* sess = reinterpret_cast<MNN::Session*>(session_);
    if (!interp || !sess) return false;
    auto emb = buildInputEmb(ids);
    auto* in = interp->getSessionInput(sess, nullptr);
    if (!in || in->elementSize() != static_cast<int>(emb.size())) {
        LOGE("clip2: input tensor is missing or has the wrong shape");
        return false;
    }
    auto host = std::shared_ptr<MNN::Tensor>(MNN::Tensor::create<float>(in->shape(), emb.data(), MNN::Tensor::CAFFE));
    if (!host || !in->copyFromHostTensor(host.get())) {
        LOGE("clip2: input copy failed");
        return false;
    }
    const auto code = interp->runSession(sess);
    if (code != MNN::NO_ERROR) {
        LOGE("clip2: runSession failed: %d", static_cast<int>(code));
        return false;
    }

    auto pull = [&](const char* name, std::vector<float>& dst) -> bool {
        auto* o = interp->getSessionOutput(sess, name);
        if (!o) { LOGE("clip2: output '%s' missing", name); return false; }
        auto oh = std::shared_ptr<MNN::Tensor>(MNN::Tensor::create<float>(o->shape(), nullptr, MNN::Tensor::CAFFE));
        if (!oh || !oh->host<float>()) { LOGE("clip2: staging alloc for '%s' failed — out of memory?", name); return false; }
        if (!o->copyToHostTensor(oh.get())) { LOGE("clip2: output copy for '%s' failed", name); return false; }
        int n = oh->elementSize(); dst.resize(n);
        std::memcpy(dst.data(), oh->host<float>(), (size_t)n * sizeof(float));
        return allFinite(dst, name);
    };
    return pull("last_hidden_state", hidden) && pull("pooled_output", pooledAllTokens);
}

void MnnClip::free() {
    if (interp_) { MNN::Interpreter::destroy(reinterpret_cast<MNN::Interpreter*>(interp_)); interp_ = nullptr; }
    session_ = nullptr;
    if (tokenEmbMap_) { munmap(tokenEmbMap_, tokenEmbBytes_); tokenEmbMap_ = nullptr; tokenEmbBytes_ = 0; }
    if (posEmbMap_)   { munmap(posEmbMap_, posEmbBytes_);     posEmbMap_   = nullptr; posEmbBytes_   = 0; }
    tokenEmbBase_ = nullptr; posEmbBase_ = nullptr;
}

MnnClip::~MnnClip() { free(); }

}
