#include "qnn_sd_graphs.h"
#include "file_backed_buffer.h"

#include "HTP/QnnHtpContext.h"
#include "HTP/QnnHtpGraph.h"
#include "QnnContext.h"
#include "QnnDevice.h"
#include "QnnGraph.h"
#include "QnnInterface.h"
#include "QnnLog.h"
#include "QnnOpDef.h"
#include "QnnTensor.h"
#include "QnnTypes.h"
#include "safetensor2mnn/FloatConversion.hpp"
#include "safetensor2mnn/LoraMapping.hpp"
#include "safetensor2mnn/SDXLLoraMapping.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <malloc.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <memory>
#include <unordered_map>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "fancyqnnconv", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "fancyqnnconv", __VA_ARGS__)

namespace aura {

struct QnnBuilderBackend::Impl {
    QNN_INTERFACE_VER_TYPE qnn{};
};

namespace {

using GetQnnProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);

std::string findUnetPrefix(const SafeTensorReader& reader) {
    if (reader.has_tensor("model.diffusion_model.input_blocks.0.0.weight")) {
        return "model.diffusion_model.";
    }
    if (reader.has_tensor("input_blocks.0.0.weight")) {
        return "";
    }
    return "model.diffusion_model.";
}

std::string findVaePrefix(const SafeTensorReader& reader) {
    if (reader.has_tensor("first_stage_model.decoder.conv_in.weight")) {
        return "first_stage_model.";
    }
    if (reader.has_tensor("decoder.conv_in.weight")) {
        return "";
    }
    return "first_stage_model.";
}

void quantizeSymmetricPerAxis(
    const std::vector<float>& weights,
    const std::vector<int>& dims,
    int quantAxis,
    std::vector<int8_t>& outQuant,
    std::vector<Qnn_ScaleOffset_t>& outScales) {
    const int rank = static_cast<int>(dims.size());
    const int numChannels = dims[quantAxis];
    outQuant.resize(weights.size());
    outScales.resize(static_cast<size_t>(numChannels));

    size_t outer = 1;
    for (int i = 0; i < quantAxis; ++i) outer *= static_cast<size_t>(dims[i]);
    size_t inner = 1;
    for (int i = quantAxis + 1; i < rank; ++i) inner *= static_cast<size_t>(dims[i]);

    std::vector<float> maxAbs(static_cast<size_t>(numChannels), 0.0f);
    for (size_t o = 0; o < outer; ++o) {
        const size_t baseO = o * static_cast<size_t>(numChannels) * inner;
        for (int c = 0; c < numChannels; ++c) {
            const float* src = weights.data() + baseO + static_cast<size_t>(c) * inner;
            float m = maxAbs[static_cast<size_t>(c)];
            for (size_t i = 0; i < inner; ++i) {
                m = std::max(m, std::abs(src[i]));
            }
            maxAbs[static_cast<size_t>(c)] = m;
        }
    }

    for (int c = 0; c < numChannels; ++c) {
        const float scale = std::max(maxAbs[static_cast<size_t>(c)] / 127.0f, 1e-6f);
        outScales[static_cast<size_t>(c)] = {scale, 0};
    }

    for (size_t o = 0; o < outer; ++o) {
        const size_t baseO = o * static_cast<size_t>(numChannels) * inner;
        for (int c = 0; c < numChannels; ++c) {
            const float invScale = 1.0f / outScales[static_cast<size_t>(c)].scale;
            const size_t offset = baseO + static_cast<size_t>(c) * inner;
            const float* src = weights.data() + offset;
            int8_t* dst = outQuant.data() + offset;
            for (size_t i = 0; i < inner; ++i) {
                const long q = std::lround(src[i] * invScale);
                dst[i] = static_cast<int8_t>(std::clamp<long>(q, -127L, 127L));
            }
        }
    }
}

std::vector<float> transposeOihwToHwio(const std::vector<float>& oihw, int oc, int ic, int kh, int kw) {
    std::vector<float> hwio(static_cast<size_t>(kh) * kw * ic * oc);
    for (int o = 0; o < oc; ++o) {
        for (int i = 0; i < ic; ++i) {
            for (int r = 0; r < kh; ++r) {
                for (int s = 0; s < kw; ++s) {
                    const size_t srcIdx = ((static_cast<size_t>(o) * ic + i) * kh + r) * kw + s;
                    const size_t dstIdx = ((static_cast<size_t>(r) * kw + s) * ic + i) * oc + o;
                    hwio[dstIdx] = oihw[srcIdx];
                }
            }
        }
    }
    return hwio;
}

class QnnGraphBuilder {
public:
    QnnGraphBuilder(
        const QNN_INTERFACE_VER_TYPE& qnn,
        Qnn_ContextHandle_t context,
        Qnn_GraphHandle_t graph,
        SafeTensorReader& reader,
        const std::vector<SafeTensorReader*>& loras,
        const std::vector<float>& loraStrengths,
        const std::unordered_map<std::string, std::string>* loraMap,
        const std::atomic<bool>& cancelled)
        : qnn_(qnn),
          context_(context),
          graph_(graph),
          reader_(reader),
          loras_(loras),
          loraStrengths_(loraStrengths),
          loraMap_(loraMap),
          cancelled_(cancelled) {}

    bool aborted() const { return !ok_ || cancelled_.load(); }
    const std::string& error() const { return error_; }

    std::string uniq(const std::string& base) {
        return base + "_" + std::to_string(nextId_++);
    }

    std::string addInput(const std::string& name, const std::vector<uint32_t>& dims) {
        Qnn_Tensor_t t = QNN_TENSOR_INIT;
        initTensorHeader(t, name, QNN_TENSOR_TYPE_APP_WRITE, QNN_DATATYPE_FLOAT_16, dims);
        if (!createGraphTensor(t)) return {};
        return name;
    }

    std::string staticFp16(const std::string& name, const std::vector<uint32_t>& dims, const std::vector<float>& floats) {
        std::vector<uint16_t> fp16(floats.size());
        for (size_t i = 0; i < floats.size(); ++i) {
            fp16[i] = fp32_to_fp16(floats[i]);
        }
        Qnn_Tensor_t t = QNN_TENSOR_INIT;
        initTensorHeader(t, name, QNN_TENSOR_TYPE_STATIC, QNN_DATATYPE_FLOAT_16, dims);
        t.v1.memType = QNN_TENSORMEMTYPE_RAW;
        t.v1.clientBuf = {fp16.data(), static_cast<uint32_t>(fp16.size() * sizeof(uint16_t))};
        if (!createGraphTensor(t)) return {};
        return name;
    }

    std::string staticInt8PerAxis(
        const std::string& name,
        const std::vector<uint32_t>& dims,
        int quantAxis,
        const std::vector<int8_t>& quant,
        std::vector<Qnn_ScaleOffset_t> scales) {
        Qnn_Tensor_t t = QNN_TENSOR_INIT;
        initTensorHeader(t, name, QNN_TENSOR_TYPE_STATIC, QNN_DATATYPE_SFIXED_POINT_8, dims);
        scalesPool_.push_back(std::move(scales));
        auto& savedScales = scalesPool_.back();
        t.v1.quantizeParams.encodingDefinition = QNN_DEFINITION_DEFINED;
        t.v1.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET;
        t.v1.quantizeParams.axisScaleOffsetEncoding.axis = quantAxis;
        t.v1.quantizeParams.axisScaleOffsetEncoding.numScaleOffsets = static_cast<uint32_t>(savedScales.size());
        t.v1.quantizeParams.axisScaleOffsetEncoding.scaleOffset = savedScales.data();
        t.v1.memType = QNN_TENSORMEMTYPE_RAW;
        t.v1.clientBuf = {const_cast<int8_t*>(quant.data()), static_cast<uint32_t>(quant.size())};
        if (!createGraphTensor(t)) return {};
        return name;
    }

    std::string addActivation(
        const std::string& name,
        const std::vector<uint32_t>& dims,
        Qnn_TensorType_t tensorType = QNN_TENSOR_TYPE_NATIVE) {
        Qnn_Tensor_t t = QNN_TENSOR_INIT;
        initTensorHeader(t, name, tensorType, QNN_DATATYPE_FLOAT_16, dims);
        if (!createGraphTensor(t)) return {};
        return name;
    }

    std::vector<float> loadWeight(const std::string& key, int expectedSize) {
        if (!reader_.read(key, true)) {
            fail("Missing weight tensor: " + key);
            return {};
        }
        std::vector<float> w = std::move(reader_.data);
        reader_.clearBuffers();
        if (expectedSize > 0 && static_cast<int>(w.size()) != expectedSize) {
            fail("Unexpected element count for " + key + ": got " + std::to_string(w.size()) +
                 ", expected " + std::to_string(expectedSize));
            return {};
        }
        if (loraMap_ != nullptr && !loras_.empty()) {
            w = applyQnnLora(w, key, loras_, loraStrengths_, *loraMap_);
        }
        return w;
    }

    std::vector<float> loadBiasOrNorm(const std::string& key, int expectedSize, float fillDefault) {
        if (!key.empty() && reader_.has_tensor(key)) {
            if (!reader_.read(key, true)) {
                fail("Failed reading tensor: " + key);
                return {};
            }
            std::vector<float> v = std::move(reader_.data);
            reader_.clearBuffers();
            if (static_cast<int>(v.size()) != expectedSize) {
                v.resize(static_cast<size_t>(expectedSize), fillDefault);
            }
            return v;
        }
        return std::vector<float>(static_cast<size_t>(expectedSize), fillDefault);
    }

