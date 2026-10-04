//==============================================================================
//
//  Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
//  All rights reserved.
//  Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

/** @file
 *  @brief QNN LPAI component Backend API.
 *
 *         The interfaces in this file work with the top level QNN
 *         API and supplements QnnBackend.h for LPAI backend
 */

#ifndef QNN_LPAI_BACKEND_H
#define QNN_LPAI_BACKEND_H

#include "QnnBackend.h"
#include "QnnLpaiTypes.h"

#ifndef __cplusplus
#include <stdbool.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

//=============================================================================
// Macros
//=============================================================================

//=============================================================================
// Data Types
//=============================================================================

/**
 * @brief An enum which defines the different backend custom config options
 */
typedef enum {
  // see QnnLpaiBackend_CustomConfigHwInfo_t
  QNN_LPAI_BACKEND_CUSTOM_CFG_HW_INFO = 0,
  // see QnnLpaiBackend_RpcPollingConfig_t
  QNN_LPAI_BACKEND_CUSTOM_CFG_RPC_POLLING = 1,
  // DEBUG: enable/disable eNPU core utilization measurement, see
  // QnnLpaiBackend_eNPUCoreUtilCfg_t. Enabling must be done before any other client app init.
  // Fails if unsupported by the eAI (RT) API; silently ignored if the requested state already
  // matches the current state. To read the measured data, use QnnLpaiBackend_getCoreUtilData()
  // (see QnnExtendedApi.h) which is callable from both island and non-island images.
  QNN_LPAI_BACKEND_CUSTOM_CFG_ENPU_CORE_UTIL = 2,
  // config field should point to char*, path to a custom prepare config JSON file supplying
  // model builder settings for graph preparation, e.g. {"platform_info_json": "...",
  // "cut_plan": "..."}. Unrecognized keys are ignored. Only honored during offline graph
  // preparation, i.e. by qnn-context-binary-generator; ignored on the runtime/inference path.
  // Takes precedence over QNN_LPAI_BACKEND_CUSTOM_CFG_PLATFORM_CFG: if both are set, the
  // platform config file is ignored and a warning is logged. The pointed-to string must remain
  // valid until QnnGraph_create() returns.
  QNN_LPAI_BACKEND_CUSTOM_CFG_PREPARE_CFG = 3,
  QNN_LPAI_BACKEND_CUSTOM_CFG_UNDEFINED = 0x7fffffff
} QnnLpaiBackend_CustomConfigOption_t;

/**
 * @brief An enum which defines the different targets supported by LPAI compilation.
 */
typedef enum {
  /// LPAI model will be compiled for x86
  QNN_LPAI_BACKEND_TARGET_X86 = 0,
  /// LPAI model will be compiled for ARM
  QNN_LPAI_BACKEND_TARGET_ARM = 1,
  /// LPAI model will be compiled for ADSP
  QNN_LPAI_BACKEND_TARGET_ADSP = 2,
  /// LPAI model will be compiled for TENSILICA
  QNN_LPAI_BACKEND_TARGET_TENSILICA = 3,
  /// UNKNOWN enum event that must not be used
  QNN_LPAI_BACKEND_TARGET_UNKNOWN = 0x7fffffff,
} QnnLpaiBackend_Target_t;

/**
 * @brief An enum which defines the version of LPAI Hardware.
 */
typedef enum {
  /// No LPAI HW will be used
  QNN_LPAI_BACKEND_HW_VERSION_NA   = 0,
  /// LPAI HW version v1
  QNN_LPAI_BACKEND_HW_VERSION_V1   = 0x00000001,
  /// LPAI HW version v2
  QNN_LPAI_BACKEND_HW_VERSION_V2   = 0x00000002,
  /// LPAI HW version v3
  QNN_LPAI_BACKEND_HW_VERSION_V3   = 0x00000003,
  /// LPAI HW version v4
  QNN_LPAI_BACKEND_HW_VERSION_V4   = 0x00000004,
  /// LPAI HW version v5
  QNN_LPAI_BACKEND_HW_VERSION_V5   = 0x00000005,
  /// LPAI HW version v5.1
  QNN_LPAI_BACKEND_HW_VERSION_V5_1 = 0x00010005,
  /// LPAI HW version v6
  QNN_LPAI_BACKEND_HW_VERSION_V6   = 0x00000006,
  /// LPAI HW version v7
  QNN_LPAI_BACKEND_HW_VERSION_V7   = 0x00000007,
    /// LPAI HW default version v7
  QNN_LPAI_BACKEND_HW_VERSION_DEFAULT = QNN_LPAI_BACKEND_HW_VERSION_V7,

  /// UNKNOWN enum event that must not be used
  QNN_LPAI_BACKEND_HW_VERSION_UNKNOWN = 0x7fffffff,
} QnnLpaiBackend_HwVersion_t;

//=============================================================================
// Public Functions
//=============================================================================

//------------------------------------------------------------------------------
//   Implementation Definition
//------------------------------------------------------------------------------

