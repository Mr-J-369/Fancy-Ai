#pragma once
#include "clip_tokenizer.h"
#include "mnn_clip.h"
#include "qnn_backend.h"
#include <string>
#include <vector>

namespace aura {

class SdModel {
public:
    static constexpr int TOKENS = 77;
    static constexpr int DIM = 768;
    static constexpr int EMB = TOKENS * DIM;
    static constexpr size_t MAX_CHUNKS = 3;

    bool load(const std::string& dir, const std::string& libDir, const std::string& skelDir,
              int width, int height);
    [[nodiscard]] size_t textChunkCount(const std::string& text) const;
    [[nodiscard]] size_t supportedChunkCount() const;
    [[nodiscard]] std::vector<float> encodeText(const std::string& text, size_t chunks = 1);
    [[nodiscard]] std::vector<float> unet(const std::vector<float>& latent, int timestep,
                            const std::vector<float>& textEmb, size_t activeChunks = 1);
    [[nodiscard]] std::vector<float> vaeDecode(const std::vector<float>& latent);

    [[nodiscard]] std::vector<float> vaeEncode(const std::vector<float>& imageNorm);

private:
    [[nodiscard]] bool ensureClip();
    [[nodiscard]] bool ensureUnet();
    [[nodiscard]] bool ensureVaeDecoder();
    [[nodiscard]] bool ensureVaeEncoder();

    ClipTokenizer tok_;
    MnnClip clip_;
    QnnGraphRunner unet_;
    QnnGraphRunner vae_;
    QnnGraphRunner vaeEnc_;
    std::string dir_;
    int width_ = 512;
    int height_ = 512;
    int latElems_ = 4 * 64 * 64;
};

}
