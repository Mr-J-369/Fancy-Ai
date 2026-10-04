#pragma once

#include <cstdint>
#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace aura {

class MnnSd15Model {
public:
    MnnSd15Model();
    ~MnnSd15Model();

    MnnSd15Model(const MnnSd15Model&) = delete;
    MnnSd15Model& operator=(const MnnSd15Model&) = delete;

    bool load(const std::string& directory);
    void setBackend(int backend);
    void setMemoryPolicy(int policy);
    void finishRequest();
    [[nodiscard]] size_t textChunkCount(const std::string& text) const;
    std::vector<float> encodeText(const std::string& text, size_t minimumChunks = 1);
    std::vector<float> unet(
        const std::vector<float>& latent,
        int32_t timestep,
        const std::vector<float>& context);
    std::vector<float> unetGuided(
        const std::vector<float>& latent,
        int32_t timestep,
        const std::vector<float>& conditional,
        const std::vector<float>& unconditional,
        float guidance);
    std::vector<float> vaeDecode(const std::vector<float>& latent);
    std::vector<float> vaeEncodeRgba(
        const uint8_t* pixels,
        int width,
        int height,
        int stride);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace aura