/**
 * @brief Structure describing the set of configurations supported by the backend.
 *        Objects of this type are to be referenced through QnnBackend_CustomConfig_t.
 */
typedef struct {
  uint32_t option;
  void* config;
} QnnLpaiBackend_CustomConfig_t;

// clang-format off
/// QnnLpaiBackend_CustomConfig_t initializer macro
#define QNN_LPAI_BACKEND_CUSTOM_CONFIG_INIT                        \
  {                                                                \
    QNN_LPAI_BACKEND_CUSTOM_CFG_UNDEFINED,           /*option*/    \
    NULL                                             /*config*/    \
  }
// clang-format on

typedef struct {
  QnnLpaiBackend_Target_t lpaiTarget;
  QnnLpaiBackend_HwVersion_t hwVersion;
} QnnLpaiBackend_CustomConfigHwInfo_t;

// clang-format off
/// QnnLpaiBackend_CustomConfigHwInfo_t initializer macro
#define QNN_LPAI_BACKEND_CUSTOM_CONFIG_HW_INFO_INIT                         \
  {                                                                         \
    QNN_LPAI_BACKEND_TARGET_ADSP,        /*lpaiTarget*/                     \
    QNN_LPAI_BACKEND_HW_VERSION_DEFAULT  /*hwVersion*/                      \
  }
// clang-format on

/**
 * @brief RPC polling configuration applied when creating an LPAI backend.
 */
typedef struct {
  // 0 disables all RPC polling; nonzero enables all RPC polling.
  uint32_t enable;

  // Duration DSP remains active polling for new inference requests after being woken up.
  uint32_t DSPPollingTimeoutUs;

  // How far in advance of estimated inference completion HLOS is woken up.
  uint32_t HLOSWakeupLeadTimeUs;

  // Maximum duration HLOS polls for the inference result once polling starts.
  uint32_t HLOSPollingTimeoutUs;
} QnnLpaiBackend_RpcPollingConfig_t;

// clang-format off
/// Default RPC polling enable state. Disabled by default so existing backend
/// behavior is preserved unless a client explicitly enables polling.
#define QNN_LPAI_BACKEND_RPC_POLLING_ENABLE_DEFAULT 0U

/// Default HLOS-to-ADSP polling timeout in microseconds. Once woken, DSP stays
/// active polling for this duration to reduce latency for back-to-back requests.
#define QNN_LPAI_BACKEND_RPC_POLLING_DSP_TIMEOUT_DEFAULT_US 100000U

/// Default ADSP-to-HLOS wakeup lead time in microseconds. DSP wakes HLOS this
/// far before estimated inference completion so HLOS can begin result polling.
#define QNN_LPAI_BACKEND_RPC_POLLING_HLOS_WAKEUP_LEAD_TIME_DEFAULT_US 200U

/// Default HLOS polling timeout in microseconds. After waking, HLOS polls for
/// the inference result up to this duration before returning to normal wait.
#define QNN_LPAI_BACKEND_RPC_POLLING_HLOS_TIMEOUT_DEFAULT_US 400U

/// QnnLpaiBackend_RpcPollingConfig_t initializer macro
#define QNN_LPAI_BACKEND_RPC_POLLING_CONFIG_INIT                                                 \
  {                                                                                              \
    QNN_LPAI_BACKEND_RPC_POLLING_ENABLE_DEFAULT,                   /*enable*/                    \
    QNN_LPAI_BACKEND_RPC_POLLING_DSP_TIMEOUT_DEFAULT_US,           /*DSPPollingTimeoutUs*/       \
    QNN_LPAI_BACKEND_RPC_POLLING_HLOS_WAKEUP_LEAD_TIME_DEFAULT_US, /*HLOSWakeupLeadTimeUs*/      \
    QNN_LPAI_BACKEND_RPC_POLLING_HLOS_TIMEOUT_DEFAULT_US           /*HLOSPollingTimeoutUs*/      \
  }
// clang-format on

/**
 * @brief Type representing the desired eNPU core utilization measurement state: true to enable
 *        core utilization measurement, false to disable it.
 *        Used with the QNN_LPAI_BACKEND_CUSTOM_CFG_ENPU_CORE_UTIL backend custom config.
 */
typedef bool QnnLpaiBackend_eNPUCoreUtilCfg_t;

/// QnnLpaiBackend_eNPUCoreUtilCfg_t initializer macro. Measurement is disabled by default.
#define QNN_LPAI_BACKEND_ENPU_CORE_UTIL_CFG_INIT false

/**
 * @brief Enum describing the set of properties supported by the backend.
 *        Objects of this type are to be referenced through QnnBackend_CustomProperty_t.
 */
