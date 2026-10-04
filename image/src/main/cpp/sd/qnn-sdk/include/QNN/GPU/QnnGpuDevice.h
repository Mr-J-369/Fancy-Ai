//==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// All Rights Reserved.
// Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

/**
 *  @file
 *  @brief  A header which defines the QNN GPU specialization of the QnnDevice.h interface.
 */

#ifndef QNN_GPU_DEVICE_H
#define QNN_GPU_DEVICE_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief This enum defines QNN GPU device types.
 */
typedef enum {
  /// Larger primary GPU on device
  QNN_GPU_DEVICE_PRIMARY_GPU = 0u,
  /// Smaller secondary GPU on device
  QNN_GPU_DEVICE_SECONDARY_GPU = 1u,
  /// Unused, present to ensure 32 bits.
  QNN_GPU_DEVICE_GPU_TYPE_UNDEFINED = 0x7FFFFFFF
} QnnGpuDevice_DeviceType_t;

/**
 * @brief A struct which defines the QNN GPU device custom configuration options.
 *        Objects of this type are to be referenced through QnnDevice_DeviceInfoExtension_t.
 */
typedef enum {
  /// Initial version of device information
  QNN_GPU_DEVICE_INFO_EXTENSION_V1 = 0u,
  /// Unused, present to ensure 32 bits.
  QNN_GPU_DEVICE_INFO_EXTENSION_CONFIG_OPTION_UNDEFINED = 0x7FFFFFFF
} QnnGpuDevice_DeviceInfoExtensionConfigOption_t;

typedef struct {
  const char* adrenoTier;
} QnnGpuDevice_DeviceInfoExtensionV1_t;

typedef struct _QnnDevice_DeviceInfoExtension_t {
  QnnGpuDevice_DeviceInfoExtensionConfigOption_t option;
  union UNNAMED {
    QnnGpuDevice_DeviceInfoExtensionV1_t deviceInfoV1;
  };
} QnnGpuDevice_DeviceInfoExtension_t;

// clang-format off
/// QnnGpuDevice_CustomConfig_t initializer macro
#define QNN_GPU_DEVICE_INFO_EXTENSION_INIT                            \
  {                                                                   \
    QNN_GPU_DEVICE_INFO_EXTENSION_CONFIG_OPTION_UNDEFINED, /*option*/ \
    {                                                                 \
    NULL           /*deviceInfoV1*/                                   \
    }                                                                 \
  }
// clang-format on

#ifdef __cplusplus
}  // extern "C"
#endif

#endif
