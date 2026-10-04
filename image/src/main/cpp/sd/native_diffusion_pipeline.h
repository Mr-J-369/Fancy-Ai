#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace aura::diffusion {

enum class Sampler : int {
    Euler = 0,
    EulerAncestral = 1,
    Heun = 2,
    Dpm2 = 3,
    Dpm2Ancestral = 4,
    Lms = 5,
    DpmPp2SAncestral = 6,
    DpmPp2M = 7,
    DpmPpSde = 8,
    DpmPp2MSde = 9,
    DpmPp3MSde = 10,
    Ddim = 11,
    Lcm = 12,
};

enum class Schedule : int {
    Normal = 0,
    Karras = 1,
    Exponential = 2,
    SgmUniform = 3,
    Simple = 4,
    DdimUniform = 5,
    Beta = 6,
};

struct Request {
    int width = 0;
    int height = 0;
    int steps = 0;
    int64_t seed = 0;
    float strength = 0.75f;
    Sampler sampler = Sampler::DpmPp2M;
    Schedule schedule = Schedule::Karras;
    bool vPrediction = false;
    const uint8_t* sourceRgba = nullptr;
    int sourceWidth = 0;
    int sourceHeight = 0;
    int sourceStride = 0;
    bool localDreamSd15 = false;
};

using EncodeSource = std::function<std::vector<float>(const uint8_t*, int, int, int)>;
using PredictNoise = std::function<std::vector<float>(const std::vector<float>&, int)>;
using DecodeLatent = std::function<std::vector<float>(const std::vector<float>&)>;
using Progress = std::function<void(int)>;
using Cancelled = std::function<bool()>;

/** Runs noise creation, img2img injection, every sampler step and VAE decode natively. */
[[nodiscard]] std::vector<uint8_t> generate(
    const Request& request,
    float vaeScale,
    const EncodeSource& encodeSource,
    const PredictNoise& predictNoise,
    const DecodeLatent& decodeLatent,
    const Progress& progress,
    const Cancelled& cancelled);

/** QNN VAE graphs remain 512px; these helpers reproduce the former Kotlin feathered tiling. */
[[nodiscard]] std::vector<float> encodeTiled(
    const uint8_t* rgba,
    int sourceWidth,
    int sourceHeight,
    int sourceStride,
    int outputWidth,
    int outputHeight,
    const std::function<std::vector<float>(const std::vector<float>&)>& encodeTile);

[[nodiscard]] std::vector<float> encodeTiled(
    const uint8_t* rgba,
    int sourceWidth,
    int sourceHeight,
    int sourceStride,
    int outputWidth,
    int outputHeight,
    int tilePixels,
    int overlapPixels,
    const std::function<std::vector<float>(const std::vector<float>&)>& encodeTile);

[[nodiscard]] std::vector<float> imageToChw(
    const uint8_t* rgba,
    int sourceWidth,
    int sourceHeight,
    int sourceStride,
    int outputWidth,
    int outputHeight);

[[nodiscard]] std::vector<float> decodeTiled(
    const std::vector<float>& latent,
    int width,
    int height,
    const std::function<std::vector<float>(const std::vector<float>&)>& decodeTile);

[[nodiscard]] std::vector<float> decodeTiled(
    const std::vector<float>& latent,
    int width,
    int height,
    int tilePixels,
    int overlapPixels,
    const std::function<std::vector<float>(const std::vector<float>&)>& decodeTile);

}  // namespace aura::diffusion