    std::string conv2d(
        const std::string& input,
        const std::string& weightKey,
        const std::string& biasKey,
        int oc, int ic, int kh, int kw,
        int outH, int outW,
        int strideH, int strideW,
        int padTop, int padBottom, int padLeft, int padRight,
        const std::string& prefix,
        const std::string& explicitOutName = "",
        Qnn_TensorType_t outType = QNN_TENSOR_TYPE_NATIVE) {
        if (aborted()) return {};
        std::vector<float> w = loadWeight(weightKey, oc * ic * kh * kw);
        if (aborted()) return {};
        std::vector<float> hwio = transposeOihwToHwio(w, oc, ic, kh, kw);
        w.clear();
        w.shrink_to_fit();

        const std::string wName = uniq(prefix + "_w");
        const std::vector<uint32_t> wDims{
            static_cast<uint32_t>(kh), static_cast<uint32_t>(kw), static_cast<uint32_t>(ic), static_cast<uint32_t>(oc)};
        // HTP FP16 W8A16 Conv2d restricts SFIXED_POINT_8 weights to 1x1 kernels with ic % 4 == 0.
        if (kh == 1 && kw == 1 && (ic % 4 == 0)) {
            std::vector<int8_t> quant;
            std::vector<Qnn_ScaleOffset_t> scales;
            quantizeSymmetricPerAxis(hwio, {kh, kw, ic, oc}, 3, quant, scales);
            hwio.clear();
            hwio.shrink_to_fit();

            if (staticInt8PerAxis(wName, wDims, 3, quant, std::move(scales)).empty()) {
                return {};
            }
            quant.clear();
            quant.shrink_to_fit();
        } else {
            if (staticFp16(wName, wDims, hwio).empty()) {
                return {};
            }
            hwio.clear();
            hwio.shrink_to_fit();
        }

        std::vector<std::string> inNames{input, wName};
        if (!biasKey.empty() && reader_.has_tensor(biasKey)) {
            std::vector<float> bias = loadBiasOrNorm(biasKey, oc, 0.0f);
            if (aborted()) return {};
            const std::string bName = uniq(prefix + "_b");
            if (staticFp16(bName, {static_cast<uint32_t>(oc)}, bias).empty()) return {};
            inNames.push_back(bName);
        }

        const std::string outName = explicitOutName.empty() ? uniq(prefix + "_out") : explicitOutName;
        if (addActivation(outName, {1, static_cast<uint32_t>(outH), static_cast<uint32_t>(outW), static_cast<uint32_t>(oc)}, outType).empty()) {
            return {};
        }

        uint32_t strideVals[2] = {static_cast<uint32_t>(strideH), static_cast<uint32_t>(strideW)};
        uint32_t padVals[4] = {
            static_cast<uint32_t>(padTop), static_cast<uint32_t>(padBottom),
            static_cast<uint32_t>(padLeft), static_cast<uint32_t>(padRight)};
        uint32_t dilationVals[2] = {1, 1};

        Qnn_Param_t params[4];
        params[0] = makeTensorParam(prefix + "_stride", QNN_OP_CONV_2D_PARAM_STRIDE, {2}, strideVals);
        params[1] = makeTensorParam(prefix + "_pad", QNN_OP_CONV_2D_PARAM_PAD_AMOUNT, {2, 2}, padVals);
        params[2] = makeTensorParam(prefix + "_dil", QNN_OP_CONV_2D_PARAM_DILATION, {2}, dilationVals);
        params[3] = makeScalarUint32(QNN_OP_CONV_2D_PARAM_GROUP, 1);

        if (!addNode(prefix, QNN_OP_CONV_2D, params, 4, inNames, {outName})) return {};
        return outName;
    }

    std::string fullyConnected(
        const std::string& input,
        const std::string& weightKey,
        const std::string& biasKey,
        int m,
        int n,
        uint32_t batch,
        const std::string& prefix,
        bool is3D = false,
        uint32_t seqLen = 0) {
        if (aborted()) return {};
        std::string cur = input;
        if (is3D) {
            cur = reshape(cur, {seqLen, static_cast<uint32_t>(n)}, prefix + "_flat");
            if (aborted()) return {};
        }
        std::vector<float> w = loadWeight(weightKey, m * n);
        if (aborted()) return {};
        std::vector<int8_t> quant;
        std::vector<Qnn_ScaleOffset_t> scales;
        quantizeSymmetricPerAxis(w, {m, n}, 0, quant, scales);
        w.clear();
        w.shrink_to_fit();

        const std::string wName = uniq(prefix + "_w");
        if (staticInt8PerAxis(wName, {static_cast<uint32_t>(m), static_cast<uint32_t>(n)}, 0, quant, std::move(scales)).empty()) {
            return {};
        }
        quant.clear();
        quant.shrink_to_fit();

        std::vector<std::string> inNames{cur, wName};
        if (!biasKey.empty() && reader_.has_tensor(biasKey)) {
            std::vector<float> bias = loadBiasOrNorm(biasKey, m, 0.0f);
            if (aborted()) return {};
            const std::string bName = uniq(prefix + "_b");
            if (staticFp16(bName, {static_cast<uint32_t>(m)}, bias).empty()) return {};
            inNames.push_back(bName);
        }

        const uint32_t effectiveBatch = is3D ? seqLen : batch;
        const std::string fcOut = uniq(prefix + "_fc");
        if (addActivation(fcOut, {effectiveBatch, static_cast<uint32_t>(m)}).empty()) return {};
        if (!addNode(prefix, QNN_OP_FULLY_CONNECTED, nullptr, 0, inNames, {fcOut})) return {};
        if (is3D) {
            return reshape(fcOut, {1, seqLen, static_cast<uint32_t>(m)}, prefix + "_unflat");
        }
        return fcOut;
    }

    std::string groupNorm(
        const std::string& input,
        const std::string& gammaKey,
        const std::string& betaKey,
        const std::vector<uint32_t>& dims,
        uint32_t groups,
        float epsilon,
        const std::string& prefix) {
        if (aborted()) return {};
        const int c = static_cast<int>(dims.back());
        const auto gamma = loadBiasOrNorm(gammaKey, c, 1.0f);
        const auto beta = loadBiasOrNorm(betaKey, c, 0.0f);
        if (aborted()) return {};
        const std::string gName = uniq(prefix + "_g");
        const std::string bName = uniq(prefix + "_b");
        if (staticFp16(gName, {static_cast<uint32_t>(c)}, gamma).empty()) return {};
        if (staticFp16(bName, {static_cast<uint32_t>(c)}, beta).empty()) return {};
        const std::string outName = uniq(prefix + "_out");
        if (addActivation(outName, dims).empty()) return {};

        Qnn_Param_t params[2];
        params[0] = makeScalarFloat32(QNN_OP_GROUP_NORM_PARAM_EPSILON, epsilon);
        params[1] = makeScalarUint32(QNN_OP_GROUP_NORM_PARAM_GROUP, groups);
        if (!addNode(prefix, QNN_OP_GROUP_NORM, params, 2, {input, gName, bName}, {outName})) return {};
        return outName;
    }

    std::string layerNorm(
        const std::string& input,
        const std::string& gammaKey,
        const std::string& betaKey,
        const std::vector<uint32_t>& dims,
        float epsilon,
        const std::string& prefix) {
        if (aborted()) return {};
        const int c = static_cast<int>(dims.back());
        const auto gamma = loadBiasOrNorm(gammaKey, c, 1.0f);
        const auto beta = loadBiasOrNorm(betaKey, c, 0.0f);
        if (aborted()) return {};
        const std::string gName = uniq(prefix + "_g");
        const std::string bName = uniq(prefix + "_b");
        if (staticFp16(gName, {static_cast<uint32_t>(c)}, gamma).empty()) return {};
        if (staticFp16(bName, {static_cast<uint32_t>(c)}, beta).empty()) return {};
        const std::string outName = uniq(prefix + "_out");
        if (addActivation(outName, dims).empty()) return {};

        uint32_t axisVal[1] = {static_cast<uint32_t>(dims.size() - 1)};
        Qnn_Param_t params[2];
        params[0] = makeScalarFloat32(QNN_OP_LAYER_NORM_PARAM_EPSILON, epsilon);
        params[1] = makeTensorParam(prefix + "_axes", QNN_OP_LAYER_NORM_PARAM_AXES, {1}, axisVal);
        if (!addNode(prefix, QNN_OP_LAYER_NORM, params, 2, {input, gName, bName}, {outName})) return {};
        return outName;
    }

    std::string unary(const std::string& input, const char* opType, const std::vector<uint32_t>& dims, const std::string& prefix,
                      const std::string& explicitOutName = "", Qnn_TensorType_t outType = QNN_TENSOR_TYPE_NATIVE) {
        if (aborted()) return {};
        const std::string outName = explicitOutName.empty() ? uniq(prefix) : explicitOutName;
        if (addActivation(outName, dims, outType).empty()) return {};
        if (!addNode(prefix, opType, nullptr, 0, {input}, {outName})) return {};
        return outName;
    }

    std::string binary(const std::string& a, const std::string& b, const char* opType, const std::vector<uint32_t>& outDims,
                       const std::string& prefix, const std::string& explicitOutName = "",
                       Qnn_TensorType_t outType = QNN_TENSOR_TYPE_NATIVE) {
        if (aborted()) return {};
        const std::string outName = explicitOutName.empty() ? uniq(prefix) : explicitOutName;
        if (addActivation(outName, outDims, outType).empty()) return {};
        if (!addNode(prefix, opType, nullptr, 0, {a, b}, {outName})) return {};
        return outName;
    }

    std::string silu(const std::string& input, const std::vector<uint32_t>& dims, const std::string& prefix) {
        const std::string sig = unary(input, QNN_OP_SIGMOID, dims, prefix + "_sig");
        return binary(input, sig, QNN_OP_ELEMENT_WISE_MULTIPLY, dims, prefix + "_mul");
    }

    std::string reshape(const std::string& input, const std::vector<uint32_t>& outDims, const std::string& prefix) {
        return unary(input, QNN_OP_RESHAPE, outDims, prefix);
    }

    std::string transpose(const std::string& input, const std::vector<uint32_t>& outDims, const std::vector<uint32_t>& perm,
                          const std::string& prefix, const std::string& explicitOutName = "",
                          Qnn_TensorType_t outType = QNN_TENSOR_TYPE_NATIVE) {
        if (aborted()) return {};
        const std::string outName = explicitOutName.empty() ? uniq(prefix) : explicitOutName;
        if (addActivation(outName, outDims, outType).empty()) return {};
        Qnn_Param_t p = makeTensorParam(prefix + "_perm", QNN_OP_TRANSPOSE_PARAM_PERM, {static_cast<uint32_t>(perm.size())}, perm.data());
        if (!addNode(prefix, QNN_OP_TRANSPOSE, &p, 1, {input}, {outName})) return {};
        return outName;
    }

    std::string concat(const std::vector<std::string>& inputs, uint32_t axis, const std::vector<uint32_t>& outDims, const std::string& prefix) {
        if (aborted()) return {};
        const std::string outName = uniq(prefix);
        if (addActivation(outName, outDims).empty()) return {};
        Qnn_Param_t p = makeScalarUint32(QNN_OP_CONCAT_PARAM_AXIS, axis);
        if (!addNode(prefix, QNN_OP_CONCAT, &p, 1, inputs, {outName})) return {};
        return outName;
    }

