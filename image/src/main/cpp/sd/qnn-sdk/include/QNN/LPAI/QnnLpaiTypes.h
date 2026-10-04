//==============================================================================
//
//  Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
//  All rights reserved.
//  Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

/** @file
 *  @brief QNN LPAI Types
 *
 *         Defines default configurations and property type definitions
 *         for LPAI.
 */

#ifndef QNN_LPAI_TYPES_H
#define QNN_LPAI_TYPES_H

//==============================================================================
// Backend config Types
//==============================================================================

// Backend default Alignment property version
#define QNN_LPAI_BACKEND_GET_PROP_ALIGNMENT_REQ_DEFAULT 0

// Backend default persistent binary version
#define QNN_LPAI_BACKEND_GET_PROP_REQUIRE_PERSISTENT_BINARY_DEFAULT 1

// Backend default eNpu core num version
#define QNN_LPAI_BACKEND_GET_PROP_ENPU_CORE_NUM_DEFAULT 200

// Backend default eNpu core info version
#define QNN_LPAI_BACKEND_GET_PROP_ENPU_CORE_INFO_DEFAULT 300

// Backend default eNPU clock frequency version
#define QNN_LPAI_BACKEND_GET_PROP_ENPU_CLK_FREQ_DEFAULT 500

//==============================================================================
// Context config Types
//==============================================================================

// Context default memory config version
#define QNN_LPAI_CONTEXT_SET_CFG_MODEL_BUFFER_MEM_TYPE_DEFAULT 1

// Context default island type version
#define QNN_LPAI_CONTEXT_SET_CFG_ENABLE_ISLAND_DEFAULT 11

//==============================================================================
// Graph config Types
//==============================================================================

// Graph default scratch memory config version
#define QNN_LPAI_GRAPH_SET_CFG_SCRATCH_MEM_DEFAULT 1

// Graph default scratch memory property version
#define QNN_LPAI_GRAPH_GET_PROP_SCRATCH_MEM_SIZE_DEFAULT 1

// Graph default persistent memory config version
#define QNN_LPAI_GRAPH_SET_CFG_PERSISTENT_MEM_DEFAULT 101

// Graph default persistent memory property version
#define QNN_LPAI_GRAPH_GET_PROP_PERSISTENT_MEM_SIZE_DEFAULT 101

// Graph default PerfConfig version
#define QNN_LPAI_GRAPH_SET_CFG_PERF_CFG_DEFAULT 201

// Graph default EnpuPerfConfig version
#define QNN_LPAI_GRAPH_SET_CFG_ENPU_PERF_CFG_DEFAULT 701

// Graph default Core Affinity config version
#define QNN_LPAI_GRAPH_SET_CFG_CORE_AFFINITY_DEFAULT 301

// Graph default pause execution config version
#define QNN_LPAI_GRAPH_SET_CFG_PAUSE_EXECUTION_DEFAULT 401

// Graph default resume execution config version
#define QNN_LPAI_GRAPH_SET_CFG_RESUME_EXECUTION_DEFAULT 501

// Graph default batch-multiple execution config version
#define QNN_LPAI_GRAPH_SET_CFG_BATCH_MULTIPLE_SUPPORT_DEFAULT 601

// Graph default prepare config version
#define QNN_LPAI_GRAPH_SET_CFG_PREPARE_DEFAULT 10001

#endif  // QNN_LPAI_TYPES_H
