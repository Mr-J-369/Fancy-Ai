#pragma once
#include "clip_tokenizer.h"
#include "mnn_clip.h"
#include "qnn_backend.h"
#include <string>
#include <vector>

namespace aura {

class SdModel {
public:
    bool load(const std::string& dir, const std::string& libDir, const std::string& skelDir,
              int width, int height);
    [[nodiscard]] std::vector<float> encodeText(const std::string& text);
    [[nodiscard]] std::vector<float> unet(const std::vector<float>& latent, int timestep,
                            const std::vector<float>& textEmb);
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