    std::vector<std::string> split(
        const std::string& input,
        uint32_t axis,
        const std::vector<uint32_t>& splitIndices,
        const std::vector<std::vector<uint32_t>>& outDimsList,
        const std::string& prefix,
        const std::vector<std::string>& explicitOutNames = {},
        Qnn_TensorType_t outType = QNN_TENSOR_TYPE_NATIVE) {
        if (aborted()) return {};
        std::vector<std::string> outNames;
        outNames.reserve(outDimsList.size());
        for (size_t i = 0; i < outDimsList.size(); ++i) {
            const std::string name = (i < explicitOutNames.size() && !explicitOutNames[i].empty())
                ? explicitOutNames[i]
                : uniq(prefix + "_s" + std::to_string(i));
            if (addActivation(name, outDimsList[i], outType).empty()) return {};
            outNames.push_back(name);
        }
        Qnn_Param_t params[2];
        params[0] = makeScalarUint32(QNN_OP_SPLIT_PARAM_AXIS, axis);
        params[1] = makeTensorParam(prefix + "_idx", QNN_OP_SPLIT_PARAM_SPLIT_INDEX,
                                    {static_cast<uint32_t>(splitIndices.size())}, splitIndices.data());
        if (!addNode(prefix, QNN_OP_SPLIT, params, 2, {input}, outNames)) return {};
        return outNames;
    }

    std::string matmul(const std::string& a, const std::string& b, const std::vector<uint32_t>& outDims,
                       bool transposeIn0, bool transposeIn1, const std::string& prefix) {
        if (aborted()) return {};
        const std::string outName = uniq(prefix);
        if (addActivation(outName, outDims).empty()) return {};
        Qnn_Param_t params[2];
        params[0] = makeScalarBool(QNN_OP_MAT_MUL_PARAM_TRANSPOSE_IN0, transposeIn0);
        params[1] = makeScalarBool(QNN_OP_MAT_MUL_PARAM_TRANSPOSE_IN1, transposeIn1);
        if (!addNode(prefix, QNN_OP_MAT_MUL, params, 2, {a, b}, {outName})) return {};
        return outName;
    }

    std::string softmax(const std::string& input, const std::vector<uint32_t>& dims, uint32_t axis, const std::string& prefix) {
        if (aborted()) return {};
        const std::string outName = uniq(prefix);
        if (addActivation(outName, dims).empty()) return {};
        Qnn_Param_t params[2];
        params[0] = makeScalarUint32(QNN_OP_SOFTMAX_PARAM_AXIS, axis);
        params[1] = makeScalarFloat32(QNN_OP_SOFTMAX_PARAM_BETA, 1.0f);
        if (!addNode(prefix, QNN_OP_SOFTMAX, params, 2, {input}, {outName})) return {};
        return outName;
    }

    std::string resizeNearest2x(const std::string& input, uint32_t c, uint32_t h, uint32_t w, const std::string& prefix) {
        if (aborted()) return {};
        const std::string outName = uniq(prefix);
        if (addActivation(outName, {1, h * 2, w * 2, c}).empty()) return {};
        Qnn_Param_t params[2];
        params[0] = makeScalarBool(QNN_OP_RESIZE_NEAREST_NEIGHBOR_PARAM_ALIGN_CORNERS, false);
        params[1] = makeScalarBool(QNN_OP_RESIZE_NEAREST_NEIGHBOR_PARAM_HALF_PIXEL_CENTERS, false);
        if (!addNode(prefix, QNN_OP_RESIZE_NEAREST_NEIGHBOR, params, 2, {input}, {outName})) return {};
        return outName;
    }

    std::string padAsymBottomRight(const std::string& input, uint32_t c, uint32_t h, uint32_t w, const std::string& prefix) {
        if (aborted()) return {};
        const std::string outName = uniq(prefix);
        if (addActivation(outName, {1, h + 1, w + 1, c}).empty()) return {};
        uint32_t padAmounts[8] = {0, 0, 0, 1, 0, 1, 0, 0};
        Qnn_Param_t params[2];
        params[0] = makeScalarUint32(QNN_OP_PAD_PARAM_SCHEME, QNN_OP_PAD_SCHEME_CONSTANT);
        params[1] = makeTensorParam(prefix + "_amt", QNN_OP_PAD_PARAM_PAD_AMOUNT, {4, 2}, padAmounts);
        if (!addNode(prefix, QNN_OP_PAD, params, 2, {input}, {outName})) return {};
        return outName;
    }

    std::string reluMinMax(const std::string& input, const std::vector<uint32_t>& dims, float minVal, float maxVal, const std::string& prefix) {
        if (aborted()) return {};
        const std::string outName = uniq(prefix);
        if (addActivation(outName, dims).empty()) return {};
        Qnn_Param_t params[2];
        params[0] = makeScalarFloat32(QNN_OP_RELU_MIN_MAX_PARAM_MIN_VALUE, minVal);
        params[1] = makeScalarFloat32(QNN_OP_RELU_MIN_MAX_PARAM_MAX_VALUE, maxVal);
        if (!addNode(prefix, QNN_OP_RELU_MIN_MAX, params, 2, {input}, {outName})) return {};
        return outName;
    }

    std::string timestepEmbedding(const std::string& t2D, uint32_t dim, const std::string& prefix) {
        if (aborted()) return {};
        const uint32_t half = dim / 2;
        std::vector<float> freqs(half);
        const double logMax = std::log(10000.0);
        for (uint32_t i = 0; i < half; ++i) {
            freqs[i] = static_cast<float>(std::exp(-logMax * static_cast<double>(i) / static_cast<double>(half)));
        }
        const std::string fName = uniq(prefix + "_freqs");
        if (staticFp16(fName, {1, half}, freqs).empty()) return {};
        const std::string args = binary(t2D, fName, QNN_OP_ELEMENT_WISE_MULTIPLY, {1, half}, prefix + "_arg");
        const std::string c = unary(args, QNN_OP_ELEMENT_WISE_COS, {1, half}, prefix + "_cos");
        const std::string s = unary(args, QNN_OP_ELEMENT_WISE_SIN, {1, half}, prefix + "_sin");
        return concat({c, s}, 1, {1, dim}, prefix + "_emb");
    }

    std::string conv1x1OrLinear2D(
        const std::string& in4D,
        const std::string& weightKey,
        const std::string& biasKey,
        int oc,
        int ic,
        int h,
        int w,
        const std::string& prefix) {
        const auto& shape = reader_.tensor_shape(weightKey);
        if (shape.size() == 2) {
            const uint32_t seq = static_cast<uint32_t>(h * w);
            const std::string r3 = reshape(in4D, {1, seq, static_cast<uint32_t>(ic)}, prefix + "_to3d");
            const std::string fc = fullyConnected(r3, weightKey, biasKey, oc, ic, seq, prefix, true, seq);
            return reshape(fc, {1, static_cast<uint32_t>(h), static_cast<uint32_t>(w), static_cast<uint32_t>(oc)}, prefix + "_to4d");
        }
        return conv2d(in4D, weightKey, biasKey, oc, ic, 1, 1, h, w, 1, 1, 0, 0, 0, 0, prefix);
    }

private:
    const QNN_INTERFACE_VER_TYPE& qnn_;
    Qnn_ContextHandle_t context_;
    Qnn_GraphHandle_t graph_;
    SafeTensorReader& reader_;
    const std::vector<SafeTensorReader*>& loras_;
    const std::vector<float>& loraStrengths_;
    const std::unordered_map<std::string, std::string>* loraMap_;
    const std::atomic<bool>& cancelled_;

    std::deque<std::string> names_;
    std::deque<std::vector<uint32_t>> dimsPool_;
    std::deque<std::vector<Qnn_ScaleOffset_t>> scalesPool_;
    std::deque<Qnn_Tensor_t> tensorPool_;
    std::unordered_map<std::string, size_t> tensorMap_;
    int nextId_ = 0;
    bool ok_ = true;
    std::string error_;

    void fail(const std::string& msg) {
        if (ok_) {
            ok_ = false;
            error_ = msg;
            LOGE("%s", msg.c_str());
        }
    }

    const char* intern(const std::string& s) {
        names_.push_back(s);
        return names_.back().c_str();
    }

    uint32_t* storeDims(const std::vector<uint32_t>& d) {
        dimsPool_.push_back(d);
        return dimsPool_.back().data();
    }

    void initTensorHeader(
        Qnn_Tensor_t& t,
        const std::string& name,
        Qnn_TensorType_t type,
        Qnn_DataType_t dtype,
        const std::vector<uint32_t>& dims) {
        t = QNN_TENSOR_INIT;
        t.version = QNN_TENSOR_VERSION_1;
        t.v1.id = 0;
        t.v1.name = intern(name);
        t.v1.type = type;
        t.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
        t.v1.dataType = dtype;
        t.v1.quantizeParams = QNN_QUANTIZE_PARAMS_INIT;
        t.v1.quantizeParams.encodingDefinition = QNN_DEFINITION_UNDEFINED;
        t.v1.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_UNDEFINED;
        t.v1.rank = static_cast<uint32_t>(dims.size());
        t.v1.dimensions = storeDims(dims);
        t.v1.memType = QNN_TENSORMEMTYPE_RAW;
        t.v1.clientBuf = {nullptr, 0};
    }

    bool createGraphTensor(Qnn_Tensor_t& t) {
        if (aborted()) return false;
        const Qnn_ErrorHandle_t err = qnn_.tensorCreateGraphTensor(graph_, &t);
        if (err != QNN_SUCCESS) {
            fail("QnnTensor_createGraphTensor failed for " + std::string(t.v1.name) + ": err=" + std::to_string(err));
            return false;
        }
        // Static/clientBuf arrays are copied by QNN during tensorCreateGraphTensor; clear dangling stack pointers cleanly.
        t.v1.clientBuf = {nullptr, 0};
        tensorMap_[t.v1.name] = tensorPool_.size();
        tensorPool_.push_back(t);
        return true;
    }

    Qnn_Param_t makeScalarUint32(const char* paramName, uint32_t value) {
        Qnn_Param_t p = QNN_PARAM_INIT;
        p.paramType = QNN_PARAMTYPE_SCALAR;
        p.name = paramName;
        p.scalarParam.dataType = QNN_DATATYPE_UINT_32;
        p.scalarParam.uint32Value = value;
        return p;
    }

    Qnn_Param_t makeScalarFloat32(const char* paramName, float value) {
        Qnn_Param_t p = QNN_PARAM_INIT;
        p.paramType = QNN_PARAMTYPE_SCALAR;
        p.name = paramName;
        p.scalarParam.dataType = QNN_DATATYPE_FLOAT_32;
        p.scalarParam.floatValue = value;
        return p;
    }

    Qnn_Param_t makeScalarBool(const char* paramName, bool value) {
        Qnn_Param_t p = QNN_PARAM_INIT;
        p.paramType = QNN_PARAMTYPE_SCALAR;
        p.name = paramName;
        p.scalarParam.dataType = QNN_DATATYPE_BOOL_8;
        p.scalarParam.bool8Value = value ? 1 : 0;
        return p;
    }

