#include "qnn_backend.h"
#include "file_backed_buffer.h"

#include "QnnInterface.h"
#include "QnnLog.h"
#include "System/QnnSystemInterface.h"
#include "HTP/QnnHtpContext.h"
#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"

#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>

#if FANCY_INTEGRITY_REQUIRED
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "fancyqnn", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "fancyqnn", __VA_ARGS__)
#endif

namespace aura {

namespace {

struct QnnFunctions {
    QNN_INTERFACE_VER_TYPE qnnInterface{};
    QNN_SYSTEM_INTERFACE_VER_TYPE qnnSystemInterface{};
};

struct TensorMetadata {
    Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
    std::string name;
    std::vector<uint32_t> dimensions;
    std::vector<uint8_t> dynamicDimensions;
    std::vector<Qnn_ScaleOffset_t> axisScaleOffsets;
};

struct GraphInfo {
    Qnn_GraphHandle_t graph = nullptr;
    std::string name;
    std::vector<TensorMetadata> inputs;
    std::vector<TensorMetadata> outputs;
    std::vector<FileBackedBuffer> inputBuffers;
    std::vector<FileBackedBuffer> outputBuffers;
};

using GetQnnProviders = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
using GetQnnSystemProviders = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*);

QnnFunctions g_fp{};
std::string g_bufferDirectory;
void* g_backendLib = nullptr;
void* g_systemLib = nullptr;
Qnn_BackendHandle_t g_backend = nullptr;
Qnn_DeviceHandle_t g_device = nullptr;
Qnn_LogHandle_t g_log = nullptr;
bool g_ready = false;

QnnHtpDevice_PerfInfrastructure_t g_perfInfra{};
uint32_t g_powerConfigId = 0;
bool g_perfReady = false;

const char* tensorName(const Qnn_Tensor_t& t) { return t.v1.name; }
Qnn_DataType_t tensorDataType(const Qnn_Tensor_t& t) { return t.v1.dataType; }
Qnn_QuantizeParams_t tensorQuantization(const Qnn_Tensor_t& t) { return t.v1.quantizeParams; }
uint32_t tensorRank(const Qnn_Tensor_t& t) { return t.v1.rank; }
uint32_t* tensorDimensions(const Qnn_Tensor_t& t) { return t.v1.dimensions; }
Qnn_ClientBuffer_t tensorBuffer(const Qnn_Tensor_t& t) { return t.v1.clientBuf; }

bool loadInterfaces() {
    g_backendLib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_backendLib) {
        LOGE("initBackend: cannot load libQnnHtp.so: %s", dlerror());
        return false;
    }
    const auto getProviders = reinterpret_cast<GetQnnProviders>(dlsym(g_backendLib, "QnnInterface_getProviders"));
    const QnnInterface_t** providers = nullptr;
    uint32_t providerCount = 0;
    if (!getProviders || getProviders(&providers, &providerCount) != QNN_SUCCESS || !providers) {
        LOGE("initBackend: QnnInterface_getProviders failed");
        return false;
    }
    bool found = false;
    for (uint32_t i = 0; i < providerCount; ++i) {
        if (providers[i] && providers[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
            providers[i]->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
            g_fp.qnnInterface = providers[i]->QNN_INTERFACE_VER_NAME;
            found = true;
            break;
        }
    }
    if (!found) {
        LOGE("initBackend: compatible QNN interface not found");
        return false;
    }

    g_systemLib = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_systemLib) {
        LOGE("initBackend: cannot load libQnnSystem.so: %s", dlerror());
        return false;
    }
    const auto getSystemProviders = reinterpret_cast<GetQnnSystemProviders>(
        dlsym(g_systemLib, "QnnSystemInterface_getProviders"));
    const QnnSystemInterface_t** sysProviders = nullptr;
    uint32_t sysCount = 0;
    if (!getSystemProviders || getSystemProviders(&sysProviders, &sysCount) != QNN_SUCCESS || !sysProviders) {
        LOGE("initBackend: QnnSystemInterface_getProviders failed");
        return false;
    }
    for (uint32_t i = 0; i < sysCount; ++i) {
        if (sysProviders[i] && sysProviders[i]->systemApiVersion.major == QNN_SYSTEM_API_VERSION_MAJOR &&
            sysProviders[i]->systemApiVersion.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
            g_fp.qnnSystemInterface = sysProviders[i]->QNN_SYSTEM_INTERFACE_VER_NAME;
            return true;
        }
    }
    LOGE("initBackend: compatible QNN system interface not found");
    return false;
}

