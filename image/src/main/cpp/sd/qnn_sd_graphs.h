#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "file_backed_buffer.h"
#include "safetensor2mnn/SafeTensorReader.hpp"

namespace aura {

using QnnConvertProgressFn = std::function<void(int percent, const std::string& message)>;

std::vector<float> applyQnnLora(
    const std::vector<float>& originalWeights,
    const std::string& weightName,
    const std::vector<SafeTensorReader*>& loras,
    const std::vector<float>& loraStrengths,
    const std::unordered_map<std::string, std::string>& mapping);

struct QnnBuilderBackend {
    void* backendLib = nullptr;
    void* logHandle = nullptr;
    void* backendHandle = nullptr;
    void* deviceHandle = nullptr;
    struct Impl;
    Impl* impl = nullptr;

    bool init(const std::string& skelDir, std::string& errorOut);
    void shutdown();
    ~QnnBuilderBackend() { shutdown(); }
};

bool buildAndSaveSd15Unet(
    QnnBuilderBackend& backend,
    SafeTensorReader& reader,
    const std::vector<SafeTensorReader*>& loras,
    const std::vector<float>& loraStrengths,
    int width,
    int height,
    const std::string& backingDir,
    FileBackedBuffer* outBuffer,
    const std::string& outputPath,
    const std::atomic<bool>& cancelled,
    std::string& errorOut);

bool buildAndSaveSdxlUnet(
    QnnBuilderBackend& backend,
    SafeTensorReader& reader,
    const std::vector<SafeTensorReader*>& loras,
    const std::vector<float>& loraStrengths,
    const std::string& outputPath,
    const std::atomic<bool>& cancelled,
    std::string& errorOut);

bool buildAndSaveVaeDecoder(
    QnnBuilderBackend& backend,
    SafeTensorReader& reader,
    int latentH,
    int latentW,
    bool nchwIo,
    const std::string& outputPath,
    const std::atomic<bool>& cancelled,
    std::string& errorOut);

bool buildAndSaveVaeEncoder(
    QnnBuilderBackend& backend,
    SafeTensorReader& reader,
    int imageH,
    int imageW,
    bool nchwIo,
    const std::string& outputPath,
    const std::atomic<bool>& cancelled,
    std::string& errorOut);

}  // namespace aura
