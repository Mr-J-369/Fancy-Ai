#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace aura {

struct SdxlConditioning {
    std::vector<float> context;
    std::vector<float> pooled;
};

class MnnSdxlModel {
public:
    MnnSdxlModel();
    ~MnnSdxlModel();

    MnnSdxlModel(const MnnSdxlModel&) = delete;
    MnnSdxlModel& operator=(const MnnSdxlModel&) = delete;

    bool load(const std::string& directory);
    void setBackend(int backend);
    void setMemoryPolicy(int policy);
    void finishRequest();
    [[nodiscard]] size_t textChunkCount(const std::string& text) const;
    SdxlConditioning encodeText(const std::string& text, size_t minimumChunks = 1);
    std::vector<float> unet(
        const std::vector<float>& latent,
        int32_t timestep,
        const SdxlConditioning& conditioning);
    std::vector<float> unetGuided(
        const std::vector<float>& latent,
        int32_t timestep,
        const SdxlConditioning& conditional,
        const SdxlConditioning& unconditional,
        float guidance);
    std::vector<float> vaeEncodeRgba(
        const uint8_t* pixels,
        int width,
        int height,
        int stride);
    std::vector<float> vaeDecode(const std::vector<float>& latent);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace aura