bool copyTensor(const Qnn_Tensor_t& src, TensorMetadata& dst) {
    if (src.version != QNN_TENSOR_VERSION_1 && src.version != QNN_TENSOR_VERSION_2) {
        LOGE("Unsupported QNN tensor version %d", static_cast<int>(src.version));
        return false;
    }
    dst.tensor = src;
    if (src.v1.name) {
        dst.name = src.v1.name;
        dst.tensor.v1.name = dst.name.c_str();
    }
    if (src.v1.rank > 0) {
        if (!src.v1.dimensions) return false;
        dst.dimensions.assign(src.v1.dimensions, src.v1.dimensions + src.v1.rank);
        dst.tensor.v1.dimensions = dst.dimensions.data();
    }
    if (src.version == QNN_TENSOR_VERSION_2 && src.v2.isDynamicDimensions) {
        dst.dynamicDimensions.assign(src.v2.isDynamicDimensions, src.v2.isDynamicDimensions + src.v2.rank);
        dst.tensor.v2.isDynamicDimensions = dst.dynamicDimensions.data();
    }
    auto& q = dst.tensor.v1.quantizeParams;
    if (q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET &&
        q.axisScaleOffsetEncoding.numScaleOffsets > 0) {
        const auto* offsets = q.axisScaleOffsetEncoding.scaleOffset;
        if (!offsets) return false;
        dst.axisScaleOffsets.assign(offsets, offsets + q.axisScaleOffsetEncoding.numScaleOffsets);
        q.axisScaleOffsetEncoding.scaleOffset = dst.axisScaleOffsets.data();
    }
    return true;
}

bool copyTensors(const Qnn_Tensor_t* src, uint32_t count, std::vector<TensorMetadata>& dst) {
    if (count > 0 && !src) return false;
    dst.resize(count);
    for (uint32_t i = 0; i < count; ++i) {
        if (!copyTensor(src[i], dst[i])) return false;
    }
    return true;
}

bool copyGraph(const QnnSystemContext_GraphInfo_t& src, GraphInfo& dst) {
    const char* name = nullptr;
    const Qnn_Tensor_t* in = nullptr;
    const Qnn_Tensor_t* out = nullptr;
    uint32_t inCount = 0, outCount = 0;
    if (src.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
        name = src.graphInfoV1.graphName; in = src.graphInfoV1.graphInputs; inCount = src.graphInfoV1.numGraphInputs;
        out = src.graphInfoV1.graphOutputs; outCount = src.graphInfoV1.numGraphOutputs;
    } else if (src.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
        name = src.graphInfoV2.graphName; in = src.graphInfoV2.graphInputs; inCount = src.graphInfoV2.numGraphInputs;
        out = src.graphInfoV2.graphOutputs; outCount = src.graphInfoV2.numGraphOutputs;
    } else if (src.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
        name = src.graphInfoV3.graphName; in = src.graphInfoV3.graphInputs; inCount = src.graphInfoV3.numGraphInputs;
        out = src.graphInfoV3.graphOutputs; outCount = src.graphInfoV3.numGraphOutputs;
    } else {
        LOGE("Unsupported QNN graph metadata version %d", static_cast<int>(src.version));
        return false;
    }
    if (!name) return false;
    dst.name = name;
    return copyTensors(in, inCount, dst.inputs) && copyTensors(out, outCount, dst.outputs);
}

