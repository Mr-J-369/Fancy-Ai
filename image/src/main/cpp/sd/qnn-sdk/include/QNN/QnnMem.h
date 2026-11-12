//==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// All rights reserved.
// Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

/**
 *  @file
 *  @brief  Memory registration component API.
 *
 *          Requires Backend to be initialized.
 *          Provides means to register externally allocated memory with a backend.
 */

#ifndef QNN_MEM_H
#define QNN_MEM_H

#include "QnnCommon.h"
#include "QnnTypes.h"

#ifdef __cplusplus
extern "C" {
#endif

//=============================================================================
// Macros
//=============================================================================

/// Invalid memory file descriptor value
#define QNN_MEM_INVALID_FD -1

//=============================================================================
// Data Types
//=============================================================================

/**
 * @brief QNN Mem(ory) API result / error codes.
 */
typedef enum {
  QNN_MEM_MIN_ERROR = QNN_MIN_ERROR_MEM,
  ////////////////////////////////////

  /// Qnn Memory success
  QNN_MEM_NO_ERROR = QNN_SUCCESS,
  /// Backend does not support requested functionality
  QNN_MEM_ERROR_NOT_SUPPORTED = QNN_COMMON_ERROR_NOT_SUPPORTED,
  /// Invalid function argument
  QNN_MEM_ERROR_INVALID_ARGUMENT = QNN_MIN_ERROR_MEM + 0,
  /// Invalid memory handle
  QNN_MEM_ERROR_INVALID_HANDLE = QNN_MIN_ERROR_MEM + 1,
  /// Provided memory has already been registered
  QNN_MEM_ERROR_ALREADY_REGISTERED = QNN_MIN_ERROR_MEM + 2,
  /// Error in memory mapping
  QNN_MEM_ERROR_MAPPING = QNN_MIN_ERROR_MEM + 3,
  /// Invalid memory shape based on a backend's memory restrictions (e.g. alignment incompatibility)
  QNN_MEM_ERROR_INVALID_SHAPE = QNN_MIN_ERROR_MEM + 4,
  /// Backend does not support requested memory type
  QNN_MEM_ERROR_UNSUPPORTED_MEMTYPE = QNN_MIN_ERROR_MEM + 5,

  ////////////////////////////////////
  QNN_MEM_MAX_ERROR = QNN_MAX_ERROR_MEM,
  // Unused, present to ensure 32 bits.
  QNN_MEM_ERROR_UNDEFINED = 0x7FFFFFFF
} QnnMem_Error_t;

/**
 * @brief A struct which describes the shape of memory
 */
typedef struct {
  /// Number of dimensions
  uint32_t numDim;
  /// Array holding size of each dimension. Size of array is = numDim
  uint32_t* dimSize;
  /// Additional configuration in string, for extensibility. Allowed to be NULL
  const char* shapeConfig;
} Qnn_MemShape_t;

#define SHAPE_CONFIG_DATA_FORMAT_UBWC_RGBA8888              "DATA_FORMAT_UBWC_RGBA8888"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_NV12                  "DATA_FORMAT_UBWC_NV12"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_NV12_Y                "DATA_FORMAT_UBWC_NV12_Y"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_NV12_UV               "DATA_FORMAT_UBWC_NV12_UV"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_NV124R                "DATA_FORMAT_UBWC_NV124R"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_NV124R_Y              "DATA_FORMAT_UBWC_NV124R_Y"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_NV124R_UV             "DATA_FORMAT_UBWC_NV124R_UV"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_P010                  "DATA_FORMAT_UBWC_P010"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_P010_Y                "DATA_FORMAT_UBWC_P010_Y"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_P010_UV               "DATA_FORMAT_UBWC_P010_UV"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_P016                  "DATA_FORMAT_UBWC_P016"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_P016_Y                "DATA_FORMAT_UBWC_P016_Y"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_P016_UV               "DATA_FORMAT_UBWC_P016_UV"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_TP10                  "DATA_FORMAT_UBWC_TP10"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_TP10_Y                "DATA_FORMAT_UBWC_TP10_Y"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_TP10_UV               "DATA_FORMAT_UBWC_TP10_UV"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_TBAYER10_1X1          "DATA_FORMAT_UBWC_TBAYER10_1X1"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_TBAYER10_2X2          "DATA_FORMAT_UBWC_TBAYER10_2X2"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_TBAYER10_3X3          "DATA_FORMAT_UBWC_TBAYER10_3X3"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_TBAYER10_4X4          "DATA_FORMAT_UBWC_TBAYER10_4X4"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_10BPS_1X1     "DATA_FORMAT_UBWC_BAYER16_10BPS_1X1"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_10BPS_2X2     "DATA_FORMAT_UBWC_BAYER16_10BPS_2X2"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_10BPS_4X4     "DATA_FORMAT_UBWC_BAYER16_10BPS_4X4"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_12BPS_1X1     "DATA_FORMAT_UBWC_BAYER16_12BPS_1X1"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_12BPS_2X2     "DATA_FORMAT_UBWC_BAYER16_12BPS_2X2"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_12BPS_4X4     "DATA_FORMAT_UBWC_BAYER16_12BPS_4X4"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_14BPS_1X1     "DATA_FORMAT_UBWC_BAYER16_14BPS_1X1"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_14BPS_2X2     "DATA_FORMAT_UBWC_BAYER16_14BPS_2X2"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_14BPS_4X4     "DATA_FORMAT_UBWC_BAYER16_14BPS_4X4"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_16BPS_1X1     "DATA_FORMAT_UBWC_BAYER16_16BPS_1X1"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_16BPS_2X2     "DATA_FORMAT_UBWC_BAYER16_16BPS_2X2"
#define SHAPE_CONFIG_DATA_FORMAT_UBWC_BAYER16_16BPS_4X4     "DATA_FORMAT_UBWC_BAYER16_16BPS_4X4"

// clang-format off
/// Qnn_MemShape_t initializer macro
#define QNN_MEM_SHAPE_INIT    \
  {                           \
    0u,       /*numDim*/      \
    NULL,     /*dimSize*/     \
    NULL      /*shapeConfig*/ \
  }
// clang-format on

/**
 * @brief An enumeration of memory types which may be used to provide data for a QNN tensor.
 */
typedef enum {
  /// Memory allocated by ION manager. ION memory can only be registered with Backend libraries
  /// when a device supports ION manager.
  QNN_MEM_TYPE_ION = 1,
  /// Memory allocated by a custom backend mechanism.
  QNN_MEM_TYPE_CUSTOM = 2,
  /// Memory allocated by DMA-BUF subsystem.
  QNN_MEM_TYPE_DMA_BUF = 3,
  /// Memory allocated by DX12-BUF subsystem.
  QNN_MEM_TYPE_DX12 = 4,
  // Unused, present to ensure 32 bits.
  QNN_MEM_TYPE_UNDEFINED = 0x7FFFFFFF
} Qnn_MemType_t;

/**
 * @brief a struct which includes ION related information
 */
typedef struct {
  /// file descriptor for memory, must be set to QNN_MEM_INVALID_FD if not applicable
  int32_t fd;
} Qnn_MemIonInfo_t;

/// Qnn_MemIonInfo_t initializer macro
#define QNN_MEM_ION_INFO_INIT \
  { QNN_MEM_INVALID_FD /*fd*/ }

/**
 * @brief Definition of custom mem info opaque object. This object type is managed by backend
 * specific APIs obtained by a custom backend mechanism.
 */
typedef void* Qnn_MemInfoCustom_t;

/**
 * @brief a struct which includes DMA-BUF related information
 */
typedef struct {
  /// file descriptor for memory, must be set to QNN_MEM_INVALID_FD if not applicable
  int32_t fd;
  /// data pointer, created by app, using mmap on above file descriptor.
  void* data;
} Qnn_MemDmaBufInfo_t;

/// Qnn_MemDmaBufInfo_t initializer macro
#define QNN_MEM_DMA_BUF_INFO_INIT \
  {                               \
    QNN_MEM_INVALID_FD, /*fd*/    \
        NULL            /*data*/  \
  }

/**
 * @brief a struct which includes DX12-BUF related information
 */
 typedef void* Qnn_Dx12ResourceHandle_t;
 typedef struct {
  /// DX12 buffer pointer, created by app on above file descriptor.
  Qnn_Dx12ResourceHandle_t resourceHandle;
} Qnn_MemDx12BufInfo_t;

/// Qnn_MemDx12BufInfo_t initializer macro
#define QNN_MEM_DX12_BUF_INFO_INIT \
  {                                \
    NULL            /*resourseHandle*/  \
  }
/**
 * @brief A struct which describes memory params
 */
typedef struct {
  /// memory shape
  Qnn_MemShape_t memShape;
  /// memory data type
  Qnn_DataType_t dataType;
  /// memory type
  Qnn_MemType_t memType;

  union UNNAMED {
    Qnn_MemIonInfo_t ionInfo;
    Qnn_MemInfoCustom_t customInfo;
    Qnn_MemDmaBufInfo_t dmaBufInfo;
    Qnn_MemDx12BufInfo_t dx12BufInfo;
  };
} Qnn_MemDescriptor_t;

// clang-format off
/// Qnn_MemDescriptor_t initializer macro
#define QNN_MEM_DESCRIPTOR_INIT          \
  {                                      \
    QNN_MEM_SHAPE_INIT,     /*memShape*/ \
    QNN_DATATYPE_UNDEFINED, /*dataType*/ \
    QNN_MEM_TYPE_UNDEFINED, /*memType*/  \
    {                                    \
      QNN_MEM_ION_INFO_INIT /*ionInfo*/  \
    }                                    \
  }
// clang-format on

/**
 * @brief An enumeration of directions for a memory copy performed by QnnMem_copyBatch().
 */
typedef enum {
  /// Copy from the client (host) side of a memory handle to the accelerator side.
  QNN_MEM_COPY_HOST_TO_DEVICE = 0,
  /// Copy from the accelerator side of a memory handle to the client (host) side.
  QNN_MEM_COPY_DEVICE_TO_HOST = 1,
  // Unused, present to ensure 32 bits.
  QNN_MEM_COPY_UNDEFINED = 0x7FFFFFFF
} QnnMem_CopyDirection_t;

/**
 * @brief A struct which describes a single memory copy request, version 1.
 */
typedef struct {
  /// Memory handle, previously registered via QnnMem_register(), whose host and accelerator
  /// side allocations are the endpoints of the copy.
  Qnn_MemHandle_t memHandle;
  /// Direction of the copy.
  QnnMem_CopyDirection_t direction;
  /// Offset, in bytes, into the memory handle at which the copy starts.
  uint64_t offset;
  /// Number of bytes to copy. A value of 0 copies all remaining bytes from _offset_.
  uint64_t size;
} QnnMem_CopyDescriptorV1_t;

// clang-format off
/// QnnMem_CopyDescriptorV1_t initializer macro
#define QNN_MEM_COPY_DESCRIPTOR_V1_INIT             \
  {                                                 \
    NULL,                    /*memHandle*/          \
    QNN_MEM_COPY_UNDEFINED,  /*direction*/          \
    0u,                      /*offset*/             \
    0u                       /*size*/               \
  }
// clang-format on

/**
 * @brief An enumeration of QnnMem_CopyDescriptor_t versions.
 */
typedef enum {
  /// Enum to choose usage of QnnMem_CopyDescriptorV1_t in QnnMem_CopyDescriptor_t
  QNN_MEM_COPY_DESC_V1 = 1,
  // Unused, present to ensure 32 bits.
  QNN_MEM_COPY_DESC_UNDEFINED = 0x7FFFFFFF
} QnnMem_CopyDescriptor_Version_t;

/**
 * @brief A struct which provides a versioned memory copy descriptor.
 */
typedef struct {
  /// Version of the memory copy descriptor
  QnnMem_CopyDescriptor_Version_t version;
  union UNNAMED {
    /// Memory copy descriptor, valid when version is QNN_MEM_COPY_DESC_V1
    const QnnMem_CopyDescriptorV1_t* v1;
  } desc;
} QnnMem_CopyDescriptor_t;

// clang-format off
/// QnnMem_CopyDescriptor_t initializer macro
#define QNN_MEM_COPY_DESCRIPTOR_INIT                 \
  {                                                  \
    QNN_MEM_COPY_DESC_UNDEFINED, /*version*/         \
    {                                                \
      NULL /*v1*/                                    \
    }                                                \
  }
// clang-format on

//=============================================================================
// Public Functions
//=============================================================================

/**
 * @brief Register existing memory to memory handle.
 *        Used to instruct QNN to use this memory directly.
 *
 * @param[in] context A context handle.
 *
 * @param[in] memDescriptors Array of memory descriptors to be registered.
 *
 * @param[in] numDescriptors Number of memory descriptors in the array.
 *
 * @param[out] memHandles Array of allocated memory handles, length is _numDescriptors_. Same shape
 *                        as _memDescriptors_ (i.e. memHandles[n] corresponds to
 *                        memDescriptors[n]).
 *
 * @note memHandles parameter: Array memory is owned by the client. Array size must be at least
 *       _numDescriptors_*sizeof(Qnn_MemHandle_t). The array will be initialized to NULL by the
 *       backend. Upon failure, no memory will be registered and the _memHandles_ array will remain
 *       NULL.
 *
 * @return Error code:
 *         - QNN_SUCCESS: memory was successfully registered
 *         - QNN_MEM_ERROR_NOT_SUPPORTED: backend does not support this API
 *         - QNN_MEM_ERROR_ALREADY_REGISTERED: memory has already been registered
 *         - QNN_MEM_ERROR_UNSUPPORTED_MEMTYPE: backend does not support a memType specified within
 *           _memDescriptors_
 *         - QNN_MEM_ERROR_MAPPING: failed to map between memory file descriptor and memory address
 *         - QNN_MEM_ERROR_INVALID_ARGUMENT: NULL array ptr or invalid memory descriptor
 *         - QNN_MEM_ERROR_INVALID_SHAPE: backend does not support a memShape specified within
 *           _memDescriptors_
 *         - QNN_MEM_ERROR_INVALID_HANDLE: _context_ is not a valid handle
 */
QNN_API
Qnn_ErrorHandle_t QnnMem_register(Qnn_ContextHandle_t context,
                                  const Qnn_MemDescriptor_t* memDescriptors,
                                  uint32_t numDescriptors,
                                  Qnn_MemHandle_t* memHandles);

/**
 * @brief Deregister a memory handle which was registered via QnnMem_register and invalidates
 *        memHandle for the given backend handle.
 *
 * @param[in] memHandles Array of memory handles to be deregistered.
 *
 * @param[in] numHandles Number of memory handles in the array.
 *
 * @note memHandles parameter: Upon failure, all valid handles within _memHandles_ will still be
 *       de-registered.
 *
 * @return Error code:
 *         - QNN_SUCCESS: memory was successfully de-registered
 *         - QNN_MEM_ERROR_NOT_SUPPORTED: backend does not support this API
 *         - QNN_MEM_ERROR_INVALID_ARGUMENT: _memHandles_ is NULL
 *         - QNN_MEM_ERROR_INVALID_HANDLE: a handle within _memHandles_ is NULL/invalid
 */
QNN_API
Qnn_ErrorHandle_t QnnMem_deRegister(const Qnn_MemHandle_t* memHandles, uint32_t numHandles);

/**
 * @brief Copy data between the client (host) side and the accelerator side of memory handles
 *        which were registered via QnnMem_register.
 *
 * @param[in] descriptors Array of copy descriptors describing the copies to be performed.
 *
 * @param[in] numDescriptors Number of copy descriptors in the array.
 *
 * @note Descriptors are processed in the order supplied. Upon failure, the error from the first
 *       failing descriptor is returned immediately: descriptors preceding it have already
 *       completed and are not rolled back, and descriptors following it are not submitted.
 *       Clients requiring all-or-nothing semantics must re-establish buffer state themselves.
 *
 * @return Error code:
 *         - QNN_SUCCESS: all copies completed successfully
 *         - QNN_MEM_ERROR_NOT_SUPPORTED: backend does not support this API
 *         - QNN_MEM_ERROR_INVALID_ARGUMENT: _descriptors_ is NULL, _numDescriptors_ is 0, or a
 *           descriptor holds an unsupported version, direction, offset or size
 *         - QNN_MEM_ERROR_INVALID_HANDLE: a memory handle within _descriptors_ is NULL/invalid,
 *           or has no accelerator side allocation to copy to/from
 */
QNN_API
Qnn_ErrorHandle_t QnnMem_copyBatch(const QnnMem_CopyDescriptor_t* descriptors,
                                   uint32_t numDescriptors);

/**
 * @brief Fill the accelerator side of a memory handle which was registered via QnnMem_register
 *        with a repeated scalar value.
 *
 * @param[in] memHandle Memory handle whose accelerator side allocation is to be filled.
 *
 * @param[in] offsetElements Offset, in elements of _value_'s data type, at which the fill starts.
 *
 * @param[in] value Scalar value written to each filled element. The scalar's data type determines
 *                  the element size.
 *
 * @param[in] numElements Number of elements to fill. A value of 0 is a no-op.
 *
 * @return Error code:
 *         - QNN_SUCCESS: memory was successfully filled
 *         - QNN_MEM_ERROR_NOT_SUPPORTED: backend does not support this API, or does not support
 *           the data type of _value_
 *         - QNN_MEM_ERROR_INVALID_ARGUMENT: _offsetElements_ and _numElements_ describe a range
 *           outside the registered memory
 *         - QNN_MEM_ERROR_INVALID_HANDLE: _memHandle_ is NULL/invalid, or has no accelerator side
 *           allocation to fill
 */
QNN_API
Qnn_ErrorHandle_t QnnMem_fill(Qnn_MemHandle_t memHandle,
                              uint64_t offsetElements,
                              Qnn_Scalar_t value,
                              uint64_t numElements);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // QNN_MEM_H