    Qnn_Param_t makeTensorParam(
        const std::string& uniqueTensorName,
        const char* paramName,
        const std::vector<uint32_t>& dims,
        const uint32_t* data) {
        size_t elemCount = 1;
        for (uint32_t d : dims) elemCount *= d;
        Qnn_Param_t p = QNN_PARAM_INIT;
        p.paramType = QNN_PARAMTYPE_TENSOR;
        p.name = paramName;
        p.tensorParam = QNN_TENSOR_INIT;
        p.tensorParam.version = QNN_TENSOR_VERSION_1;
        p.tensorParam.v1.name = intern(uniq(uniqueTensorName));
        p.tensorParam.v1.type = QNN_TENSOR_TYPE_STATIC;
        p.tensorParam.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
        p.tensorParam.v1.dataType = QNN_DATATYPE_UINT_32;
        p.tensorParam.v1.quantizeParams = QNN_QUANTIZE_PARAMS_INIT;
        p.tensorParam.v1.rank = static_cast<uint32_t>(dims.size());
        p.tensorParam.v1.dimensions = storeDims(dims);
        p.tensorParam.v1.memType = QNN_TENSORMEMTYPE_RAW;
        p.tensorParam.v1.clientBuf = {const_cast<uint32_t*>(data), static_cast<uint32_t>(elemCount * sizeof(uint32_t))};
        const Qnn_ErrorHandle_t err = qnn_.tensorCreateGraphTensor(graph_, &p.tensorParam);
        if (err != QNN_SUCCESS) {
            fail("QnnTensor_createGraphTensor failed for param " + std::string(p.tensorParam.v1.name) + ": err=" + std::to_string(err));
        }
        return p;
    }