bool copyMetadata(const QnnSystemContext_BinaryInfo_t* binInfo, std::vector<GraphInfo>& dst) {
    if (!binInfo) return false;
    const QnnSystemContext_GraphInfo_t* graphs = nullptr;
    uint32_t count = 0;
    if (binInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
        graphs = binInfo->contextBinaryInfoV1.graphs; count = binInfo->contextBinaryInfoV1.numGraphs;
    } else if (binInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
        graphs = binInfo->contextBinaryInfoV2.graphs; count = binInfo->contextBinaryInfoV2.numGraphs;
    } else if (binInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
        graphs = binInfo->contextBinaryInfoV3.graphs; count = binInfo->contextBinaryInfoV3.numGraphs;
    } else {
        LOGE("Unsupported QNN binary metadata version %d", static_cast<int>(binInfo->version));
        return false;
    }
    if (!graphs || count == 0) return false;
    dst.resize(count);
    for (uint32_t i = 0; i < count; ++i) {
        if (!copyGraph(graphs[i], dst[i])) return false;
    }
    return true;
}

size_t elementSize(Qnn_DataType_t type) {
    switch (type) {
        case QNN_DATATYPE_INT_8: case QNN_DATATYPE_UINT_8: case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8: case QNN_DATATYPE_BOOL_8: return 1;
        case QNN_DATATYPE_INT_16: case QNN_DATATYPE_UINT_16: case QNN_DATATYPE_FLOAT_16:
        case QNN_DATATYPE_SFIXED_POINT_16: case QNN_DATATYPE_UFIXED_POINT_16: return 2;
        case QNN_DATATYPE_INT_32: case QNN_DATATYPE_UINT_32: case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_SFIXED_POINT_32: case QNN_DATATYPE_UFIXED_POINT_32: return 4;
        case QNN_DATATYPE_INT_64: case QNN_DATATYPE_UINT_64: case QNN_DATATYPE_FLOAT_64: return 8;
        default: return 0;
    }
}

size_t elementCount(const Qnn_Tensor_t& tensor) {
    const auto rank = tensorRank(tensor);
    const auto* dimensions = tensorDimensions(tensor);
    if (rank > 0 && !dimensions) return 0;
    size_t count = 1;
    for (uint32_t i = 0; i < rank; ++i) count *= dimensions[i];
    return count;
}

