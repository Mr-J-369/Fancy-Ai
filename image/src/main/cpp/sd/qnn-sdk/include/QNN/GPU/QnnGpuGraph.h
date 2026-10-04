//==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// All Rights Reserved.
// Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

/**
 *  @file
 *  @brief  A header which defines the QNN GPU specialization of the QnnGraph.h interface.
 */

#ifndef QNN_GPU_GRAPH_H
#define QNN_GPU_GRAPH_H

#ifdef __cplusplus
#include <cstdint>
#else
#include <stdint.h>
#endif

#include "QnnGraph.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Handshake flag to identify QnnGpuGraph_CustomConfigV2_t structures.
 *
 * This value (0x7FFFFFFF) is used to distinguish V2 custom configs from V1 configs.
 * Clients MUST set the handshake field to this value when using QnnGpuGraph_CustomConfigV2_t.
 *
 * The value was chosen to be unable to appear as a valid QnnGpu_Precision_t enum value
 * in V1 configs, ensuring reliable version detection.
 *
 * If the handshake doesn't match, the config will be interpreted as V1 format.
 */

#define QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG 0x7FFFFFFF

/**
 * @brief An enum which defines the different tensor optimization options. A
 *        tensor may be optimized to the specified QnnGpu_Precision_t when it
 *        is a graph tensor that is not a graph input or a graph output and
 *        does not connect two operations from different op packages.
 */
typedef enum {
  /// Sets the precision mode to floating point 32-bit (FP32)
  QNN_GPU_PRECISION_FP32 = 0,
  /// Sets the precision mode to floating point 16-bit (FP16)
  QNN_GPU_PRECISION_FP16 = 1,
  /// Sets the precision mode to FP16 for storage and FP32 for calculations
  QNN_GPU_PRECISION_HYBRID = 2,
  /// Uses the tensor data type provided by the user (default)
  QNN_GPU_PRECISION_USER_PROVIDED = 3,
  /// Unused, reserved for v2 handshake and ensures 32 bits
  QNN_GPU_PRECISION_UNDEFINED = QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG,
} QnnGpu_Precision_t;

/**
 * @brief A struct which defines the QNN GPU graph custom configuration options.
 *        Objects of this type are to be referenced through QnnGraph_CustomConfig_t.
 */
typedef struct {
  QnnGpu_Precision_t precision;
  uint8_t disableMemoryOptimizations;
  uint8_t disableNodeOptimizations;
  uint8_t disableQueueRecording;
} QnnGpuGraph_CustomConfig_t;

typedef enum {
  /// Set the precision mode (FP32, FP16, HYBRID, USER_PROVIDED)
  QNN_GPU_GRAPH_CONFIG_OPTION_PRECISION = 0,
  /// Flag to disable Backend's memory optimizations
  QNN_GPU_GRAPH_CONFIG_OPTION_DISABLE_MEMORY_OPTIMIZATIONS = 1,
  /// Flag to disable Backend's node optimizations
  QNN_GPU_GRAPH_CONFIG_OPTION_DISABLE_NODE_OPTIMIZATIONS = 2,
  /// Flag to disable Backend's recordable command queue
  QNN_GPU_GRAPH_CONFIG_OPTION_DISABLE_QUEUE_RECORDING = 3,
  /// QnnGpuGraph_FenceConfig_t that covers possible fencing related options
  QNN_GPU_GRAPH_CONFIG_OPTION_FENCE_CONFIG = 4,
  /// Unused, ensures 32 bits
  QNN_GPU_GRAPH_CONFIG_OPTION_UNDEFINED = 0x7FFFFFFF,
} QnnGpuGraph_ConfigOption_t;

typedef enum {
  QNN_GPU_GRAPH_FENCE_CONFIG_VERSION_V1 = 0,
  QNN_GPU_GRAPH_FENCE_CONFIG_VERSION_UNDEFINED = 0x7FFFFFFF,
} QnnGpuGraph_FenceConfigVersion_t;

typedef int32_t* FenceHandle;

typedef struct {
  uint32_t* numInputFences; // Pointer to client-owned number of input fences, read during execute
  FenceHandle* inputFenceHandles; // Pointer to client-owned array of input FD fences, read during execute
  uint8_t enableOutputFence; // Flag to notify Backend to fill in outputFence
  FenceHandle outputFence; // Pointer to client-owned FD location that Backend will fill with output fence during execute
} QnnGpuGraph_FenceConfigV1_t;

typedef struct {
 QnnGpuGraph_FenceConfigVersion_t version;
 union UNNAMED {
  QnnGpuGraph_FenceConfigV1_t fenceConfigV1;
 };
} QnnGpuGraph_FenceConfig_t;

/**
 * @brief Versioned custom configuration structure for QNN GPU graphs.
 *
 * This structure provides backward-compatible extension of QnnGpuGraph_CustomConfig_t.
 * The handshake field MUST be set to QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG.
 */

typedef struct {
  uint32_t handshake; // Must be 0x7FFFFFFF (QNN_GPU_GRAPH_V2_HANDSHAKE_FLAG)
  QnnGpuGraph_ConfigOption_t option;
  union UNNAMED {
    QnnGpu_Precision_t precision;
    uint8_t disableMemoryOptimizations;
    uint8_t disableNodeOptimizations;
    uint8_t disableQueueRecording;
    QnnGpuGraph_FenceConfig_t fenceConfig;
  };
} QnnGpuGraph_CustomConfigV2_t;

// clang-format off
/// QnnGpuGraph_CustomConfig_t initializer macro
#define QNN_GPU_GRAPH_CUSTOM_CONFIG_INIT                              \
  {                                                                   \
    QNN_GPU_PRECISION_USER_PROVIDED,   /*precision*/                  \
    0u,                                /*disableMemoryOptimizations*/ \
    0u,                                /*disableNodeOptimizations*/   \
    0u                                 /*disableQueueRecording*/      \
  }
// clang-format on

#ifdef __cplusplus
}  // extern "C"
#endif

#endif