    bool addNode(
        const std::string& nodeName,
        const char* opType,
        Qnn_Param_t* params,
        uint32_t numParams,
        const std::vector<std::string>& inputNames,
        const std::vector<std::string>& outputNames) {
        if (aborted()) return false;
        std::vector<Qnn_Tensor_t> inTensors;
        inTensors.reserve(inputNames.size());
        for (const auto& name : inputNames) {
            auto it = tensorMap_.find(name);
            if (it == tensorMap_.end()) {
                fail("Unknown input tensor in graph: " + name);
                return false;
            }
            inTensors.push_back(tensorPool_[it->second]);
        }
        std::vector<Qnn_Tensor_t> outTensors;
        outTensors.reserve(outputNames.size());
        for (const auto& name : outputNames) {
            auto it = tensorMap_.find(name);
            if (it == tensorMap_.end()) {
                fail("Unknown output tensor in graph: " + name);
                return false;
            }
            outTensors.push_back(tensorPool_[it->second]);
        }

        Qnn_OpConfig_t op = QNN_OPCONFIG_INIT;
        op.version = QNN_OPCONFIG_VERSION_1;
        op.v1.name = intern(uniq(nodeName));
        op.v1.packageName = QNN_OP_PACKAGE_NAME_QTI_AISW;
        op.v1.typeName = opType;
        op.v1.numOfParams = numParams;
        op.v1.params = params;
        op.v1.numOfInputs = static_cast<uint32_t>(inTensors.size());
        op.v1.inputTensors = inTensors.data();
        op.v1.numOfOutputs = static_cast<uint32_t>(outTensors.size());
        op.v1.outputTensors = outTensors.data();

        const Qnn_ErrorHandle_t err = qnn_.graphAddNode(graph_, op);
        if (err != QNN_SUCCESS) {
            fail("QnnGraph_addNode(" + std::string(opType) + ", " + nodeName + ") failed: err=" + std::to_string(err));
            return false;
        }
        return true;
    }
};

std::string buildUnetResBlock(
    QnnGraphBuilder& gb,
    const std::string& pfx,
    const std::string& x,
    const std::string& emb,
    int inCh,
    int outCh,
    int embCh,
    int h,
    int w,
    const std::string& tag) {
    std::string hOut = gb.groupNorm(x, pfx + ".in_layers.0.weight", pfx + ".in_layers.0.bias",
                                    {1, static_cast<uint32_t>(h), static_cast<uint32_t>(w), static_cast<uint32_t>(inCh)},
                                    32, 1e-5f, tag + "_gn1");
    hOut = gb.silu(hOut, {1, static_cast<uint32_t>(h), static_cast<uint32_t>(w), static_cast<uint32_t>(inCh)}, tag + "_silu1");
    hOut = gb.conv2d(hOut, pfx + ".in_layers.2.weight", pfx + ".in_layers.2.bias",
                     outCh, inCh, 3, 3, h, w, 1, 1, 1, 1, 1, 1, tag + "_conv1");

    std::string embSilu = gb.silu(emb, {1, static_cast<uint32_t>(embCh)}, tag + "_esilu");
    std::string embProj = gb.fullyConnected(embSilu, pfx + ".emb_layers.1.weight", pfx + ".emb_layers.1.bias",
                                            outCh, embCh, 1, tag + "_eproj");
    embProj = gb.reshape(embProj, {1, 1, 1, static_cast<uint32_t>(outCh)}, tag + "_ebroad");
    hOut = gb.binary(hOut, embProj, QNN_OP_ELEMENT_WISE_ADD,
                     {1, static_cast<uint32_t>(h), static_cast<uint32_t>(w), static_cast<uint32_t>(outCh)}, tag + "_eadd");

    hOut = gb.groupNorm(hOut, pfx + ".out_layers.0.weight", pfx + ".out_layers.0.bias",
                        {1, static_cast<uint32_t>(h), static_cast<uint32_t>(w), static_cast<uint32_t>(outCh)},
                        32, 1e-5f, tag + "_gn2");
    hOut = gb.silu(hOut, {1, static_cast<uint32_t>(h), static_cast<uint32_t>(w), static_cast<uint32_t>(outCh)}, tag + "_silu2");
    hOut = gb.conv2d(hOut, pfx + ".out_layers.3.weight", pfx + ".out_layers.3.bias",
                     outCh, outCh, 3, 3, h, w, 1, 1, 1, 1, 1, 1, tag + "_conv2");

    std::string skip = x;
    if (inCh != outCh) {
        skip = gb.conv2d(x, pfx + ".skip_connection.weight", pfx + ".skip_connection.bias",
                         outCh, inCh, 1, 1, h, w, 1, 1, 0, 0, 0, 0, tag + "_skip");
    }
    return gb.binary(hOut, skip, QNN_OP_ELEMENT_WISE_ADD,
                     {1, static_cast<uint32_t>(h), static_cast<uint32_t>(w), static_cast<uint32_t>(outCh)}, tag + "_radd");
}

std::string buildCrossAttention(
    QnnGraphBuilder& gb,
    const std::string& pfx,
    const std::string& x,
    const std::string& context,
    int queryDim,
    int contextDim,
    int numHeads,
    int dHead,
    int seqLen,
    int ctxLen,
    const std::string& tag,
    const std::string& attnBias = "") {
    const int innerDim = numHeads * dHead;
    const uint32_t qSeq = static_cast<uint32_t>(seqLen);
    const uint32_t kSeq = static_cast<uint32_t>(ctxLen);
    const uint32_t nh = static_cast<uint32_t>(numHeads);
    const uint32_t dh = static_cast<uint32_t>(dHead);

    std::string q = gb.fullyConnected(x, pfx + ".to_q.weight", "", innerDim, queryDim, qSeq, tag + "_q", true, qSeq);
    std::string k = gb.fullyConnected(context, pfx + ".to_k.weight", "", innerDim, contextDim, kSeq, tag + "_k", true, kSeq);
    std::string v = gb.fullyConnected(context, pfx + ".to_v.weight", "", innerDim, contextDim, kSeq, tag + "_v", true, kSeq);

    q = gb.reshape(q, {1, qSeq, nh, dh}, tag + "_qr");
    k = gb.reshape(k, {1, kSeq, nh, dh}, tag + "_kr");
    v = gb.reshape(v, {1, kSeq, nh, dh}, tag + "_vr");

    q = gb.transpose(q, {1, nh, qSeq, dh}, {0, 2, 1, 3}, tag + "_qt");
    k = gb.transpose(k, {1, nh, kSeq, dh}, {0, 2, 1, 3}, tag + "_kt");
    v = gb.transpose(v, {1, nh, kSeq, dh}, {0, 2, 1, 3}, tag + "_vt");

    const std::string scaleConst = gb.uniq(tag + "_scale");
    gb.staticFp16(scaleConst, {1, 1, 1, 1}, {1.0f / std::sqrt(static_cast<float>(dHead))});
    q = gb.binary(q, scaleConst, QNN_OP_ELEMENT_WISE_MULTIPLY, {1, nh, qSeq, dh}, tag + "_qs");

    std::string scores = gb.matmul(q, k, {1, nh, qSeq, kSeq}, false, true, tag + "_qk");
    if (!attnBias.empty()) {
        scores = gb.binary(scores, attnBias, QNN_OP_ELEMENT_WISE_ADD, {1, nh, qSeq, kSeq}, tag + "_mask_add");
    }
    std::string probs = gb.softmax(scores, {1, nh, qSeq, kSeq}, 3, tag + "_sm");
    std::string out = gb.matmul(probs, v, {1, nh, qSeq, dh}, false, false, tag + "_pv");

    out = gb.transpose(out, {1, qSeq, nh, dh}, {0, 2, 1, 3}, tag + "_ot");
    out = gb.reshape(out, {1, qSeq, static_cast<uint32_t>(innerDim)}, tag + "_or");
    return gb.fullyConnected(out, pfx + ".to_out.0.weight", pfx + ".to_out.0.bias",
                             queryDim, innerDim, qSeq, tag + "_proj", true, qSeq);
}

std::string buildSpatialTransformer(
    QnnGraphBuilder& gb,
    SafeTensorReader& reader,
    const std::string& pfx,
    const std::string& xIn,
    const std::string& context,
    int ch,
    int numHeads,
    int dHead,
    int depth,
    int contextDim,
    int h,
    int w,
    int ctxLen,
    const std::string& tag,
    const std::string& attnBias = "") {
    const int seqLen = h * w;
    const uint32_t uH = static_cast<uint32_t>(h);
    const uint32_t uW = static_cast<uint32_t>(w);
    const uint32_t uCh = static_cast<uint32_t>(ch);
    const uint32_t uSeq = static_cast<uint32_t>(seqLen);

    std::string x = gb.groupNorm(xIn, pfx + ".norm.weight", pfx + ".norm.bias",
                                 {1, uH, uW, uCh}, 32, 1e-6f, tag + "_gn");
    const bool useLinear = reader.tensor_shape(pfx + ".proj_out.weight").size() == 2;
    if (useLinear) {
        x = gb.reshape(x, {1, uSeq, uCh}, tag + "_in3");
        x = gb.fullyConnected(x, pfx + ".proj_in.weight", pfx + ".proj_in.bias",
                              ch, ch, uSeq, tag + "_pin", true, uSeq);
    } else {
        x = gb.conv2d(x, pfx + ".proj_in.weight", pfx + ".proj_in.bias",
                      ch, ch, 1, 1, h, w, 1, 1, 0, 0, 0, 0, tag + "_pin");
        x = gb.reshape(x, {1, uSeq, uCh}, tag + "_in3");
    }

    for (int d = 0; d < depth; ++d) {
        const std::string tb = pfx + ".transformer_blocks." + std::to_string(d);
        const std::string dTag = tag + "_tb" + std::to_string(d);

        std::string r1 = x;
        std::string ln1 = gb.layerNorm(x, tb + ".norm1.weight", tb + ".norm1.bias", {1, uSeq, uCh}, 1e-5f, dTag + "_ln1");
        std::string sa = buildCrossAttention(gb, tb + ".attn1", ln1, ln1, ch, ch, numHeads, dHead, seqLen, seqLen, dTag + "_sa");
        x = gb.binary(sa, r1, QNN_OP_ELEMENT_WISE_ADD, {1, uSeq, uCh}, dTag + "_add1");

        std::string r2 = x;
        std::string ln2 = gb.layerNorm(x, tb + ".norm2.weight", tb + ".norm2.bias", {1, uSeq, uCh}, 1e-5f, dTag + "_ln2");
        std::string ca = buildCrossAttention(gb, tb + ".attn2", ln2, context, ch, contextDim, numHeads, dHead, seqLen, ctxLen, dTag + "_ca", attnBias);
        x = gb.binary(ca, r2, QNN_OP_ELEMENT_WISE_ADD, {1, uSeq, uCh}, dTag + "_add2");

        std::string r3 = x;
        std::string ln3 = gb.layerNorm(x, tb + ".norm3.weight", tb + ".norm3.bias", {1, uSeq, uCh}, 1e-5f, dTag + "_ln3");
        const uint32_t ffInner = uCh * 4;
        std::string ffProj = gb.fullyConnected(ln3, tb + ".ff.net.0.proj.weight", tb + ".ff.net.0.proj.bias",
                                               ch * 8, ch, uSeq, dTag + "_ff0", true, uSeq);
        auto parts = gb.split(ffProj, 2, {ffInner}, {{1, uSeq, ffInner}, {1, uSeq, ffInner}}, dTag + "_geglu_sp");
        if (gb.aborted()) return {};
        std::string gate = gb.unary(parts[1], QNN_OP_GELU, {1, uSeq, ffInner}, dTag + "_gelu");
        std::string gated = gb.binary(parts[0], gate, QNN_OP_ELEMENT_WISE_MULTIPLY, {1, uSeq, ffInner}, dTag + "_geglu");
        std::string ffOut = gb.fullyConnected(gated, tb + ".ff.net.2.weight", tb + ".ff.net.2.bias",
                                              ch, ch * 4, uSeq, dTag + "_ff2", true, uSeq);
        x = gb.binary(ffOut, r3, QNN_OP_ELEMENT_WISE_ADD, {1, uSeq, uCh}, dTag + "_add3");
    }

    if (useLinear) {
        x = gb.fullyConnected(x, pfx + ".proj_out.weight", pfx + ".proj_out.bias",
                              ch, ch, uSeq, tag + "_pout", true, uSeq);
        x = gb.reshape(x, {1, uH, uW, uCh}, tag + "_out4");
    } else {
        x = gb.reshape(x, {1, uH, uW, uCh}, tag + "_out4");
        x = gb.conv2d(x, pfx + ".proj_out.weight", pfx + ".proj_out.bias",
                      ch, ch, 1, 1, h, w, 1, 1, 0, 0, 0, 0, tag + "_pout");
    }
    return gb.binary(x, xIn, QNN_OP_ELEMENT_WISE_ADD, {1, uH, uW, uCh}, tag + "_radd");
}

struct UnetSpec {
    int latentH;
    int latentW;
    int contextTokens;
    int contextDim;
    int numHeadChannels;  // -1 => numHeads fixed at 8
    int numHeads;
    bool isSdxl;
    std::vector<int> channelMult;
    std::vector<int> attentionResolutions;
    std::vector<int> transformerDepth;
};

void buildUnetGraphBody(
    QnnGraphBuilder& gb,
    SafeTensorReader& reader,
    const UnetSpec& spec,
    const std::string& pfx) {
    const uint32_t latH = static_cast<uint32_t>(spec.latentH);
    const uint32_t latW = static_cast<uint32_t>(spec.latentW);
    const uint32_t ctxTokens = static_cast<uint32_t>(spec.contextTokens);
    const uint32_t ctxDim = static_cast<uint32_t>(spec.contextDim);

    std::string x;
    if (spec.isSdxl) {
        const std::string xNchw = gb.addInput("sample", {1, 4, latH, latW});
        x = gb.transpose(xNchw, {1, latH, latW, 4}, {0, 2, 3, 1}, "in_nhwc");
    } else {
        x = gb.addInput("sample", {1, latH, latW, 4});
    }
    const std::string tIn = gb.addInput("timestep", {1});
    const std::string context = gb.addInput("encoder_hidden_states", {1, ctxTokens, ctxDim});
    std::string attnBias;
    if (spec.contextTokens > 77) {
        const std::string maskIn = gb.addInput("encoder_attention_mask", {1, ctxTokens});
        const std::string posScale = gb.uniq("mask_pos");
        const std::string negBias = gb.uniq("mask_bias");
        gb.staticFp16(posScale, {1, 1}, {100.0f});
        gb.staticFp16(negBias, {1, 1}, {-100.0f});
        attnBias = gb.binary(maskIn, posScale, QNN_OP_ELEMENT_WISE_MULTIPLY, {1, ctxTokens}, "mask_mul");
        attnBias = gb.binary(attnBias, negBias, QNN_OP_ELEMENT_WISE_ADD, {1, ctxTokens}, "mask_add");
        attnBias = gb.reshape(attnBias, {1, 1, 1, ctxTokens}, "mask_4d");
    }

    std::string t2D = gb.reshape(tIn, {1, 1}, "t_2d");
    std::string tEmb = gb.timestepEmbedding(t2D, 320, "t_emb");
    std::string emb = gb.fullyConnected(tEmb, pfx + "time_embed.0.weight", pfx + "time_embed.0.bias", 1280, 320, 1, "te0");
    emb = gb.silu(emb, {1, 1280}, "te_silu");
    emb = gb.fullyConnected(emb, pfx + "time_embed.2.weight", pfx + "time_embed.2.bias", 1280, 1280, 1, "te2");

    if (spec.isSdxl) {
        const std::string textEmbeds = gb.addInput("text_embeds", {1, 1280});
        const std::string timeIds = gb.addInput("time_ids", {1, 6});
        auto ids = gb.split(timeIds, 1, {1, 2, 3, 4, 5}, {{1, 1}, {1, 1}, {1, 1}, {1, 1}, {1, 1}, {1, 1}}, "tid_split");
        if (gb.aborted()) return;
        std::vector<std::string> admParts;
        admParts.push_back(textEmbeds);
        for (int k = 0; k < 6; ++k) {
            admParts.push_back(gb.timestepEmbedding(ids[static_cast<size_t>(k)], 256, "tid_emb" + std::to_string(k)));
        }
        std::string adm = gb.concat(admParts, 1, {1, 2816}, "adm_cat");
        std::string lEmb = gb.fullyConnected(adm, pfx + "label_emb.0.0.weight", pfx + "label_emb.0.0.bias", 1280, 2816, 1, "le0");
        lEmb = gb.silu(lEmb, {1, 1280}, "le_silu");
        lEmb = gb.fullyConnected(lEmb, pfx + "label_emb.0.2.weight", pfx + "label_emb.0.2.bias", 1280, 1280, 1, "le2");
        emb = gb.binary(emb, lEmb, QNN_OP_ELEMENT_WISE_ADD, {1, 1280}, "emb_sum");
    }

    auto headsFor = [&](int ch, int& nHeads, int& dHead) {
        if (spec.numHeadChannels > 0) {
            dHead = spec.numHeadChannels;
            nHeads = ch / dHead;
        } else {
            nHeads = spec.numHeads;
            dHead = ch / nHeads;
        }
    };
    auto hasAttn = [&](int ds) {
        return std::find(spec.attentionResolutions.begin(), spec.attentionResolutions.end(), ds) !=
               spec.attentionResolutions.end();
    };

    struct SkipEntry {
        std::string name;
        int ch;
    };
    std::vector<SkipEntry> hs;

    int curH = spec.latentH;
    int curW = spec.latentW;
    int ch = 320;
    std::string h = gb.conv2d(x, pfx + "input_blocks.0.0.weight", pfx + "input_blocks.0.0.bias",
                              ch, 4, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1, "in0");
    hs.push_back({h, ch});

    int inputBlockIdx = 0;
    int ds = 1;
    const int numLevels = static_cast<int>(spec.channelMult.size());
    for (int i = 0; i < numLevels; ++i) {
        const int outCh = 320 * spec.channelMult[i];
        for (int j = 0; j < 2; ++j) {
            inputBlockIdx++;
            const std::string ib = pfx + "input_blocks." + std::to_string(inputBlockIdx);
            h = buildUnetResBlock(gb, ib + ".0", h, emb, ch, outCh, 1280, curH, curW, "inb" + std::to_string(inputBlockIdx) + "_0");
            ch = outCh;
            if (hasAttn(ds)) {
                int nHeads = 0, dHead = 0;
                headsFor(ch, nHeads, dHead);
                h = buildSpatialTransformer(gb, reader, ib + ".1", h, context, ch, nHeads, dHead,
                                            spec.transformerDepth[i], spec.contextDim, curH, curW,
                                            spec.contextTokens, "inb" + std::to_string(inputBlockIdx) + "_1", attnBias);
            }
            hs.push_back({h, ch});
        }
        if (i != numLevels - 1) {
            ds *= 2;
            inputBlockIdx++;
            curH /= 2;
            curW /= 2;
            const std::string dsKey = pfx + "input_blocks." + std::to_string(inputBlockIdx) + ".0.op";
            h = gb.conv2d(h, dsKey + ".weight", dsKey + ".bias", ch, ch, 3, 3, curH, curW, 2, 2, 1, 1, 1, 1,
                          "inds" + std::to_string(inputBlockIdx));
            hs.push_back({h, ch});
        }
    }

    h = buildUnetResBlock(gb, pfx + "middle_block.0", h, emb, ch, ch, 1280, curH, curW, "mid0");
    {
        int nHeads = 0, dHead = 0;
        headsFor(ch, nHeads, dHead);
        h = buildSpatialTransformer(gb, reader, pfx + "middle_block.1", h, context, ch, nHeads, dHead,
                                    spec.transformerDepth.back(), spec.contextDim, curH, curW,
                                    spec.contextTokens, "mid1", attnBias);
    }
    h = buildUnetResBlock(gb, pfx + "middle_block.2", h, emb, ch, ch, 1280, curH, curW, "mid2");

    int outputBlockIdx = 0;
    for (int i = numLevels - 1; i >= 0; --i) {
        const int outCh = 320 * spec.channelMult[i];
        for (int j = 0; j < 3; ++j) {
            const SkipEntry skip = hs.back();
            hs.pop_back();
            const std::string ob = pfx + "output_blocks." + std::to_string(outputBlockIdx);
            const std::string tag = "outb" + std::to_string(outputBlockIdx);
            h = gb.concat({h, skip.name}, 3,
                          {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), static_cast<uint32_t>(ch + skip.ch)},
                          tag + "_cat");
            h = buildUnetResBlock(gb, ob + ".0", h, emb, ch + skip.ch, outCh, 1280, curH, curW, tag + "_0");
            ch = outCh;
            int upsampleIdx = 1;
            if (hasAttn(ds)) {
                int nHeads = 0, dHead = 0;
                headsFor(ch, nHeads, dHead);
                h = buildSpatialTransformer(gb, reader, ob + ".1", h, context, ch, nHeads, dHead,
                                            spec.transformerDepth[i], spec.contextDim, curH, curW,
                                            spec.contextTokens, tag + "_1", attnBias);
                upsampleIdx++;
            }
            if (i > 0 && j == 2) {
                h = gb.resizeNearest2x(h, static_cast<uint32_t>(ch), static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), tag + "_up");
                curH *= 2;
                curW *= 2;
                const std::string upKey = ob + "." + std::to_string(upsampleIdx) + ".conv";
                h = gb.conv2d(h, upKey + ".weight", upKey + ".bias", ch, ch, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1, tag + "_upc");
                ds /= 2;
            }
            outputBlockIdx++;
        }
    }

    h = gb.groupNorm(h, pfx + "out.0.weight", pfx + "out.0.bias",
                     {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), 320},
                     32, 1e-5f, "out_gn");
    h = gb.silu(h, {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), 320}, "out_silu");
    if (spec.isSdxl) {
        h = gb.conv2d(h, pfx + "out.2.weight", pfx + "out.2.bias", 4, 320, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1, "out_conv");
        gb.transpose(h, {1, 4, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW)},
                     {0, 3, 1, 2}, "out_nchw", "out", QNN_TENSOR_TYPE_APP_READ);
    } else {
        gb.conv2d(h, pfx + "out.2.weight", pfx + "out.2.bias", 4, 320, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1,
                  "out_conv", "out", QNN_TENSOR_TYPE_APP_READ);
    }
}

