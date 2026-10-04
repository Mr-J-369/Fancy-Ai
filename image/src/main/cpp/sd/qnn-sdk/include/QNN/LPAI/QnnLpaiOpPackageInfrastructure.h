//=============================================================================
//
//  Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
//  All rights reserved.
//  Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//=============================================================================

/**
 *  @file
 *  @brief  A header which contains the LPAI custom operation global infrastructure APIs.
 */

#ifndef __QNN_LPAI_OP_PACKAGE_INFRA_H__
#define __QNN_LPAI_OP_PACKAGE_INFRA_H__

#ifdef __cplusplus
#include <cstdint>
#else
#include <stdint.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define QNN_LPAI_CUSTOM_OP_MAX_LAYOUT_RANK 5

// Opaque handles for node and tensor objects
typedef void* QnnLpai_CustomOpNode_t;
typedef void* QnnLpai_CustomOpTensor_t;

typedef struct QnnLpaiCustomOp_Version {
  uint32_t major; /* Major Version Info */
  uint32_t minor; /* Minor Version Info */
  uint32_t patch; /* Patch Version Info */
} QnnLpaiCustomOp_Version_t;

typedef enum QnnLpaiCustomOp_Error {
  LPAI_CUSTOM_OP_SUCCESS = 0,       // Successful - No errors
  LPAI_CUSTOM_OP_FAIL,              // Failure - Generic
  LPAI_CUSTOM_OP_INVALID_ARGUMENT,  // Invalid argument provided
  LPAI_CUSTOM_OP_MEMORY,            // Memory failure
  LPAI_CUSTOM_OP_NOT_SUPPORTED,     // Not supported operation
  LPAI_CUSTOM_OP_MAX = 0x7FFFFFFF
} QnnLpaiCustomOp_Error_t;

/**
 * @brief Data type for tensors in LPAI custom operations.
 */
typedef enum QnnLpaiCustomOp_DataType {
  LPAI_CUSTOM_OP_DATATYPE_INT_4 = 1,               // signed 4 bit integer
  LPAI_CUSTOM_OP_DATATYPE_UINT_4,                  // unsigned 4 bit integer
  LPAI_CUSTOM_OP_DATATYPE_INT_8,                   // signed 8 bit integer
  LPAI_CUSTOM_OP_DATATYPE_UINT_8,                  // unsigned 8 bit integer
  LPAI_CUSTOM_OP_DATATYPE_INT_16,                  // signed 16 bit integer
  LPAI_CUSTOM_OP_DATATYPE_UINT_16,                 // unsigned 16 bit integer
  LPAI_CUSTOM_OP_DATATYPE_INT_32,                  // signed 32 bit integer
  LPAI_CUSTOM_OP_DATATYPE_UINT_32,                 // unsigned 32 bit integer
  LPAI_CUSTOM_OP_DATATYPE_INT_64,                  // signed 64 bit integer
  LPAI_CUSTOM_OP_DATATYPE_UINT_64,                 // unsigned 64 bit integer
  LPAI_CUSTOM_OP_DATATYPE_FLOAT_16,                // float 16 bit
  LPAI_CUSTOM_OP_DATATYPE_FLOAT_32,                // float 32 bit
  LPAI_CUSTOM_OP_DATATYPE_UNDEFINED = 0x7FFFFFFF,  // undefined data type
} QnnLpaiCustomOp_DataType_t;

/**
 * @brief The memory layout form of a tensor
 *
 * Flat memory layout: Memory layout and logical shape are aligned
 */
typedef enum QnnLpaiCustomOp_LayoutForm {
  QNN_LPAI_CUSTOM_OP_LAYOUT_FORM_FLAT      = 1,
  QNN_LPAI_CUSTOM_OP_LAYOUT_FORM_UNDEFINED = 0x7FFFFFFF
} QnnLpaiCustomOp_LayoutForm_t;

