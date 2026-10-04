//==============================================================================
//
//  Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
//  All Rights Reserved.
//  Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

#ifndef _QNN_LPAI_OP_PACKAGE_
#define _QNN_LPAI_OP_PACKAGE_

#include "QnnLpaiOpPackageInfrastructure.h"
#include "QnnOpPackage.h"

// execute function pointer signature of custom-op. Must match with runtime and RT-Adaptor
typedef int32_t (*LpaiCustomOp_Execute_Fn)(QnnLpai_CustomOpNode_t);

// compiler definitions
typedef struct {
  uint32_t numOfTypes;
  Qnn_DataType_t* DataTypes;
} QnnLpaiOpPackage_DataTypeInfo_t;

// compiler function pointer signature of custom-op.
typedef Qnn_ErrorHandle_t (*QnnLpaiOpPackage_Validate_Fn)(Qnn_OpConfig_t);
typedef Qnn_ErrorHandle_t (*QnnLpaiOpPackage_GetTempBufferSize_Fn)(Qnn_OpConfig_t, size_t*);

// Layout support flag constants
/// Default: shape == layout, no bubble, no CSF
#define QNN_LPAI_OP_LAYOUT_DEFAULT (0u)

/// Indicates the custom-op kernel implementation uses the getTensorLayout API to
/// query each tensor's physical memory layout instead of assuming it matches the
/// logical shape. Set this flag when the kernel is layout-aware and can therefore
/// tolerate cases where the LPAI backend's tensor memory layout is NOT fully equal
/// to the logical shape (e.g. padded/bubble strides or an alternate storage order).
/// When this flag is not set, the backend assumes shape == layout (QNN_LPAI_OP_LAYOUT_DEFAULT)
/// and may insert transpose/repack ops to satisfy that assumption.
#define QNN_LPAI_OP_LAYOUT_SHAPE_NE_LAYOUT (1u << 0)

typedef Qnn_ErrorHandle_t (*QnnLpaiOpPackage_GetLayoutSupportFlag_Fn)(uint32_t* layoutFlag);

typedef struct _QnnOpPackage_OperationInfo_t {
  char* opType;
  LpaiCustomOp_Execute_Fn executeOp;

  // Info used by compiler
  uint32_t numOfInputs;
  uint32_t numOfOutputs;
  uint32_t numOfParams;
  QnnLpaiOpPackage_DataTypeInfo_t* inputDatatypes;   // array with numOfInputs elements
  QnnLpaiOpPackage_DataTypeInfo_t* outputDatatypes;  // array with numOfOutputs elements

  QnnLpaiOpPackage_Validate_Fn validateOp;
  QnnLpaiOpPackage_GetTempBufferSize_Fn getTempBufferSize;
  QnnLpaiOpPackage_GetLayoutSupportFlag_Fn getLayoutSupportFlag;
} QnnLpaiOpPackage_OperationInfo_t;

#endif