bool prepareTensors(
    const std::vector<TensorMetadata>& metadata,
    std::vector<Qnn_Tensor_t>& tensors,
    std::vector<FileBackedBuffer>& buffers) {
    tensors.resize(metadata.size());
    buffers.resize(metadata.size());
    for (size_t i = 0; i < metadata.size(); ++i) {
        tensors[i] = metadata[i].tensor;
        const size_t bytes = elementCount(tensors[i]) * elementSize(tensorDataType(tensors[i]));
        if (bytes == 0 || bytes > std::numeric_limits<uint32_t>::max()) {
            LOGE("Unsupported or empty QNN tensor %s", tensorName(tensors[i]));
            return false;
        }
        if (!buffers[i].resize(bytes, g_bufferDirectory)) {
            LOGE("QNN host tensor backing failed: %zu bytes, errno=%d", bytes, errno);
            return false;
        }
        std::memset(buffers[i].data(), 0, bytes);
        tensors[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
        tensors[i].v1.clientBuf = {buffers[i].data(), static_cast<uint32_t>(bytes)};
    }
    return true;
}

void applyHtpPerformanceVote() {
    if (!g_perfReady) return;
    QnnHtpPerfInfrastructure_PowerConfig_t dcvs = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT;
    dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    dcvs.dcvsV3Config.contextId       = g_powerConfigId;
    dcvs.dcvsV3Config.setDcvsEnable   = 1;
    dcvs.dcvsV3Config.dcvsEnable      = 0;
    dcvs.dcvsV3Config.powerMode       = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    dcvs.dcvsV3Config.setSleepLatency = 1;
    dcvs.dcvsV3Config.sleepLatency    = 40;
    dcvs.dcvsV3Config.setSleepDisable = 1;
    dcvs.dcvsV3Config.sleepDisable    = 1;
    dcvs.dcvsV3Config.setBusParams    = 1;
    dcvs.dcvsV3Config.busVoltageCornerMin = dcvs.dcvsV3Config.busVoltageCornerTarget =
        dcvs.dcvsV3Config.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_TURBO;
    dcvs.dcvsV3Config.setCoreParams   = 1;
    dcvs.dcvsV3Config.coreVoltageCornerMin = dcvs.dcvsV3Config.coreVoltageCornerTarget =
        dcvs.dcvsV3Config.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_TURBO;
    const QnnHtpPerfInfrastructure_PowerConfig_t* cfgs[] = { &dcvs, nullptr };
    if (g_perfInfra.setPowerConfig(g_powerConfigId, cfgs) != QNN_SUCCESS) {
        LOGI("htp perf: TURBO power config failed, falling back to NOM_PLUS");
        dcvs.dcvsV3Config.busVoltageCornerMin = dcvs.dcvsV3Config.busVoltageCornerTarget =
            dcvs.dcvsV3Config.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_NOM_PLUS;
        dcvs.dcvsV3Config.coreVoltageCornerMin = dcvs.dcvsV3Config.coreVoltageCornerTarget =
            dcvs.dcvsV3Config.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_NOM_PLUS;
        if (g_perfInfra.setPowerConfig(g_powerConfigId, cfgs) != QNN_SUCCESS)
            LOGE("htp perf: fallback setPowerConfig failed");
    }

    QnnHtpPerfInfrastructure_PowerConfig_t poll = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT;
    poll.option                = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
    poll.rpcPollingTimeConfig  = 0;
    const QnnHtpPerfInfrastructure_PowerConfig_t* rpcCfgs[] = { &poll, nullptr };
    if (g_perfInfra.setPowerConfig(g_powerConfigId, rpcCfgs) != QNN_SUCCESS)
        LOGE("htp perf: disabling RPC polling failed");
}

void setupHtpPerformanceMode() {
    QnnDevice_Infrastructure_t deviceInfra = nullptr;
    if (!g_fp.qnnInterface.deviceGetInfrastructure ||
        g_fp.qnnInterface.deviceGetInfrastructure(&deviceInfra) != QNN_SUCCESS || !deviceInfra) {
        LOGE("htp perf: deviceGetInfrastructure unavailable — DSP left at ambient clock");
        return;
    }
    auto* htpInfra = static_cast<QnnHtpDevice_Infrastructure_t*>(deviceInfra);
    g_perfInfra = htpInfra->perfInfra;
    if (!g_perfInfra.createPowerConfigId || !g_perfInfra.setPowerConfig ||
        !g_perfInfra.destroyPowerConfigId ||
        g_perfInfra.createPowerConfigId(0, 0, &g_powerConfigId) != QNN_SUCCESS ||
        g_powerConfigId == 0) {
        g_powerConfigId = 0;
        LOGE("htp perf: createPowerConfigId failed — DSP left at ambient clock");
        return;
    }
    g_perfReady = true;
    applyHtpPerformanceVote();
    LOGI("htp perf: turbo profile active (TURBO corners, 40us latency, sleep disabled, powerConfigId=%u)", g_powerConfigId);
}

}  // namespace

bool QnnGraphRunner::initBackend(const std::string& libDir, const std::string& skelDir) {
    if (g_ready) {
        if (!g_perfReady) setupHtpPerformanceMode();
        return true;
    }
    std::string adsp = skelDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
    (void)libDir;
    g_bufferDirectory = skelDir;
    if (!loadInterfaces()) return false;
    if (g_fp.qnnInterface.logCreate) g_fp.qnnInterface.logCreate(nullptr, QNN_LOG_LEVEL_WARN, &g_log);
    if (QNN_SUCCESS != g_fp.qnnInterface.backendCreate(g_log, nullptr, &g_backend)) {
        LOGE("initBackend: backendCreate failed"); return false;
    }
    if (g_fp.qnnInterface.deviceCreate &&
        QNN_SUCCESS != g_fp.qnnInterface.deviceCreate(g_log, nullptr, &g_device)) {
        LOGI("initBackend: deviceCreate failed/unsupported — continuing with null device");
        g_device = nullptr;
    }
    setupHtpPerformanceMode();
    g_ready = true;
    LOGI("initBackend: QNN HTP ready (ADSP=%s)", adsp.c_str());
    return true;
}

void QnnGraphRunner::releasePerfVote() {
    if (!g_perfReady) return;
    LOGI("htp perf: releasing vote (powerConfigId=%u); backend stays up", g_powerConfigId);
    const Qnn_ErrorHandle_t error = g_perfInfra.destroyPowerConfigId(g_powerConfigId);
    if (error != QNN_SUCCESS) {
        LOGE("htp perf: destroyPowerConfigId failed, err=%lld; vote retained for retry",
             static_cast<long long>(error));
        return;
    }
    g_powerConfigId = 0;
    g_perfReady = false;
}