/**
 * @brief Describes the memory layout of a tensor.
 *
 * layoutForm: Specifies the memory layout form in one of the supported forms.
 *
 * layoutOrder: Specifies the order of logical dimensions in the memory layout.
 * values are valid up to the rank index. Array indices from the smallest to largest
 * corresponds to the slowest moving (least contiguous) dimension to the fastest moving
 * (most contiguous) dimension. Values are in the range [0, rank-1], where 0 denotes the
 * index of the first dimension in the logical shape defined by the client.
 *
 * layoutStride: Specifies the stride of each dimension in memory. Values are valid
 * up to the rank index. This array is aligned with the layoutOrder
 *
 * LayoutDim: Specifies the number of elements in each dimension of the tensor.
 * Values are valid up to the rank index. This array is aligned with the layoutOrder
 *
 * Example:
 * Given a 4D tensor with logical shape in NCHW format and memory layout NWHC,
 * where the most and least contiguous dimensions are C and N, respectively.
 *
 * The logical shape dimensions are indexed as N:0, C:1, H:2, W:3.
 *
 * The layoutOrder would be [0, 3, 2, 1, X] where X denotes invalid value, and the value
 * at the right most valid index is 1 which corresponds to the logical shape index of C;
 * whereas, the value at the left most index is 0 which corresponds to the logical shape
 * index of N.
 *
 * If the tensor has logical shape 2x3x8x5 (NCHW) and a data size of 1 byte,
 * the layoutDim would be [2, 5, 8, 3, X], where X denotes invalid value.
 *
 * the layoutStride in NWHC layout would be [120, 24, 3, 1, X], where X denotes invalid
 * value. C is the most contiguous dimension and has the smallest stride of 1 byte,
 * meaning adjacent elements along C are 1 byte apart in memory. H is the next fastest
 * moving dimension with a stride of 3 bytes. N is the slowest-moving dimension with a
 * stride of 120 bytes.
 */
typedef struct QnnLpaiCustomOp_Layout {
  QnnLpaiCustomOp_LayoutForm_t layoutForm;
  uint8_t layoutOrder[QNN_LPAI_CUSTOM_OP_MAX_LAYOUT_RANK];
  uint32_t layoutStride[QNN_LPAI_CUSTOM_OP_MAX_LAYOUT_RANK];
  uint32_t layoutDim[QNN_LPAI_CUSTOM_OP_MAX_LAYOUT_RANK];
} QnnLpaiCustomOp_Layout_t;

/**
 * @brief Quantization type to specify the scope of quantization parameters
 *
 * Per-tensor: All elements in the tensor share the same quantization parameters.
 */
typedef enum QnnLpaiCustomOp_QuantType {
  QNN_LPAI_CUSTOM_OP_QUANT_TYPE_PER_TENSOR = 1,
  QNN_LPAI_CUSTOM_OP_QUANT_TYPE_UNDEFINED  = 0x7FFFFFFF
} QnnLpaiCustomOp_QuantType_t;

/**
 * @brief Scale type to specify how the scale is represented in the quantization parameters.
 *
 * The scale can be represented as either integer or floating point format. The integer
 * representation is an approximation of the floating point value using integer math as follows:
 *
 * scale_float = scale_int / (2^shift)
 *
 */
typedef enum QnnLpaiCustomOp_ScaleType {
  QNN_LPAI_CUSTOM_OP_QUANT_SCALE_TYPE_INT = 1,
  QNN_LPAI_CUSTOM_OP_QUANT_SCALE_TYPE_FLOAT,
  QNN_LPAI_CUSTOM_OP_QUANT_SCALE_TYPE_UNDEFINED = 0x7FFFFFFF
} QnnLpaiCustomOp_ScaleType_t;

/**
 * @brief Quantization integer scale representation.
 *
 * The integer representation of quantization scale is an approximation of the floating point
 * value using integer math as follows:
 *
 * scale_float = scale_int / (2^shift_int)
 */
typedef struct QnnLpaiCustomOp_IntScale {
  int32_t scale;
  int32_t shift;
} QnnLpaiCustomOp_IntScale_t;

/** @brief Quantization scale
 *
 * The scale can be represented as either integer or floating point format. The integer
 * representation is an approximation of the floating point value using integer math as follows:
 *
 * scale_float = scale_int / (2^shift)
 */
