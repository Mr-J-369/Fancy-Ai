//==============================================================================
//
//  Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
//  All rights reserved.
//  Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

/**
 * @file
 * @brief A header file which defines APIs and config for QAIRT LABU tool.
 */

#ifndef QNN_LORA_ADAPTER_BIN_UPDATER_H
#define QNN_LORA_ADAPTER_BIN_UPDATER_H

#include "QnnCommon.h"
#include "QnnLog.h"

#ifdef __cplusplus
extern "C" {
#endif

//=============================================================================
// Macros
//=============================================================================

// Macro controlling visibility of LoraAdapterBinUpdater API
#ifndef QNN_LORA_ADAPTER_BIN_UPDATER_API
#define QNN_LORA_ADAPTER_BIN_UPDATER_API
#endif

/**
 * Enumeration of error codes
 */
typedef enum {
  /// Indicate success: QNN_LORA_ADAPTER_BIN_UPDATER_SUCCESS = 0
  QNN_LORA_ADAPTER_BIN_UPDATER_SUCCESS = 0,
  /// Invalid QnnLoraAdapterBinUpdaterApp handle
  QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_INVALID_HANDLE = 1,
  /// QnnLoraAdapterBinUpdaterApp Handle creation failure
  QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_HANDLE_CREATION_FAILURE = 2,
  /// Invalid QnnLoraAdapterBinUpdaterApp config
  QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_INVALID_CONFIG = 3,
  /// QnnLoraAdapterBinUpdaterApp creation failure
  QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_APP_CREATION_FAILURE = 4,
  /// QnnLoraAdapterBinUpdaterApp execution failure
  QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_RUN_FAILURE = 5,
  /// Failure in freeing QnnLoraAdapterBinUpdaterApp handle
  QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_FREE_HANDLE_FAILURE = 6,
  /// Unused, present to ensure 32 bits
  QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_UNDEFINED = 0x7FFFFFFF
} QnnLoraAdapterBinUpdater_ErrorCode_t;

/**
 * @brief This struct defines QnnLoraAdapterBinUpdater config options.
 */
typedef struct {
  /// Path to the modified adapter safetensor. The file contains the updated LoRA weights.
  /// Must not be NULL.
  const char* modifiedAdapterPath;
  /// Comma‑separated list of paths to additional adapter safetensors.
  /// These safetensors represent "other" adapters that are part of multi‑adapter concurrencies
  /// alongside the modified adapter.
  /// For example: If we have following concurrencies: A, B, C, A+B, A+C, B+c and adapter A is being
  /// modified then if the user wants to update A+B and A+C concurrencies then paths for adapter B
  /// and C must be provided.
  /// Optional
  /// - NULL means no additional adapter safetensors.
  /// - Non-NULL must be a comma separated list of paths.
  const char* otherAdapterPaths;
  /// Path to the DLC file containing Lora tensor transformations.
  /// Must not be NULL.
  const char* dlcPath;
  /// Path to the old adapter binaries files that should be updated with modified adapter.
  /// If there are multiple binaries, then they should be in comma separated format.
  /// Must not be NULL.
  const char* oldAdapterBinaryFiles;
  /// Directory to save output artifacts.  Defaults to "./output/" if not provided.
  const char* outputDir;
  /// Path to the Backend shared library.
  /// Must not be NULL.
  const char* backendPath;
  /// Flag to indicate whether safetensors should be dumped. Updated safetensors are dumped using
  /// this flag.
  /// Optional
  /// - 0 = Disabled (default).
  /// - non-zero = Enabled
  uint8_t dumpSafeTensors;
  /// Flag to indicate whether the updatable metadata should be removed.
  /// Optional
  /// - 0 = Disabled (default).
  /// - non-zero = Enabled
  uint8_t stripMetadata;
  /// QNN Log level for debugging purposes.
  /// Optional
  /// Specifies the minimum log level for messages.
  /// Defaults to QNN_LOG_LEVEL_ERROR if not explicitly set.
  QnnLog_Level_t logLevel;
} QnnLoraAdapterBinUpdaterConfigV1_t;

// clang-format off
/// Qnn_LoraAdapterBinUpdaterConfigV1_t initializer macro
#define QNN_LORA_ADAPTER_BIN_UPDATER_CONFIG_V1_INIT                           \
  {                                                                           \
    NULL,                                           /*modifiedAdapterPath*/   \
    NULL,                                           /*otherAdapterPaths*/     \
    NULL,                                           /*dlcPath*/               \
    NULL,                                           /*oldAdapterBinaryFiles*/ \
    NULL,                                           /*outputDir*/             \
    NULL,                                           /*backendPath*/           \
    0u,                                             /*dumpSafeTensors*/       \
    0u,                                             /*stripMetadata*/         \
    QNN_LOG_LEVEL_ERROR                             /*logLevel*/              \
  }
// clang-format on

/**
 * @brief Enum to distinguish various QnnLoraAdapterBinUpdaterConfig versions
 */
typedef enum {
  /// Enum to choose usage of QnnLoraAdapterBinUpdaterConfigV1_t in QnnLoraAdapterBinUpdaterConfig_t
  QNN_LORA_ADAPTER_BIN_UPDATER_CONFIG_VERSION_1 = 1,
  // Unused, present to ensure 32 bits.
  QNN_LORA_ADAPTER_BIN_UPDATER_CONFIG_VERSION_UNDEFINED = 0x7FFFFFFF
} QnnLoraAdapterBinUpdaterConfigVersion_t;

/**
 * @brief A struct which provides various versions of QnnLoraAdapterBinUpdaterConfig
 */
typedef struct {
  /// Version of the QnnLoraAdapterBinUpdaterConfig
  QnnLoraAdapterBinUpdaterConfigVersion_t version;
  union UNNAMED {
    /// QnnLoraAdapterBinUpdaterConfig version 1 (see QNN_LORA_ADAPTER_BIN_UPDATER_CONFIG_VERSION_1)
    QnnLoraAdapterBinUpdaterConfigV1_t v1;
  };
} QnnLoraAdapterBinUpdaterConfig_t;

// clang-format off
/// QnnLoraAdapterBinUpdaterConfig_t initializer macro
#define QNN_LORA_ADAPTER_BIN_UPDATER_CONFIG_INIT               \
  {                                                            \
    QNN_LORA_ADAPTER_BIN_UPDATER_CONFIG_VERSION_1, /*version*/ \
    {                                                          \
      QNN_LORA_ADAPTER_BIN_UPDATER_CONFIG_V1_INIT /*v1*/       \
    }                                                          \
  }
// clang-format on

/**
 * A typedef to indicate a QnnLoraAdapterBinUpdater handle
 */
typedef void* QnnLoraAdapterBinUpdaterHandle_t;

/**
 * @brief A function to create a LoraAdapterBinUpdaterApp with QnnLoraAdapterBinUpdater config.
 *
 * @param[in] config : A pointer to a fully qualified struct defining the QnnLoraAdapterBinUpdater
 *                     config.
 *
 * @param[out] adapterBinUpdater : A handle to the created instance of LoraAdapterBinUpdaterApp.
 *
 * @return Error code:
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_SUCCESS: no error is encountered
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_INVALID_CONFIG: config is not a valid
 *           QnnLoraAdapterBinUpdaterConfig_t
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_INVALID_HANDLE: adapterBinUpdater is not a valid
 * handle
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_APP_CREATION_FAILURE: indicates failure in creating
 *           LoraAdapterBinUpdaterApp instance
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_HANDLE_CREATION_FAILURE: indicates failure in
 * creating QnnLoraAdapterBinUpdaterHandle_t handle
 */
QNN_LORA_ADAPTER_BIN_UPDATER_API Qnn_ErrorHandle_t
QnnLoraAdapterBinUpdater_create(const QnnLoraAdapterBinUpdaterConfig_t* config,
                                QnnLoraAdapterBinUpdaterHandle_t* adapterBinUpdater);

/**
 * @brief Runs/executes LoraAdapterBinUpdaterApp
 *
 * @param[in] adapterBinUpdater : A QnnLoraAdapterBinUpdater handle.
 *
 * @return Error code:
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_SUCCESS: no error is encountered
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_RUN_FAILURE: indicates failure to run
 *           LoraAdapterBinUpdaterApp
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_INVALID_HANDLE: adapterBinUpdater is not a valid
 * handle
 */
QNN_LORA_ADAPTER_BIN_UPDATER_API Qnn_ErrorHandle_t
QnnLoraAdapterBinUpdater_run(QnnLoraAdapterBinUpdaterHandle_t adapterBinUpdater);

/**
 * @brief Destroys/frees LoraAdapterBinUpdaterApp
 *
 * @param[in] adapterBinUpdater : A QnnLoraAdapterBinUpdater handle.
 *
 * @return Error code:
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_SUCCESS: no error is encountered
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_FREE_HANDLE_FAILURE: indicates failure to free
 *           LoraAdapterBinUpdater handle
 *         - QNN_LORA_ADAPTER_BIN_UPDATER_ERROR_INVALID_HANDLE: adapterBinUpdater is not a valid
 * handle
 */
QNN_LORA_ADAPTER_BIN_UPDATER_API Qnn_ErrorHandle_t
QnnLoraAdapterBinUpdater_free(QnnLoraAdapterBinUpdaterHandle_t adapterBinUpdater);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // QNN_LORA_ADAPTER_BIN_UPDATER_H