bool QnnGraphRunner::loadFromMemory(const uint8_t* data, size_t size) {
    QnnSystemContext_Handle_t sysCtx = nullptr;
    if (QNN_SUCCESS != g_fp.qnnSystemInterface.systemContextCreate(&sysCtx)) {
        LOGE("loadFromMemory: systemContextCreate failed"); return false;
    }
    const QnnSystemContext_BinaryInfo_t* binInfo = nullptr;
    Qnn_ContextBinarySize_t binInfoSize = 0;
    if (QNN_SUCCESS != g_fp.qnnSystemInterface.systemContextGetBinaryInfo(
            sysCtx, const_cast<uint8_t*>(data), size, &binInfo, &binInfoSize)) {
        LOGE("loadFromMemory: getBinaryInfo failed"); g_fp.qnnSystemInterface.systemContextFree(sysCtx); return false;
    }
    auto graphs = std::make_unique<std::vector<GraphInfo>>();
    if (!copyMetadata(binInfo, *graphs)) {
        LOGE("loadFromMemory: metadata copy failed");
        g_fp.qnnSystemInterface.systemContextFree(sysCtx);
        return false;
    }
    g_fp.qnnSystemInterface.systemContextFree(sysCtx);

    QnnHtpContext_CustomConfig_t htpInitAccel{};
    htpInitAccel.option = QNN_HTP_CONTEXT_CONFIG_OPTION_INIT_ACCELERATION;
    htpInitAccel.initAcceleration = true;

    QnnHtpContext_CustomConfig_t htpSkipValidation{};
    htpSkipValidation.option = QNN_HTP_CONTEXT_CONFIG_OPTION_SKIP_VALIDATION_ON_BINARY_SECTION;
    htpSkipValidation.skipValidationOnBinarySection = true;

    QnnContext_Config_t cfgInitAccel = QNN_CONTEXT_CONFIG_INIT;
    cfgInitAccel.option = QNN_CONTEXT_CONFIG_OPTION_CUSTOM;
    cfgInitAccel.customConfig = &htpInitAccel;

    QnnContext_Config_t cfgSkipValidation = QNN_CONTEXT_CONFIG_INIT;
    cfgSkipValidation.option = QNN_CONTEXT_CONFIG_OPTION_CUSTOM;
    cfgSkipValidation.customConfig = &htpSkipValidation;

    const QnnContext_Config_t* contextConfigs[] = {&cfgInitAccel, &cfgSkipValidation, nullptr};

    Qnn_ContextHandle_t context = nullptr;
    Qnn_ErrorHandle_t ctxErr = g_fp.qnnInterface.contextCreateFromBinary(
        g_backend, g_device, contextConfigs, const_cast<uint8_t*>(data), size, &context, nullptr);
    if (ctxErr != QNN_SUCCESS) {
        LOGI("loadFromMemory: accelerated contextCreate failed (%lu), falling back to standard create", ctxErr);
        ctxErr = g_fp.qnnInterface.contextCreateFromBinary(
            g_backend, g_device, nullptr, const_cast<uint8_t*>(data), size, &context, nullptr);
    }
    if (ctxErr != QNN_SUCCESS) {
        LOGE("loadFromMemory: contextCreateFromBinary failed"); return false;
    }
    ctx_ = context;
    graphsCount_ = static_cast<uint32_t>(graphs->size());
    graphsInfo_ = graphs.release();
    auto& loadedGraphs = *static_cast<std::vector<GraphInfo>*>(graphsInfo_);
    for (uint32_t i = 0; i < graphsCount_; ++i) {
        if (QNN_SUCCESS != g_fp.qnnInterface.graphRetrieve(
                context, loadedGraphs[i].name.c_str(), &loadedGraphs[i].graph)) {
            LOGE("loadFromMemory: graphRetrieve failed for %s", loadedGraphs[i].name.c_str());
            freeContext();
            return false;
        }
    }
    applyHtpPerformanceVote();
    return true;
}

