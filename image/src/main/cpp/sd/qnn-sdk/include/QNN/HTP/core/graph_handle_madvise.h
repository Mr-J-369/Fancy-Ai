// ==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause-Clear
//
// ==============================================================================

// LCOV_EXCL_START [SAFTYSWCCB-1735]

#ifndef GRAPH_HANDLE_MADVISE_H
#define GRAPH_HANDLE_MADVISE_H 1

#include <cstdint>
#include "graph_handle_defs.h"

namespace GHANDLE_NS {

// Send MADVISE prefetch hint with up to 8 VVAs to the host.
// No-op if demand paging is not active.
GHANDLE_ACCESS_FUNC_QUALIFIER void //
send_madvise(hnnx::GHandle, const uint64_t *vvas, uint32_t count);

} // namespace GHANDLE_NS

#if GHANDLE_INCLUDE_IMPL
#include "graph_handle_madvise_impl.h"
#endif

#endif // GRAPH_HANDLE_MADVISE_H

// LCOV_EXCL_STOP