std::string buildVaeResnetBlock(
    QnnGraphBuilder& gb,
    const std::string& pfx,
    const std::string& x,
    int inCh,
    int outCh,
    int h,
    int w,
    const std::string& tag) {
    const uint32_t uH = static_cast<uint32_t>(h);
    const uint32_t uW = static_cast<uint32_t>(w);
    std::string residual = x;
    if (inCh != outCh) {
        residual = gb.conv2d(x, pfx + ".nin_shortcut.weight", pfx + ".nin_shortcut.bias",
                             outCh, inCh, 1, 1, h, w, 1, 1, 0, 0, 0, 0, tag + "_nin");
    }
    std::string hOut = gb.groupNorm(x, pfx + ".norm1.weight", pfx + ".norm1.bias",
                                    {1, uH, uW, static_cast<uint32_t>(inCh)}, 32, 1e-6f, tag + "_gn1");
    hOut = gb.silu(hOut, {1, uH, uW, static_cast<uint32_t>(inCh)}, tag + "_silu1");
    hOut = gb.conv2d(hOut, pfx + ".conv1.weight", pfx + ".conv1.bias",
                     outCh, inCh, 3, 3, h, w, 1, 1, 1, 1, 1, 1, tag + "_conv1");
    hOut = gb.groupNorm(hOut, pfx + ".norm2.weight", pfx + ".norm2.bias",
                        {1, uH, uW, static_cast<uint32_t>(outCh)}, 32, 1e-6f, tag + "_gn2");
    hOut = gb.silu(hOut, {1, uH, uW, static_cast<uint32_t>(outCh)}, tag + "_silu2");
    hOut = gb.conv2d(hOut, pfx + ".conv2.weight", pfx + ".conv2.bias",
                     outCh, outCh, 3, 3, h, w, 1, 1, 1, 1, 1, 1, tag + "_conv2");
    return gb.binary(hOut, residual, QNN_OP_ELEMENT_WISE_ADD,
                     {1, uH, uW, static_cast<uint32_t>(outCh)}, tag + "_add");
}

std::string buildVaeAttnBlock(
    QnnGraphBuilder& gb,
    const std::string& pfx,
    const std::string& x,
    int ch,
    int h,
    int w,
    const std::string& tag) {
    const uint32_t uCh = static_cast<uint32_t>(ch);
    const uint32_t uH = static_cast<uint32_t>(h);
    const uint32_t uW = static_cast<uint32_t>(w);
    const uint32_t seq = uH * uW;

    std::string n = gb.groupNorm(x, pfx + ".norm.weight", pfx + ".norm.bias", {1, uH, uW, uCh}, 32, 1e-6f, tag + "_gn");
    std::string q = gb.conv1x1OrLinear2D(n, pfx + ".q.weight", pfx + ".q.bias", ch, ch, h, w, tag + "_q");
    std::string k = gb.conv1x1OrLinear2D(n, pfx + ".k.weight", pfx + ".k.bias", ch, ch, h, w, tag + "_k");
    std::string v = gb.conv1x1OrLinear2D(n, pfx + ".v.weight", pfx + ".v.bias", ch, ch, h, w, tag + "_v");

    q = gb.reshape(q, {1, 1, seq, uCh}, tag + "_qr");
    k = gb.reshape(k, {1, 1, seq, uCh}, tag + "_kr");
    v = gb.reshape(v, {1, 1, seq, uCh}, tag + "_vr");

    const std::string scaleConst = gb.uniq(tag + "_scale");
    gb.staticFp16(scaleConst, {1, 1, 1, 1}, {1.0f / std::sqrt(static_cast<float>(ch))});
    q = gb.binary(q, scaleConst, QNN_OP_ELEMENT_WISE_MULTIPLY, {1, 1, seq, uCh}, tag + "_qs");

    std::string scores = gb.matmul(q, k, {1, 1, seq, seq}, false, true, tag + "_qk");
    std::string probs = gb.softmax(scores, {1, 1, seq, seq}, 3, tag + "_sm");
    std::string attn = gb.matmul(probs, v, {1, 1, seq, uCh}, false, false, tag + "_pv");
    attn = gb.reshape(attn, {1, uH, uW, uCh}, tag + "_or");
    std::string proj = gb.conv1x1OrLinear2D(attn, pfx + ".proj_out.weight", pfx + ".proj_out.bias", ch, ch, h, w, tag + "_proj");
    return gb.binary(proj, x, QNN_OP_ELEMENT_WISE_ADD, {1, uH, uW, uCh}, tag + "_add");
}