typedef struct QnnLpaiCustomOp_QuantScale {
  QnnLpaiCustomOp_ScaleType_t type;
  union {
    QnnLpaiCustomOp_IntScale_t iScale;
    float fScale;
  };
} QnnLpaiCustomOp_QuantScale_t;

/**
 * @brief This structure is used to describe the quantization information of a tensor.
 *
 * Quantization parameters are calculated based on the following formula:
 *
 * scale = (max - min) / (q_max - q_min)
 * offset = q_min + round(q_min*(-1/scale))
 *
 * x_q = round(x/scale) + offset
 * x = scale * (x_q - offset)
 */
typedef struct QnnLpaiCustomOp_PerTensorQuantInfo {
  QnnLpaiCustomOp_QuantScale_t scale;
  int32_t offset;
} QnnLpaiCustomOp_PerTensorQuantInfo_t;

/**
 * @brief This structure is used to describe the information for a specific buffer
 *
*/
typedef struct QnnLpaiCustomOp_BufferInfo {
  void * buffer;
  uint32_t bufferSize;
} QnnLpaiCustomOp_BufferInfo_t;

/**
 * @brief A function to retrieve the number of input tensors for a given node
 *
 * @param[in] node The given node
 *
 * @return number of input tensors for the node
 *         0 if error or invalid node is passed in
 */
typedef uint32_t (*QnnLpaiCustomOp_GetNumInputTensors_t)(QnnLpai_CustomOpNode_t node);

/**
 * @brief A function to retrieve the number of output tensors for a given node
 *
 * @param[in] node The given node
 *
 * @return number of output tensors for the node
 *         0 if error or invalid node is passed in
 */
typedef uint32_t (*QnnLpaiCustomOp_GetNumOutputTensors_t)(QnnLpai_CustomOpNode_t node);

/**
 * @brief A function to retrieve the input tensor at a given index of a given node
 *
 * @param[in] node The given node
 * @param[in] index The given index of the tensor
 *
 * @return pointer to the input tensor at the given index of the node
 *         NULL if error or invalid node/index is passed in
 */
typedef QnnLpai_CustomOpTensor_t (*QnnLpaiCustomOp_GetInputTensor_t)(QnnLpai_CustomOpNode_t node,
                                                                     uint32_t index);

/**
 * @brief A function to retrieve the output tensor at a given index of a given node
 *
 * @param[in] node The given node
 * @param[in] index The given index of the tensor
 *
 * @return pointer to the output tensor at the given index of the node
 *         NULL if error or invalid node/index is passed in
 */
typedef QnnLpai_CustomOpTensor_t (*QnnLpaiCustomOp_GetOutputTensor_t)(QnnLpai_CustomOpNode_t node,
                                                                      uint32_t index);

/**
 * @brief A function to retrieve the parameter tensor given its name in a given node
 *
 * @param[in] node The given node
 * @param[in] name The given name of the parameter tensor. The name must be a null-terminated string
 *
 * @return pointer to the parameter tensor with the given name in the node
 *         NULL if error or invalid node/name is passed in
 */
typedef QnnLpai_CustomOpTensor_t (*QnnLpaiCustomOp_GetParamTensor_t)(QnnLpai_CustomOpNode_t node,
                                                                     const char* name);

/**
*  @brief A function to retrieve the temporary memory, calculated during model compilation
*
* @param[in] node The given node
* @param[out] bufferParams The buffer information corresponding to the temporary memory calculated during model compilation
**/

typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetTempBuffer_t)(QnnLpai_CustomOpNode_t node, QnnLpaiCustomOp_BufferInfo_t* bufferParams);

/**
 * @brief A function to retrieve the logical shape rank of a given tensor
 *
 * @param[in] tensor The given tensor
 * @param[out] rank The logical shape rank of the tensor
 *
 * @return Error code
 *         LPAI_SUCCESS: successfully retrieved the shape rank of the tensor
 *         LPAI_FAIL: failed to retrieve the shape rank of the tensor
 *         LPAI_INVALID_ARGUMENT: Invalid argument provided
 *         LPAI_NOT_SUPPORTED: Not supported operation on this type of tensor
 */
typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetTensorShapeRank_t)(
    QnnLpai_CustomOpTensor_t tensor, uint32_t* rank);

/**
 * @brief A function to retrieve the memory layout rank of a given tensor
 *
 * @param[in] tensor The given tensor
 * @param[out] rank The memory layout rank of the tensor
 *
 * @return Error code
 *         LPAI_SUCCESS: successfully retrieved the layout rank of the tensor
 *         LPAI_FAIL: failed to retrieve the layout rank of the tensor
 *         LPAI_INVALID_ARGUMENT: Invalid argument provided
 *         LPAI_NOT_SUPPORTED: Not supported operation on this type of tensor
 */
typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetTensorLayoutRank_t)(
    QnnLpai_CustomOpTensor_t tensor, uint32_t* rank);

/**
 * @brief A function to retrieve the size along a given dimension index of a given tensor's logical
 * shape
 *
 *
 * @param[in] tensor The given tensor
 * @param[in] index The given logical shape dimension index
 * @param[out] size The size along the given dimension index of the tensor
 *
 * @return Error code
 *         LPAI_SUCCESS: successfully retrieved the size
 *         LPAI_FAIL: failed to retrieve the size
 *         LPAI_INVALID_ARGUMENT: Invalid argument provided
 *         LPAI_NOT_SUPPORTED: Not supported operation on this type of tensor
 */
typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetTensorShapeDimSize_t)(
    QnnLpai_CustomOpTensor_t tensor, uint32_t index, uint32_t* size);

/**
 * @brief A function to retrieve the memory layout of a given tensor
 *
 * @param[in] tensor The given tensor
 * @param[out] layout The layout of the tensor.
 *
 * @return Error code
 *         LPAI_SUCCESS: successfully retrieved the layout
 *         LPAI_FAIL: failed to retrieve the layout
 *         LPAI_INVALID_ARGUMENT: Invalid argument provided
 *         LPAI_NOT_SUPPORTED: Not supported operation on this type of tensor
 */
typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetTensorLayout_t)(
    QnnLpai_CustomOpTensor_t tensor, QnnLpaiCustomOp_Layout_t* layout);

/**
 * @brief A function to retrieve the data type of a given tensor
 *
 * @param[in] tensor The given tensor
 * @param[out] dataType The enum data type of the tensor
 *
 * @return Error code
 *         LPAI_SUCCESS: successfully retrieved the data type
 *         LPAI_FAIL: failed to retrieve the data type
 *         LPAI_INVALID_ARGUMENT: Invalid argument provided
 *         LPAI_NOT_SUPPORTED: Not supported operation on this type of tensor
 */
typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetTensorDataType_t)(
    QnnLpai_CustomOpTensor_t tensor, QnnLpaiCustomOp_DataType_t* dataType);

/**
 * @brief A function to retrieve the data size of a given tensor in the unit of bit
 *
 * @param[in] tensor The given tensor
 * @param[out] size The data size of the tensor in the unit of bit
 *
 * @return Error code
 *         LPAI_SUCCESS: successfully retrieved the data size
 *         LPAI_FAIL: failed to retrieve the data size
 *         LPAI_INVALID_ARGUMENT: Invalid argument provided
 *         LPAI_NOT_SUPPORTED: Not supported operation on this type of tensor
 */
typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetTensorDataSize_t)(
    QnnLpai_CustomOpTensor_t tensor, uint32_t* size);

/**
 * @brief A function to retrieve the quantization type of a given tensor
 *
 * @param[in] tensor The given tensor
 * @param[out] quantType The quantization type
 *
 * @return Error code
 *         LPAI_SUCCESS: successfully retrieved the quantization type
 *         LPAI_FAIL: failed to retrieve the quantization type
 *         LPAI_INVALID_ARGUMENT: Invalid argument provided
 *         LPAI_NOT_SUPPORTED: Not supported operation on this type of tensor
 */
typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetTensorQuantType_t)(
    QnnLpai_CustomOpTensor_t tensor, QnnLpaiCustomOp_QuantType_t* quantType);

/**
 * @brief A function to retrieve the per-tensor quantization parameters of a given tensor
 *
 * @param[in] tensor The given tensor
 * @param[out] quantParams The quantization parameters
 *
 * @return Error code
 *         LPAI_SUCCESS: successfully retrieved the quantization parameters
 *         LPAI_FAIL: failed to retrieve the quantization parameters
 *         LPAI_INVALID_ARGUMENT: Invalid argument provided
 *         LPAI_NOT_SUPPORTED: Not supported operation on this type of tensor
 */
typedef QnnLpaiCustomOp_Error_t (*QnnLpaiCustomOp_GetPerTensorQuantParams_t)(
    QnnLpai_CustomOpTensor_t tensor, QnnLpaiCustomOp_PerTensorQuantInfo_t* quantParams);

/**
 * @brief A function to retrieve the pointer to data of a given tensor
 *
 * @param[in] tensor The given tensor
 *
 * @return pointer to the data of the tensor
 *         NULL if error or invalid tensor is passed in
 */
typedef void* (*QnnLpaiCustomOp_GetTensorData_t)(QnnLpai_CustomOpTensor_t tensor);

typedef struct _QnnOpPackage_GlobalInfrastructure_t {
  QnnLpaiCustomOp_Version_t version;
  QnnLpaiCustomOp_GetNumInputTensors_t getNumInputTensors;
  QnnLpaiCustomOp_GetNumOutputTensors_t getNumOutputTensors;
  QnnLpaiCustomOp_GetInputTensor_t getInputTensor;
  QnnLpaiCustomOp_GetOutputTensor_t getOutputTensor;
  QnnLpaiCustomOp_GetParamTensor_t getParamTensor;
  QnnLpaiCustomOp_GetTensorShapeRank_t getTensorShapeRank;
  QnnLpaiCustomOp_GetTensorLayoutRank_t getTensorLayoutRank;
  QnnLpaiCustomOp_GetTensorShapeDimSize_t getTensorShapeDimSize;
  QnnLpaiCustomOp_GetTensorLayout_t getTensorLayout;
  QnnLpaiCustomOp_GetTensorDataType_t getTensorDataType;
  QnnLpaiCustomOp_GetTensorDataSize_t getTensorDataSize;
  QnnLpaiCustomOp_GetTensorQuantType_t getTensorQuantType;
  QnnLpaiCustomOp_GetPerTensorQuantParams_t getPerTensorQuantParams;
  QnnLpaiCustomOp_GetTensorData_t getTensorData;
  QnnLpaiCustomOp_GetTempBuffer_t getTempBuffer;
} QnnLpaiOpPackage_GlobalInfrastructure_t;

// clang-format off
/** QnnLpaiOpPackage_GlobalInfrastructure_t initializer macro */
#define QNN_LPAI_OP_PACKAGE_GLOBAL_INFRASTRUCTURE_INIT      \
  {                                                         \
    {0u, 0u, 0u}, /*version                 */              \
        NULL,     /*getNumInputTensors      */              \
        NULL,     /*getNumOutputTensors     */              \
        NULL,     /*getInputTensor          */              \
        NULL,     /*getOutputTensor         */              \
        NULL,     /*getParamTensor          */              \
        NULL,     /*getTensorShapeRank      */              \
        NULL,     /*getTensorLayoutRank     */              \
        NULL,     /*getTensorShapeDimSize   */              \
        NULL,     /*getTensorLayout         */              \
        NULL,     /*getTensorDataType       */              \
        NULL,     /*getTensorDataSize       */              \
        NULL,     /*getTensorQuantType      */              \
        NULL,     /*getPerTensorQuantParams */              \
        NULL,     /*getTensorData           */              \
        NULL,     /*getTempBuffer           */              \
  }
// clang-format on

#ifdef __cplusplus
}
#endif

#endif  // __QNN_LPAI_OP_PACKAGE_INFRA_H__