void QnnGraphRunner::logIo() const {
    const auto* graphs = static_cast<const std::vector<GraphInfo>*>(graphsInfo_);
    if (!graphs || graphs->empty()) return;
    const auto dump = [](const char* tag, const std::vector<TensorMetadata>& tensors) {
        for (size_t i = 0; i < tensors.size(); ++i) {
            const auto& t = tensors[i].tensor;
            const auto rank = tensorRank(t);
            const auto* dims = tensorDimensions(t);
            char formatted[128] = {0};
            int offset = 0;
            for (uint32_t d = 0; d < rank && offset < 110; ++d) {
                offset += snprintf(formatted + offset, sizeof(formatted) - offset, "%u,", dims[d]);
            }
            const auto q = tensorQuantization(t);
            LOGI("  %s[%zu] name=%s dtype=0x%x dims=[%s] scale=%f offset=%d",
                 tag, i, tensorName(t), static_cast<unsigned>(tensorDataType(t)),
                 formatted, q.scaleOffsetEncoding.scale, q.scaleOffsetEncoding.offset);
        }
    };
    dump("IN", graphs->front().inputs);
    dump("OUT", graphs->front().outputs);
}

static void writeInput(Qnn_Tensor_t& tensor, const std::vector<float>& source) {
    void* buffer = tensorBuffer(tensor).data;
    if (!buffer) {
        LOGE("writeInput: null client buffer");
        return;
    }
    const size_t count = elementCount(tensor);
    const auto dataType = tensorDataType(tensor);
    const auto quantization = tensorQuantization(tensor);
    const float scale = quantization.scaleOffsetEncoding.scale;
    const int offset = quantization.scaleOffsetEncoding.offset;
    const size_t copied = std::min(count, source.size());

    switch (dataType) {
        case QNN_DATATYPE_FLOAT_32:
            std::memcpy(buffer, source.data(), copied * sizeof(float));
            break;
        case QNN_DATATYPE_FLOAT_16:
            for (size_t i = 0; i < copied; ++i) static_cast<__fp16*>(buffer)[i] = static_cast<__fp16>(source[i]);
            break;
        case QNN_DATATYPE_INT_32:
            for (size_t i = 0; i < copied; ++i) static_cast<int32_t*>(buffer)[i] = static_cast<int32_t>(lroundf(source[i]));
            break;
        case QNN_DATATYPE_UFIXED_POINT_16:
            for (size_t i = 0; i < copied; ++i) {
                static_cast<uint16_t*>(buffer)[i] = static_cast<uint16_t>(
                    std::min(65535L, std::max(0L, static_cast<long>(lroundf(source[i] / scale)) - offset)));
            }
            break;
        case QNN_DATATYPE_UFIXED_POINT_8:
            for (size_t i = 0; i < copied; ++i) {
                static_cast<uint8_t*>(buffer)[i] = static_cast<uint8_t>(
                    std::min(255L, std::max(0L, static_cast<long>(lroundf(source[i] / scale)) - offset)));
            }
            break;
        case QNN_DATATYPE_SFIXED_POINT_16:
            for (size_t i = 0; i < copied; ++i) {
                static_cast<int16_t*>(buffer)[i] = static_cast<int16_t>(static_cast<long>(lroundf(source[i] / scale)) - offset);
            }
            break;
        default:
            LOGE("writeInput: unsupported dtype 0x%x", static_cast<unsigned>(dataType));
    }
}