template <typename BuildFn>
bool finalizeContextToBuffer(
    QnnBuilderBackend& backend,
    const std::string& graphName,
    bool isLargeGraph,
    SafeTensorReader& reader,
    const std::vector<SafeTensorReader*>& loras,
    const std::vector<float>& loraStrengths,
    const std::unordered_map<std::string, std::string>* loraMap,
    const std::string& backingDir,
    FileBackedBuffer* outBuffer,
    const std::string& outFilePath,
    const std::atomic<bool>& cancelled,
    std::string& errorOut,
    BuildFn&& buildFn) {
    if (cancelled.load()) {
        errorOut = "Conversion cancelled.";
        return false;
    }
    const auto& qnn = backend.impl->qnn;

    QnnHtpContext_CustomConfig_t prepareOnlyCfg = QNN_HTP_CONTEXT_CUSTOM_CONFIG_INIT;
    prepareOnlyCfg.option = QNN_HTP_CONTEXT_CONFIG_OPTION_PREPARE_ONLY;
    prepareOnlyCfg.isPrepareOnly = true;

    QnnContext_Config_t cPrep = QNN_CONTEXT_CONFIG_INIT;
    cPrep.option = QNN_CONTEXT_CONFIG_OPTION_CUSTOM;
    cPrep.customConfig = &prepareOnlyCfg;

    const QnnContext_Config_t* prepOnlyCtxCfgs[] = {&cPrep, nullptr};

    Qnn_ContextHandle_t context = nullptr;
    Qnn_ErrorHandle_t err = qnn.contextCreate(backend.backendHandle, backend.deviceHandle, prepOnlyCtxCfgs, &context);
    if (!context) {
        err = qnn.contextCreate(backend.backendHandle, backend.deviceHandle, nullptr, &context);
    }
    if (err != QNN_SUCCESS || !context) {
        errorOut = "QnnContext_create failed: err=" + std::to_string(err);
        return false;
    }

    auto cleanupContext = [&]() {
        if (context) {
            qnn.contextFree(context, nullptr);
            context = nullptr;
            mallopt(M_PURGE_ALL, 0);
        }
    };

    QnnHtpGraph_CustomConfig_t precisionCfg = QNN_HTP_GRAPH_CUSTOM_CONFIG_INIT;
    precisionCfg.option = QNN_HTP_GRAPH_CONFIG_OPTION_PRECISION;
    precisionCfg.precision = QNN_PRECISION_FLOAT16;

    QnnHtpGraph_CustomConfig_t vtcmCfg = QNN_HTP_GRAPH_CUSTOM_CONFIG_INIT;
    vtcmCfg.option = QNN_HTP_GRAPH_CONFIG_OPTION_VTCM_SIZE_IN_MB;
    vtcmCfg.vtcmSizeInMB = 8;

    QnnHtpGraph_CustomConfig_t optCfg = QNN_HTP_GRAPH_CUSTOM_CONFIG_INIT;
    optCfg.option = QNN_HTP_GRAPH_CONFIG_OPTION_OPTIMIZATION;
    optCfg.optimizationOption.type = QNN_HTP_GRAPH_OPTIMIZATION_TYPE_FINALIZE_OPTIMIZATION_FLAG;
    optCfg.optimizationOption.floatValue = 3.0f;

    QnnHtpGraph_CustomConfig_t sdOpsCfg = QNN_HTP_GRAPH_CUSTOM_CONFIG_INIT;
    sdOpsCfg.option = QNN_HTP_GRAPH_CONFIG_OPTION_FINALIZE_CONFIG;
    sdOpsCfg.finalizeConfig.key = "source_destructive_ops";
    sdOpsCfg.finalizeConfig.value.dataType = QNN_DATATYPE_UINT_32;
    sdOpsCfg.finalizeConfig.value.uint32Value = 0;

    QnnHtpGraph_CustomConfig_t sdCustomCfg = QNN_HTP_GRAPH_CUSTOM_CONFIG_INIT;
    sdCustomCfg.option = QNN_HTP_GRAPH_CONFIG_OPTION_FINALIZE_CONFIG;
    sdCustomCfg.finalizeConfig.key = "source_destructive_custom_ops";
    sdCustomCfg.finalizeConfig.value.dataType = QNN_DATATYPE_UINT_32;
    sdCustomCfg.finalizeConfig.value.uint32Value = 0;

    QnnGraph_Config_t gPrecision = QNN_GRAPH_CONFIG_INIT;
    gPrecision.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
    gPrecision.customConfig = &precisionCfg;

    QnnGraph_Config_t gVtcm = QNN_GRAPH_CONFIG_INIT;
    gVtcm.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
    gVtcm.customConfig = &vtcmCfg;

    QnnGraph_Config_t gOpt = QNN_GRAPH_CONFIG_INIT;
    gOpt.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
    gOpt.customConfig = &optCfg;

    QnnGraph_Config_t gSdOps = QNN_GRAPH_CONFIG_INIT;
    gSdOps.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
    gSdOps.customConfig = &sdOpsCfg;

    QnnGraph_Config_t gSdCustom = QNN_GRAPH_CONFIG_INIT;
    gSdCustom.option = QNN_GRAPH_CONFIG_OPTION_CUSTOM;
    gSdCustom.customConfig = &sdCustomCfg;

    const QnnGraph_Config_t* largeGraphCfgs[] = {&gPrecision, &gVtcm, &gOpt, &gSdOps, &gSdCustom, nullptr};
    const QnnGraph_Config_t* baseGraphCfgs[] = {&gPrecision, &gVtcm, &gOpt, nullptr};
    Qnn_GraphHandle_t graph = nullptr;
    bool usingNoSourceDestructive = false;
    if (isLargeGraph) {
        err = qnn.graphCreate(context, graphName.c_str(), largeGraphCfgs, &graph);
        if (err == QNN_SUCCESS && graph) {
            usingNoSourceDestructive = true;
        } else {
            LOGE("QnnGraph_create('%s') with source_destructive=0 failed: err=%llu; retrying base HTP config",
                 graphName.c_str(), static_cast<unsigned long long>(err));
            graph = nullptr;
        }
    }
    if (!graph) {
        err = qnn.graphCreate(context, graphName.c_str(), baseGraphCfgs, &graph);
    }
    if (err != QNN_SUCCESS || !graph) {
        LOGE("QnnGraph_create('%s') with base HTP config failed: err=%llu; retrying default",
             graphName.c_str(), static_cast<unsigned long long>(err));
        graph = nullptr;
        err = qnn.graphCreate(context, graphName.c_str(), nullptr, &graph);
    }
    if (err != QNN_SUCCESS || !graph) {
        cleanupContext();
        errorOut = "QnnGraph_create failed for " + graphName + ": err=" + std::to_string(err);
        return false;
    }

    {
        QnnGraphBuilder builder(qnn, context, graph, reader, loras, loraStrengths, loraMap, cancelled);
        buildFn(builder);
        if (builder.aborted()) {
            cleanupContext();
            errorOut = cancelled.load() ? "Conversion cancelled." : builder.error();
            return false;
        }
    }
    reader.clearBuffers();
    mallopt(M_PURGE_ALL, 0);

    LOGI("Finalizing QNN HTP graph '%s' (large=%d, vtcm=8MB, opt=%.0f, sd_off=%d)...",
         graphName.c_str(), isLargeGraph ? 1 : 0, optCfg.optimizationOption.floatValue,
         usingNoSourceDestructive ? 1 : 0);
    std::fprintf(stderr, "Finalizing QNN HTP graph '%s' (vtcm=8MB, opt=%.0f, sd_off=%d)…\n",
                 graphName.c_str(), optCfg.optimizationOption.floatValue,
                 usingNoSourceDestructive ? 1 : 0);
    std::fflush(stderr);
    err = qnn.graphFinalize(graph, nullptr, nullptr);
    if (err != QNN_SUCCESS) {
        cleanupContext();
        errorOut = "QnnGraph_finalize failed for " + graphName + ": err=" + std::to_string(err);
        return false;
    }

    if (cancelled.load()) {
        cleanupContext();
        errorOut = "Conversion cancelled.";
        return false;
    }

    Qnn_ContextBinarySize_t binarySize = 0;
    err = qnn.contextGetBinarySize(context, &binarySize);
    if (err != QNN_SUCCESS || binarySize == 0) {
        cleanupContext();
        errorOut = "QnnContext_getBinarySize failed for " + graphName + ": err=" + std::to_string(err);
        return false;
    }

    std::fprintf(stderr, "Serializing '%s' context binary (%llu MiB)…\n",
                 graphName.c_str(), static_cast<unsigned long long>(binarySize / (1024ULL * 1024ULL)));
    std::fflush(stderr);

    FileBackedBuffer localBuf;
    FileBackedBuffer* targetBuf = outBuffer ? outBuffer : &localBuf;
    if (!targetBuf->resize(static_cast<size_t>(binarySize), backingDir)) {
        cleanupContext();
        errorOut = "Failed allocating context binary buffer (" + std::to_string(binarySize) + " bytes)";
        return false;
    }

    Qnn_ContextBinarySize_t written = 0;
    err = qnn.contextGetBinary(context, targetBuf->data(), binarySize, &written);
    cleanupContext();
    if (err != QNN_SUCCESS || written == 0) {
        errorOut = "QnnContext_getBinary failed for " + graphName + ": err=" + std::to_string(err);
        return false;
    }

    if (!outFilePath.empty()) {
        std::ofstream f(outFilePath, std::ios::binary | std::ios::trunc);
        if (!f) {
            errorOut = "Cannot open output file: " + outFilePath;
            return false;
        }
        f.write(reinterpret_cast<const char*>(targetBuf->data()), static_cast<std::streamsize>(written));
        f.flush();
        if (!f) {
            errorOut = "Write failed for: " + outFilePath;
            return false;
        }
    }
    LOGI("Finalized '%s': %llu bytes", graphName.c_str(), static_cast<unsigned long long>(written));
    std::fprintf(stderr, "Finished '%s' (%llu MiB)\n",
                 graphName.c_str(), static_cast<unsigned long long>(written / (1024ULL * 1024ULL)));
    std::fflush(stderr);
    return true;
}

}  // namespace

bool QnnBuilderBackend::init(const std::string& skelDir, std::string& errorOut) {
    if (impl) return true;
    const std::string adsp = skelDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    backendLib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!backendLib) {
        errorOut = std::string("Cannot load libQnnHtp.so: ") + (dlerror() ? dlerror() : "unknown");
        return false;
    }

    const auto getProviders = reinterpret_cast<GetQnnProvidersFn>(dlsym(backendLib, "QnnInterface_getProviders"));
    const QnnInterface_t** providers = nullptr;
    uint32_t providerCount = 0;
    if (!getProviders || getProviders(&providers, &providerCount) != QNN_SUCCESS || !providers) {
        shutdown();
        errorOut = "QnnInterface_getProviders failed";
        return false;
    }

    auto state = std::make_unique<Impl>();
    bool found = false;
    for (uint32_t i = 0; i < providerCount; ++i) {
        if (providers[i] &&
            providers[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
            providers[i]->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
            state->qnn = providers[i]->QNN_INTERFACE_VER_NAME;
            found = true;
            break;
        }
    }
    if (!found) {
        shutdown();
        errorOut = "Compatible QNN HTP interface provider not found";
        return false;
    }

    if (state->qnn.logCreate) {
        Qnn_LogHandle_t log = nullptr;
        if (state->qnn.logCreate(nullptr, QNN_LOG_LEVEL_WARN, &log) == QNN_SUCCESS) {
            logHandle = log;
        }
    }
    Qnn_BackendHandle_t backend = nullptr;
    if (state->qnn.backendCreate(logHandle, nullptr, &backend) != QNN_SUCCESS || !backend) {
        impl = state.release();
        shutdown();
        errorOut = "QnnBackend_create failed";
        return false;
    }
    backendHandle = backend;

    if (state->qnn.deviceCreate) {
        Qnn_DeviceHandle_t device = nullptr;
        if (state->qnn.deviceCreate(logHandle, nullptr, &device) == QNN_SUCCESS) {
            deviceHandle = device;
        }
    }
    impl = state.release();
    return true;
}

void QnnBuilderBackend::shutdown() {
    if (impl) {
        const auto& qnn = impl->qnn;
        if (deviceHandle && qnn.deviceFree) {
            qnn.deviceFree(deviceHandle);
            deviceHandle = nullptr;
        }
        if (backendHandle && qnn.backendFree) {
            qnn.backendFree(backendHandle);
            backendHandle = nullptr;
        }
        if (logHandle && qnn.logFree) {
            qnn.logFree(logHandle);
            logHandle = nullptr;
        }
        delete impl;
        impl = nullptr;
    }
    if (backendLib) {
        dlclose(backendLib);
        backendLib = nullptr;
    }
}

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
    std::string& errorOut) {
    const std::string pfx = findUnetPrefix(reader);
    const UnetSpec spec{
        .latentH = height / 8,
        .latentW = width / 8,
        .contextTokens = 231,
        .contextDim = 768,
        .numHeadChannels = -1,
        .numHeads = 8,
        .isSdxl = false,
        .channelMult = {1, 2, 4, 4},
        .attentionResolutions = {4, 2, 1},
        .transformerDepth = {1, 1, 1, 1},
    };
    return finalizeContextToBuffer(
        backend, "unet", false, reader, loras, loraStrengths, &lora_mapping, backingDir, outBuffer, outputPath, cancelled, errorOut,
        [&](QnnGraphBuilder& gb) { buildUnetGraphBody(gb, reader, spec, pfx); });
}

