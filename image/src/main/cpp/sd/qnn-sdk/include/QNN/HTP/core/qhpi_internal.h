// ==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause-Clear
//
// ==============================================================================
#pragma once

#include <cstdint>

#if defined(_MSC_VER)
#define PUSH_VISIBILITY_DEFAULT()
#define POP_VISIBILITY()
#else
#define PUSH_VISIBILITY_DEFAULT() _Pragma("GCC visibility push(default)")
#define POP_VISIBILITY()          _Pragma("GCC visibility pop")
#endif
PUSH_VISIBILITY_DEFAULT()
#include "qhpi.h"
POP_VISIBILITY()
typedef QHPI_OpInfo_v1 QHPI_OpInfo;
typedef QHPI_Kernel_v1 QHPI_Kernel;
typedef QHPI_Tensor_Signature_v1 QHPI_Tensor_Signature;
inline QHPI_StatusCode qhpi_register_ops(uint32_t num_ops, QHPI_OpInfo_v1 *operators, const char *package)
{
    return qhpi_register_ops_v1(num_ops, operators, package);
}