static void readOutput(Qnn_Tensor_t& tensor, std::vector<float>& destination) {
    void* buffer = tensorBuffer(tensor).data;
    const size_t count = elementCount(tensor);
    destination.resize(count);
    if (!buffer) {
        LOGE("readOutput: null client buffer");
        return;
    }
    const auto dataType = tensorDataType(tensor);
    const auto quantization = tensorQuantization(tensor);
    const float scale = quantization.scaleOffsetEncoding.scale;
    const int offset = quantization.scaleOffsetEncoding.offset;
    switch (dataType) {
        case QNN_DATATYPE_FLOAT_32:
            std::memcpy(destination.data(), buffer, count * sizeof(float));
            break;
        case QNN_DATATYPE_FLOAT_16:
            for (size_t i = 0; i < count; ++i) destination[i] = static_cast<float>(static_cast<__fp16*>(buffer)[i]);
            break;
        case QNN_DATATYPE_UFIXED_POINT_16:
            for (size_t i = 0; i < count; ++i) {
                destination[i] = static_cast<float>(static_cast<int>(static_cast<uint16_t*>(buffer)[i]) + offset) * scale;
            }
            break;
        case QNN_DATATYPE_UFIXED_POINT_8:
            for (size_t i = 0; i < count; ++i) {
                destination[i] = static_cast<float>(static_cast<int>(static_cast<uint8_t*>(buffer)[i]) + offset) * scale;
            }
            break;
        case QNN_DATATYPE_SFIXED_POINT_16:
            for (size_t i = 0; i < count; ++i) {
                destination[i] = static_cast<float>(static_cast<int>(static_cast<int16_t*>(buffer)[i]) + offset) * scale;
            }
            break;
        default:
            LOGE("readOutput: unsupported dtype 0x%x", static_cast<unsigned>(dataType));
            std::fill(destination.begin(), destination.end(), 0.0f);
    }
}

bool QnnGraphRunner::execute(
    const std::vector<std::vector<float>>& inputs,
    std::vector<std::vector<float>>& outputs) const {
    auto* graphs = static_cast<std::vector<GraphInfo>*>(graphsInfo_);
    if (!graphs || graphs->empty()) return false;
    auto& graph = graphs->front();

    std::vector<Qnn_Tensor_t> inTensors, outTensors;
    if (!prepareTensors(graph.inputs, inTensors, graph.inputBuffers) ||
        !prepareTensors(graph.outputs, outTensors, graph.outputBuffers)) {
        return false;
    }
    if (inputs.size() != inTensors.size()) {
        LOGE("execute: input count %zu != graph %zu", inputs.size(), inTensors.size());
    }
    for (size_t i = 0; i < inTensors.size() && i < inputs.size(); ++i) {
        writeInput(inTensors[i], inputs[i]);
    }

    const Qnn_ErrorHandle_t error = g_fp.qnnInterface.graphExecute(
        graph.graph,
        inTensors.data(), static_cast<uint32_t>(inTensors.size()),
        outTensors.data(), static_cast<uint32_t>(outTensors.size()),
        nullptr, nullptr);
    if (error != QNN_SUCCESS) {
        LOGE("execute: graphExecute err=%lld", static_cast<long long>(error));
        return false;
    }
    outputs.resize(outTensors.size());
    for (size_t i = 0; i < outTensors.size(); ++i) {
        readOutput(outTensors[i], outputs[i]);
    }
    return true;
}

static std::vector<TensorDesc> describeTensors(void* graphsInfo, bool inputs) {
    std::vector<TensorDesc> result;
    const auto* graphs = static_cast<const std::vector<GraphInfo>*>(graphsInfo);
    if (!graphs || graphs->empty()) return result;
    const auto& list = inputs ? graphs->front().inputs : graphs->front().outputs;
    for (const auto& metadata : list) {
        const auto& tensor = metadata.tensor;
        TensorDesc description;
        description.name = tensorName(tensor);
        description.dataType = tensorDataType(tensor);
        const auto rank = tensorRank(tensor);
        const auto* dimensions = tensorDimensions(tensor);
        for (uint32_t i = 0; i < rank; ++i) description.dims.push_back(dimensions[i]);
        result.push_back(std::move(description));
    }
    return result;
}

std::vector<TensorDesc> QnnGraphRunner::inputs() const { return describeTensors(graphsInfo_, true); }
std::vector<TensorDesc> QnnGraphRunner::outputs() const { return describeTensors(graphsInfo_, false); }

void QnnGraphRunner::freeContext() {
    if (ctx_ && g_fp.qnnInterface.contextFree) {
        g_fp.qnnInterface.contextFree(static_cast<Qnn_ContextHandle_t>(ctx_), nullptr);
    }
    ctx_ = nullptr;
    delete static_cast<std::vector<GraphInfo>*>(graphsInfo_);
    graphsInfo_ = nullptr;
    graphsCount_ = 0;
}

QnnGraphRunner::~QnnGraphRunner() { freeContext(); }

}  // namespace aura
