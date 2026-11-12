// ==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause-Clear
//
// ==============================================================================

#ifndef UNIQUE_TYPES_H
#define UNIQUE_TYPES_H 1

namespace {
template <int K> struct UniqTy {};
} // namespace

#define DEFINE_UNIQ_TY()
#define UNIQUE_TYPE UniqTy<__LINE__>
#endif
