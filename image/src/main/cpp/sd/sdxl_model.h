#pragma once
#include "clip_tokenizer.h"
#include "mnn_clip.h"
#include "qnn_backend.h"
#include <cstddef>
#include <string>
#include <vector>

namespace aura {

/** QNN-only SDXL model. */
class SdxlModel {
public:
    bool load(const std::string& dir, const std::string& libDir, const std::string& skelDir);

    [[nodiscard]] size_t textChunkCount(const std::string& text) const;
    [[nodiscard]] size_t supportedChunkCount(size_t requested) const;
    [[nodiscard]] std::vector<float> encodeText(const std::string& text, size_t chunks = 1);

    [[nodiscard]] std::vector<float> unet(const std::vector<float>& latent, int timestep,
                            const std::vector<float>& context,
                            const std::vector<float>& pooled,
                            const std::vector<float>& timeIds, size_t activeChunks);
    [[nodiscard]] std::vector<float> vaeDecode(const std::vector<float>& latent);
    [[nodiscard]] std::vector<float> vaeEncode(const std::vector<float>& imageNorm);

    static constexpr int CTX   = 77 * 2048;
    static constexpr int TOKENS = 77;
    static constexpr int CONTEXT_DIM = 2048;
    static constexpr int POOL  = 1280;
    static constexpr int TIMEIDS = 6;

private:
    [[nodiscard]] static std::vector<std::vector<float>> runUnetInputs(QnnGraphRunner& g, const std::vector<float>& latent,
        int timestep, const std::vector<float>& context, const std::vector<float>& pooled,
        const std::vector<float>& timeIds, size_t activeChunks);

    ClipTokenizer tok_;
    MnnClip clip1_;
    MnnClip clip2_;
    QnnGraphRunner unet_;
    QnnGraphRunner vae_;
    QnnGraphRunner vaeEnc_;

    bool ensureUnet(size_t chunks);
    void releaseUnet();
    void releaseVae();

    bool loadClip();
    void freeClip();

    bool loadClipCache(const std::string& text, size_t chunks, std::vector<float>& out) const;
    void saveClipCache(const std::string& text, size_t chunks, const std::vector<float>& out) const;

    std::string dir_;
    std::string unetPath_;
    std::string unet154Patch_;
    std::string unet231Patch_;
    bool clipReady_ = false;
    bool unetReady_ = false;
    size_t unetChunks_ = 0;
    bool vaeReady_ = false;
    bool encReady_ = false;
};

}
