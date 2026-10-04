//==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// All rights reserved.
// Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

/**
 *  @file
 *  @brief  Config component API.
 *
 *          This is top level QNN API component.
 */

#ifndef QNN_CONFIG_H
#define QNN_CONFIG_H

#include "QnnCommon.h"
#include "QnnTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief QNN Config API result / error codes.
 */
typedef enum {
  QNN_GLOBAL_CONFIG_MIN_ERROR = QNN_MIN_ERROR_GLOBAL_CONFIG,
  ////////////////////////////////////////////

  /// Qnn Global Config success
  QNN_GLOBAL_CONFIG_NO_ERROR = QNN_SUCCESS,
  /// Unsupported feature
  QNN_GLOBAL_CONFIG_ERROR_UNSUPPORTED_FEATURE = QNN_COMMON_ERROR_NOT_SUPPORTED,
  /// Invalid config
  QNN_GLOBAL_CONFIG_ERROR_INVALID_CONFIG = QNN_MIN_ERROR_GLOBAL_CONFIG,
  ////////////////////////////////////////////
  QNN_GLOBAL_CONFIG_MAX_ERROR = QNN_MAX_ERROR_GLOBAL_CONFIG,
  // Unused, present to ensure 32 bits.
  QNN_GLOBAL_CONFIG_ERROR_UNDEFINED = 0x7FFFFFFF
} QnnGlobalConfig_Error_t;

typedef enum {
  QNN_GLOBAL_CONFIG_OPTION_SOC_MODEL    = 0,
  QNN_GLOBAL_CONFIG_OPTION_MACHINE_TYPE = 1,
  QNN_GLOBAL_CONFIG_OPTION_UNDEFINED    = 0x7FFFFFFF
} QnnGlobalConfig_ConfigOption_t;

/**
 * @brief Host / device machine type for backend execution.
 */
typedef enum {
  /// Default type of an x86_64 host machine.
  QNN_GLOBAL_CONFIG_OPTION_MACHINE_TYPE_HOST      = 0,
  /// Default type of an ARM device machine.
  QNN_GLOBAL_CONFIG_OPTION_MACHINE_TYPE_DEVICE    = 1,
  QNN_GLOBAL_CONFIG_OPTION_MACHINE_TYPE_UNDEFINED = 0x7FFFFFFF
} QnnGlobalConfig_MachineType_t;

typedef struct {
  QnnGlobalConfig_ConfigOption_t option;
  union UNNAMED {
    Qnn_SocModel_t                socModel;
    QnnGlobalConfig_MachineType_t machineType;
  };
} QnnGlobalConfig_t;


/// QnnBackend_Config_t initializer macro
#define QNN_GLOBAL_CONFIG_INIT                      \
  {                                                 \
    QNN_GLOBAL_CONFIG_OPTION_UNDEFINED, /*option*/  \
    {                                               \
      QNN_SOC_MODEL_UNKNOWN /*socModel*/            \
    }                                               \
  }

/**
 * @brief A function to set global configuration settings.
 *
 * @param[in] config Pointer to a NULL terminated array of config option pointers. NULL is allowed
 *                   and indicates no config options are provided. All config options have default
 *                   value, in case not provided. If same config option type is provided multiple
 *                   times, the last option value will be used.
 *
 * @return Error code:
 *         - QNN_SUCCESS: no error is encountered
 *         - QNN_GLOBAL_CONFIG_ERROR_INVALID_CONFIG: config parameter is invalid
 */
QNN_API
Qnn_ErrorHandle_t QnnGlobalConfig_Set(const QnnGlobalConfig_t **config);




#ifdef __cplusplus
}  // extern "C"
#endif

#endif