bool buildAndSaveSdxlUnet(
    QnnBuilderBackend& backend,
    SafeTensorReader& reader,
    const std::vector<SafeTensorReader*>& loras,
    const std::vector<float>& loraStrengths,
    const std::string& outputPath,
    const std::atomic<bool>& cancelled,
    std::string& errorOut) {
    const std::string pfx = findUnetPrefix(reader);
    const std::string backingDir = outputPath.substr(0, outputPath.find_last_of('/'));
    const UnetSpec spec{
        .latentH = 128,
        .latentW = 128,
        .contextTokens = 231,
        .contextDim = 2048,
        .numHeadChannels = 64,
        .numHeads = -1,
        .isSdxl = true,
        .channelMult = {1, 2, 4},
        .attentionResolutions = {4, 2},
        .transformerDepth = {1, 2, 10},
    };
    return finalizeContextToBuffer(
        backend, "unet", true, reader, loras, loraStrengths, &sdxl_lora_mapping, backingDir, nullptr, outputPath, cancelled, errorOut,
        [&](QnnGraphBuilder& gb) { buildUnetGraphBody(gb, reader, spec, pfx); });
}

bool buildAndSaveVaeDecoder(
    QnnBuilderBackend& backend,
    SafeTensorReader& reader,
    int latentH,
    int latentW,
    bool nchwIo,
    const std::string& outputPath,
    const std::atomic<bool>& cancelled,
    std::string& errorOut) {
    const std::string pfx = findVaePrefix(reader);
    const std::string backingDir = outputPath.substr(0, outputPath.find_last_of('/'));
    const std::vector<SafeTensorReader*> noLoras;
    const std::vector<float> noStrengths;

    return finalizeContextToBuffer(
        backend, "vae_decoder", true, reader, noLoras, noStrengths, nullptr, backingDir, nullptr, outputPath, cancelled, errorOut,
        [&](QnnGraphBuilder& gb) {
            int curH = latentH;
            int curW = latentW;
            std::string h;
            if (nchwIo) {
                const std::string zIn = gb.addInput("latent", {1, 4, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW)});
                h = gb.transpose(zIn, {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), 4}, {0, 2, 3, 1}, "dec_in_nhwc");
            } else {
                h = gb.addInput("latent", {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), 4});
            }

            if (reader.has_tensor(pfx + "post_quant_conv.weight")) {
                h = gb.conv2d(h, pfx + "post_quant_conv.weight", pfx + "post_quant_conv.bias",
                              4, 4, 1, 1, curH, curW, 1, 1, 0, 0, 0, 0, "pqc");
            }
            const std::string dPfx = pfx + "decoder.";
            int ch = 512;
            h = gb.conv2d(h, dPfx + "conv_in.weight", dPfx + "conv_in.bias",
                          ch, 4, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1, "dec_in");
            h = buildVaeResnetBlock(gb, dPfx + "mid.block_1", h, ch, ch, curH, curW, "dec_mid1");
            h = buildVaeAttnBlock(gb, dPfx + "mid.attn_1", h, ch, curH, curW, "dec_attn");
            h = buildVaeResnetBlock(gb, dPfx + "mid.block_2", h, ch, ch, curH, curW, "dec_mid2");

            static constexpr int kChMult[4] = {1, 2, 4, 4};
            for (int i = 3; i >= 0; --i) {
                const int outCh = 128 * kChMult[i];
                for (int j = 0; j < 3; ++j) {
                    const std::string bKey = dPfx + "up." + std::to_string(i) + ".block." + std::to_string(j);
                    h = buildVaeResnetBlock(gb, bKey, h, ch, outCh, curH, curW,
                                            "dec_u" + std::to_string(i) + "_" + std::to_string(j));
                    ch = outCh;
                }
                if (i != 0) {
                    h = gb.resizeNearest2x(h, static_cast<uint32_t>(ch), static_cast<uint32_t>(curH), static_cast<uint32_t>(curW),
                                           "dec_up" + std::to_string(i));
                    curH *= 2;
                    curW *= 2;
                    const std::string upConv = dPfx + "up." + std::to_string(i) + ".upsample.conv";
                    h = gb.conv2d(h, upConv + ".weight", upConv + ".bias", ch, ch, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1,
                                  "dec_upc" + std::to_string(i));
                }
            }

            h = gb.groupNorm(h, dPfx + "norm_out.weight", dPfx + "norm_out.bias",
                             {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), static_cast<uint32_t>(ch)},
                             32, 1e-6f, "dec_nout");
            h = gb.silu(h, {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), static_cast<uint32_t>(ch)}, "dec_silu");
            if (nchwIo) {
                h = gb.conv2d(h, dPfx + "conv_out.weight", dPfx + "conv_out.bias",
                              3, ch, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1, "dec_cout");
                gb.transpose(h, {1, 3, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW)},
                             {0, 3, 1, 2}, "dec_out_nchw", "image", QNN_TENSOR_TYPE_APP_READ);
            } else {
                gb.conv2d(h, dPfx + "conv_out.weight", dPfx + "conv_out.bias",
                          3, ch, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1, "dec_cout", "image", QNN_TENSOR_TYPE_APP_READ);
            }
        });
}

bool buildAndSaveVaeEncoder(
    QnnBuilderBackend& backend,
    SafeTensorReader& reader,
    int imageH,
    int imageW,
    bool nchwIo,
    const std::string& outputPath,
    const std::atomic<bool>& cancelled,
    std::string& errorOut) {
    const std::string pfx = findVaePrefix(reader);
    const std::string backingDir = outputPath.substr(0, outputPath.find_last_of('/'));
    const std::vector<SafeTensorReader*> noLoras;
    const std::vector<float> noStrengths;

    return finalizeContextToBuffer(
        backend, "vae_encoder", true, reader, noLoras, noStrengths, nullptr, backingDir, nullptr, outputPath, cancelled, errorOut,
        [&](QnnGraphBuilder& gb) {
            int curH = imageH;
            int curW = imageW;
            std::string h;
            if (nchwIo) {
                const std::string imgIn = gb.addInput("image", {1, 3, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW)});
                h = gb.transpose(imgIn, {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), 3}, {0, 2, 3, 1}, "enc_in_nhwc");
            } else {
                h = gb.addInput("image", {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), 3});
            }

            const std::string ePfx = pfx + "encoder.";
            int ch = 128;
            h = gb.conv2d(h, ePfx + "conv_in.weight", ePfx + "conv_in.bias",
                          ch, 3, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1, "enc_in");

            static constexpr int kChMult[4] = {1, 2, 4, 4};
            for (int i = 0; i < 4; ++i) {
                const int outCh = 128 * kChMult[i];
                for (int j = 0; j < 2; ++j) {
                    const std::string bKey = ePfx + "down." + std::to_string(i) + ".block." + std::to_string(j);
                    h = buildVaeResnetBlock(gb, bKey, h, ch, outCh, curH, curW,
                                            "enc_d" + std::to_string(i) + "_" + std::to_string(j));
                    ch = outCh;
                }
                if (i != 3) {
                    h = gb.padAsymBottomRight(h, static_cast<uint32_t>(ch), static_cast<uint32_t>(curH), static_cast<uint32_t>(curW),
                                              "enc_pad" + std::to_string(i));
                    curH /= 2;
                    curW /= 2;
                    const std::string dsConv = ePfx + "down." + std::to_string(i) + ".downsample.conv";
                    h = gb.conv2d(h, dsConv + ".weight", dsConv + ".bias", ch, ch, 3, 3, curH, curW, 2, 2, 0, 0, 0, 0,
                                  "enc_ds" + std::to_string(i));
                }
            }

            h = buildVaeResnetBlock(gb, ePfx + "mid.block_1", h, ch, ch, curH, curW, "enc_mid1");
            h = buildVaeAttnBlock(gb, ePfx + "mid.attn_1", h, ch, curH, curW, "enc_attn");
            h = buildVaeResnetBlock(gb, ePfx + "mid.block_2", h, ch, ch, curH, curW, "enc_mid2");
            h = gb.groupNorm(h, ePfx + "norm_out.weight", ePfx + "norm_out.bias",
                             {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), static_cast<uint32_t>(ch)},
                             32, 1e-6f, "enc_nout");
            h = gb.silu(h, {1, static_cast<uint32_t>(curH), static_cast<uint32_t>(curW), static_cast<uint32_t>(ch)}, "enc_silu");
            h = gb.conv2d(h, ePfx + "conv_out.weight", ePfx + "conv_out.bias",
                          8, ch, 3, 3, curH, curW, 1, 1, 1, 1, 1, 1, "enc_cout");

            if (reader.has_tensor(pfx + "quant_conv.weight")) {
                h = gb.conv2d(h, pfx + "quant_conv.weight", pfx + "quant_conv.bias",
                              8, 8, 1, 1, curH, curW, 1, 1, 0, 0, 0, 0, "qc");
            }

            const uint32_t uH = static_cast<uint32_t>(curH);
            const uint32_t uW = static_cast<uint32_t>(curW);
            auto meanLogvar = gb.split(
                h, 3, {4},
                {{1, uH, uW, 4}, {1, uH, uW, 4}},
                "enc_sp",
                {nchwIo ? "" : "mean", ""},
                nchwIo ? QNN_TENSOR_TYPE_NATIVE : QNN_TENSOR_TYPE_APP_READ);
            if (gb.aborted()) return;

            std::string logvar = gb.reluMinMax(meanLogvar[1], {1, uH, uW, 4}, -30.0f, 20.0f, "enc_lv_clamp");
            const std::string halfConst = gb.uniq("enc_half");
            gb.staticFp16(halfConst, {1, 1, 1, 1}, {0.5f});
            logvar = gb.binary(logvar, halfConst, QNN_OP_ELEMENT_WISE_MULTIPLY, {1, uH, uW, 4}, "enc_lv_half");

            if (nchwIo) {
                gb.transpose(meanLogvar[0], {1, 4, uH, uW}, {0, 3, 1, 2}, "enc_mean_nchw", "mean", QNN_TENSOR_TYPE_APP_READ);
                std::string stdNhwc = gb.unary(logvar, QNN_OP_ELEMENT_WISE_EXP, {1, uH, uW, 4}, "enc_std_nhwc");
                gb.transpose(stdNhwc, {1, 4, uH, uW}, {0, 3, 1, 2}, "enc_std_nchw", "std", QNN_TENSOR_TYPE_APP_READ);
            } else {
                gb.unary(logvar, QNN_OP_ELEMENT_WISE_EXP, {1, uH, uW, 4}, "enc_std", "std", QNN_TENSOR_TYPE_APP_READ);
            }
        });
}

}  // namespace aura
