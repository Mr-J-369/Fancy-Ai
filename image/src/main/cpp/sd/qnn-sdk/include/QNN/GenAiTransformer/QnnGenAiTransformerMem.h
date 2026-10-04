//==============================================================================
//
//  Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
//  All rights reserved.
//  Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

#ifndef QNN_GENAI_TRANSFORMER_CUSTOM_MEMORY_H
#define QNN_GENAI_TRANSFORMER_CUSTOM_MEMORY_H

#include "System/QnnSystemDlc.h"
#include "System/QnnSystemInterface.h"

/** @file
 *  @brief QNN GenAiTransformer Custom Memory components
 */

typedef size_t QnnGteRecordDataSize_t;

typedef void* QnnGteRecordDataPtr_t;

/**
 * @brief This enum defines QNN Gte memory type
 */
typedef enum { QNN_GTE_MEM_DLC_INFO = 0, QNN_GTE_MEM_UNDEFINED = 0x7FFFFFFF } QnnGteMemType_t;

/** Callback type for reading from a buffer.
 * @param[in] ctx User-provided context pointer.
 * @param[in] offset Byte offset from the start of the file/buffer to read from.
 * @param[in] length Number of bytes to read.
 * @param[out] dst Destination buffer to copy data into.
 * @return Number of bytes actually read, or negative on error.
 */
typedef int64_t (*QnnGteReadFromDlcFn_t)(void* ctx, char* dst, size_t offset, size_t length);

/** Callback type for freeing a region of the buffer.
 * @param[in] ctx User-provided context pointer.
 * @param[in] offset Byte offset from the start of the file/buffer to free from.
 * @param[in] length Number of bytes to free.
 */
typedef void (*QnnGteFreeFromDlcFn_t)(void* ctx, size_t offset, size_t length);

typedef struct {
  QnnSystemDlc_RecordHandle_t      recordHandle;
  QnnGteRecordDataSize_t           recordDataSize;
  QnnGteRecordDataPtr_t            recordDataPtr;
  QnnSystemDlc_freeRecordRangeFn_t freeRecordRangeFnPtr;
  QnnGteReadFromDlcFn_t            readFunctionPtr;
  QnnGteFreeFromDlcFn_t            freeFunctionPtr;
} QnnGteMemBuffer_t;

#define QNN_GENAI_TRANSFORMER_MEM_BUFFER_INIT \
  {                                           \
        NULL, /*recordHandle*/                \
        0,    /*recordSize*/                  \
        NULL, /*recordDataPtr*/               \
        NULL, /*freeRecordRangeFnPtr*/        \
        NULL, /*readFunctionPtr*/             \
        NULL  /*freeFunctionPtr*/             \
  }

/**
 * @brief A struct which defines the QNN GenAiTransformer memory pre-allocated by the client.
 *        Objects of this type are to be referenced through Qnn_MemInfoCustom_t.
 */
typedef struct {
  QnnGteMemType_t memType;
  union {
    QnnGteMemBuffer_t buffer;
  };
} QnnMemGte_Descriptor_t;

/// Macro to set QnnMemGte_Descriptor_t for QNN GenAiTransformer backend
#define QNN_GENAI_TRANSFORMER_CUSTOM_MEM_INFO_INIT \
  {                                                \
    QNN_GTE_MEM_UNDEFINED,                         \
    QNN_GENAI_TRANSFORMER_MEM_BUFFER_INIT          \
  }

#endif  // QNN_GENAI_TRANSFORMER_CUSTOM_MEMORY_H