typedef enum {
  // get the start address alignment and size alignment requirement of buffers, see
  // QnnLpaiBackend_BufferAlignmentReq_t
  QNN_LPAI_BACKEND_GET_PROP_ALIGNMENT_REQ = QNN_LPAI_BACKEND_GET_PROP_ALIGNMENT_REQ_DEFAULT,
  // indicate if cached binary buffer need to be persistent until QnnContext_free is called, return
  // bool
  // if true is returned, need to specify QNN_CONTEXT_CONFIG_PERSISTENT_BINARY during
  // QnnContext_createFromBinary
  QNN_LPAI_BACKEND_GET_PROP_REQUIRE_PERSISTENT_BINARY = QNN_LPAI_BACKEND_GET_PROP_REQUIRE_PERSISTENT_BINARY_DEFAULT,
  // get the number of cores in enpu
  // QnnLpaiBackend_eNPUCoreNum_t
  QNN_LPAI_BACKEND_GET_PROP_ENPU_CORE_NUM = QNN_LPAI_BACKEND_GET_PROP_ENPU_CORE_NUM_DEFAULT,
  // get the number of Macs and version number of the certain core
  // QnnLpaiBackend_eNPUCoreInfo_t
  QNN_LPAI_BACKEND_GET_PROP_ENPU_CORE_INFO = QNN_LPAI_BACKEND_GET_PROP_ENPU_CORE_INFO_DEFAULT,
  // get the current clock frequency in Hz of a specific eNPU core
  // caller must set coreIndex in QnnLpaiBackend_eNPUClkFreq_t before querying
  // QnnLpaiBackend_eNPUClkFreq_t
  QNN_LPAI_BACKEND_GET_PROP_ENPU_CLK_FREQ = QNN_LPAI_BACKEND_GET_PROP_ENPU_CLK_FREQ_DEFAULT,
  // Unused, present to ensure 32 bits.
  QNN_LPAI_BACKEND_GET_PROP_UNDEFINED = 0x7fffffff
} QnnLpaiBackend_GetPropertyOption_t;

typedef struct {
  // the start address of the buffer must be startAddrAlignment-byte aligned
  uint32_t startAddrAlignment;
  // the allocated buffer must be a multiple of sizeAlignment bytes
  uint32_t sizeAlignment;
} QnnLpaiBackend_BufferAlignmentReq_t;

// clang-format off
/// QnnLpaiBackend_BufferAlignmentReq_t initializer macro
#define QNN_LPAI_BACKEND_ALIGNMENT_REQ_INIT                          \
  {                                                                  \
    0u,                                      /*startAddrAlignment*/  \
    0u                                       /*sizeAlignment*/       \
  }
// clang-format on

/**
 * @brief Type representing the number of eNPU cores available.
 *        Used with QNN_LPAI_BACKEND_GET_PROP_ENPU_CORE_NUM property.
 */
typedef uint32_t QnnLpaiBackend_eNPUCoreNum_t;

#define QNN_LPAI_BACKEND_ENPU_CORE_NUM_INIT 0

/**
 * @brief Structure containing information about a specific eNPU core.
 *        Used with QNN_LPAI_BACKEND_GET_PROP_ENPU_CORE_INFO property.
 */
typedef struct {
  /// Index of the enpu core
  uint32_t coreIndex;
  /// Number of MAC (Multiply-Accumulate) units in the core
  uint32_t macCnt;
  /// Version of the enpu core (hardware/firmware version)
  uint32_t version;
} QnnLpaiBackend_eNPUCoreInfo_t;

// clang-format off
/// QnnLpaiBackend_eNPUCoreInfo_t initializer macro
#define QNN_LPAI_BACKEND_ENPU_CORE_INFO_INIT  \
  {                                         \
    0u, /*coreIndex*/                       \
    0u, /*macCnt*/                          \
    0u  /*version*/                         \
  }
// clang-format on

/**
 * @brief Structure for querying the clock frequency of a specific eNPU core.
 *        Used with QNN_LPAI_BACKEND_GET_PROP_ENPU_CLK_FREQ property.
 *        Caller must set coreIndex before calling QnnBackend_getProperty.
 */
typedef struct {
  /// Index of the eNPU core to query (input parameter, 0-based)
  uint32_t coreIndex;
  /// Current clock frequency of the specified core in Hz (output)
  uint32_t clkFreqHz;
} QnnLpaiBackend_eNPUClkFreq_t;

// clang-format off
/// QnnLpaiBackend_eNPUClkFreq_t initializer macro
#define QNN_LPAI_BACKEND_ENPU_CLK_FREQ_INIT  \
  {                                          \
    0u, /*coreIndex*/                        \
    0u  /*clkFreqHz*/                        \
  }
// clang-format on

// used by QnnBackend_getProperty
typedef struct {
  uint32_t option;
  void* property;
} QnnLpaiBackend_CustomProperty_t;

// clang-format off
/// QnnLpaiBackend_CustomProperty_t initializer macro
#define QNN_LPAI_BACKEND_CUSTOM_PROPERTY_INIT                        \
  {                                                                  \
    QNN_LPAI_BACKEND_GET_PROP_UNDEFINED,               /*option*/    \
    NULL                                               /*property*/  \
  }
// clang-format on

#ifdef __cplusplus
}  // extern "C"
#endif

#endif
