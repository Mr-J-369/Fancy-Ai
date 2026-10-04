// ==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause-Clear
//
// ==============================================================================

#pragma once

// Shared wire format for AuxTag_moeVaTable (prepare writer, runtime cache, tooling reader), plus
// the pickle/barrel-walking logic needed to locate and read that record out of a raw buffer
// (moe_va_pickle). pickle_metadata.h's hnnx::get_moe_va_table_info is the
// only MOE-specific function left in that header; it's a thin wrapper over what's here.
//
// Payload shape: a `num_blobs` prefix word, then `num_blobs` blob-table rows
// (MoeVaTableWireFormat::BLOB_ENTRY_WORDS each: blob_id + CBNAME), then a run of fixed
// MoeVaTableWireFormat::RECORD_WORDS-word entry records.
//
// This header is included standalone (without pickle_metadata.h) by
// hexagon/prepare/dynamic_inputs/flash_backed_moe_va_table.cc,
// hexagon/wtshare/wtshare_operation_flash_backed_moe.cc, and
// hexagon/src/dynamic_inputs/moe_va_table_runtime.cc, so everything here must be self-contained:
// the pickle-walking functions below return bool rather than pickle_metadata.h's PickleStatus_t.

#include <algorithm>
#include <array>
#include <cstdint>
#include <vector>

#include "auxdata_shared.h"
#include "pickle_header_tags.h"
#include "single_core_barrel_header.h"

namespace hnnx {

struct MoeVaTableWireFormat {
    // Named word indices within a RECORD_WORDS-word entry record. Every reader/writer of the
    // wire format uses these names in place of magic ep[N] offsets.
    enum EntryWord : unsigned {
        VA_OFFSET_LO,
        VA_OFFSET_HI,
        MOE_LAYER_ID,
        MOE_EXPERT_ID,
        VA_SIZE_LO,
        VA_SIZE_HI,
        POOL_SIZE_LO,
        POOL_SIZE_HI,
        OFFSET_LO,
        OFFSET_HI,
        POOL_ID,
        LOCATION,
        BLOB_TABLE_IDX,
    };
    // Words per entry record.
    static constexpr unsigned RECORD_WORDS = 13;
    static_assert(static_cast<unsigned>(BLOB_TABLE_IDX) + 1u == RECORD_WORDS,
                  "RECORD_WORDS must be one past the last EntryWord");

    // Word indices within a BLOB_ENTRY_WORDS-word blob-table row: the blob_id word, and where the
    // CBNAME (CBNAME_WORDS words) begins.
    static constexpr unsigned BLOB_ID = 0;
    static constexpr unsigned CBNAME_START = 1;
    static_assert(BLOB_ID + 1u == CBNAME_START, "CBNAME_START must be one past BLOB_ID");

    // Words per blob-table CBNAME: 45-byte CBNAME, zero-padded to a 4-byte multiple (48 bytes).
    static constexpr unsigned CBNAME_WORDS = 12;
    // Words per blob-table row: blob_id (1 word) + CBNAME.
    static constexpr unsigned BLOB_ENTRY_WORDS = 1u + CBNAME_WORDS;

    static_assert(CBNAME_START + CBNAME_WORDS == BLOB_ENTRY_WORDS,
                  "blob-table row layout must cover exactly BLOB_ENTRY_WORDS");

    // Encoded values for the `location` field.
    enum Location : uint32_t { IN_PICKLE, IN_BLOB };
};

///
/// @brief One MOE flash-backed VA-table entry: where a single MOE switchable const-mempool's
/// bytes live, and the relative VA offset it should be mapped at.
/// @see MoeVaTableWireFormat::parse_moe_va_table_payload, hnnx::get_moe_va_table_info
///
struct MoeVaPoolEntry {
    /// Relative VA offset; add the caller-chosen VA base to get the pool's mapped VA.
    uint64_t va_offset = 0;
    /// VA bytes reserved for this pool (pool_size rounded up to the VA page granule).
    uint64_t va_size = 0;
    /// Actual pool byte size (PoolDesc.length).
    uint64_t pool_size = 0;
    /// Byte offset to the pool's const data: from start of pickle if location == 0, from start
    /// of the shared blob file if location == 1.
    uint64_t offset = 0;
    /// 1-based mempool id in the source graph.
    uint32_t pool_id = 0;
    /// 0 = bytes are in the pickle (see offset); 1 = bytes are in a shared blob (see offset).
    uint32_t location = 0;
    /// If location == 1: wtshare external blob number. Else 0.
    uint32_t blob_id = 0;
    /// 0-based MOE layer id.
    uint16_t moe_layer_id = 0;
    /// MOE expert id (SwitchablePoolInfo.select_index).
    uint16_t moe_expert_id = 0;
    /// If location == 1: 45-byte CBNAME, null-padded to 48 bytes. Else all zero.
    std::array<char, MoeVaTableWireFormat::CBNAME_WORDS * 4> blob_cbname{};
};

///
/// @brief Shape + entries of the AuxTag_moeVaTable record in one core of a prepared pickle.
/// @see MoeVaTableWireFormat::parse_moe_va_table_shape, MoeVaTableWireFormat::parse_moe_va_table_payload,
/// hnnx::get_moe_va_table_info
///
struct MoeVaTableInfo {
    /// Number of per-pool entries; 0 means there is no AuxTag_moeVaTable record.
    uint32_t entry_count = 0;
    /// Rows in the shared-blob table; 0 for an all-in-pickle table.
    uint32_t num_blobs = 0;
    /// Total relative VA range the table consumes, in bytes: the high-water mark of
    /// (va_offset + va_size) across all entries. This is the size of the VA region the
    /// consumer must reserve; add the chosen VA base to it for the end address. 0 when empty.
    uint64_t total_va_size = 0;
    /// One entry per MOE switchable pool; empty when entry_count == 0.
    std::vector<MoeVaPoolEntry> entries;
};

namespace moe_va_wire {

/// Combine a lo/hi word pair into a uint64_t.
inline uint64_t u64_from_words(uint32_t lo, uint32_t hi)
{
    return static_cast<uint64_t>(lo) | (static_cast<uint64_t>(hi) << 32u);
}

/// Validate a payload's prefix + blob-table region and report @ref num_blobs and @ref num_entries.
/// Returns false with both out params zeroed if the payload is malformed (too small to hold its
/// own blob-table, or entry region not a whole number of records). An empty @p payload yields
/// @c true with both outputs zero (no AuxTag_moeVaTable record; not an error).
inline bool validate_payload_shape(std::vector<uint32_t> const &payload, unsigned &num_blobs_out,
                                   unsigned &num_entries_out)
{
    num_blobs_out = 0;
    num_entries_out = 0;
    if (payload.empty()) {
        return true; // no record; not an error
    }
    unsigned const num_blobs = payload[0];
    unsigned const prefix_words = 1u + num_blobs * MoeVaTableWireFormat::BLOB_ENTRY_WORDS;
    if (payload.size() < prefix_words) {
        return false; // payload too small for its own blob table
    }
    unsigned const entries_words = static_cast<unsigned>(payload.size()) - prefix_words;
    if (entries_words % MoeVaTableWireFormat::RECORD_WORDS != 0u) {
        return false; // entry region is not a whole number of records
    }
    num_blobs_out = num_blobs;
    num_entries_out = entries_words / MoeVaTableWireFormat::RECORD_WORDS;
    return true;
}

/// Decode one entry record (RECORD_WORDS words at @p ep) into @p out. The 1-based blob-table
/// index parsed from the record is written to @p table_idx_out; the caller is responsible for
/// bounds-checking it and populating out.blob_id / out.blob_cbname from the blob-table row.
inline void decode_entry_words(uint32_t const *ep, MoeVaPoolEntry &out, unsigned &table_idx_out)
{
    using W = MoeVaTableWireFormat;
    out.va_offset = u64_from_words(ep[W::VA_OFFSET_LO], ep[W::VA_OFFSET_HI]);
    out.moe_layer_id = static_cast<uint16_t>(ep[W::MOE_LAYER_ID]);
    out.moe_expert_id = static_cast<uint16_t>(ep[W::MOE_EXPERT_ID]);
    out.va_size = u64_from_words(ep[W::VA_SIZE_LO], ep[W::VA_SIZE_HI]);
    out.pool_size = u64_from_words(ep[W::POOL_SIZE_LO], ep[W::POOL_SIZE_HI]);
    out.offset = u64_from_words(ep[W::OFFSET_LO], ep[W::OFFSET_HI]);
    out.pool_id = ep[W::POOL_ID];
    out.location = ep[W::LOCATION];
    table_idx_out = ep[W::BLOB_TABLE_IDX];
}

} // namespace moe_va_wire

/// Validate the prefix + blob table of @p payload and report the resulting shape. An empty
/// @p payload (no AuxTag_moeVaTable record) yields a zeroed @p info_out and success.
/// Returns @c false on a malformed payload (@p info_out zeroed).
inline bool parse_moe_va_table_shape(std::vector<uint32_t> const &payload, MoeVaTableInfo &info_out)
{
    info_out = MoeVaTableInfo{};
    unsigned num_blobs = 0, num_entries = 0;
    if (!moe_va_wire::validate_payload_shape(payload, num_blobs, num_entries)) {
        return false;
    }
    info_out.entry_count = num_entries;
    info_out.num_blobs = num_blobs;

    // Total VA range = max(va_offset + va_size) across entries.
    unsigned const prefix_words = 1u + num_blobs * MoeVaTableWireFormat::BLOB_ENTRY_WORDS;
    uint32_t const *ep = payload.data() + prefix_words;
    using W = MoeVaTableWireFormat;
    for (unsigned e = 0u; e < num_entries; ++e, ep += W::RECORD_WORDS) {
        uint64_t const entry_end = moe_va_wire::u64_from_words(ep[W::VA_OFFSET_LO], ep[W::VA_OFFSET_HI]) +
                                   moe_va_wire::u64_from_words(ep[W::VA_SIZE_LO], ep[W::VA_SIZE_HI]);
        info_out.total_va_size = std::max(info_out.total_va_size, entry_end);
    }
    return true;
}

/// Fill @p entries_out with the entries described by @p payload, replacing its prior contents.
/// An empty @p payload (no AuxTag_moeVaTable record) yields an empty @p entries_out and success.
/// Returns @c false on a malformed payload (@p entries_out is cleared).
inline bool parse_moe_va_table_payload(std::vector<uint32_t> const &payload, std::vector<MoeVaPoolEntry> &entries_out)
{
    entries_out.clear();
    unsigned num_blobs = 0, num_entries = 0;
    if (!moe_va_wire::validate_payload_shape(payload, num_blobs, num_entries)) {
        return false;
    }
    if (num_entries == 0u) {
        return true; // no record, or a record with no entries
    }

    using W = MoeVaTableWireFormat;

    unsigned const prefix_words = 1u + num_blobs * W::BLOB_ENTRY_WORDS;
    entries_out.resize(num_entries);
    uint32_t const *ep = payload.data() + prefix_words;
    for (unsigned e = 0u; e < num_entries; ++e, ep += W::RECORD_WORDS) {
        MoeVaPoolEntry &out = entries_out[e];
        unsigned table_idx = 0;
        moe_va_wire::decode_entry_words(ep, out, table_idx);

        if (out.location == W::IN_BLOB) {
            if (table_idx < 1u || table_idx > num_blobs) {
                entries_out.clear();
                return false; // blob-table index out of range
            }
            uint32_t const *blob_row = payload.data() + 1u + (table_idx - 1u) * W::BLOB_ENTRY_WORDS;
            out.blob_id = blob_row[W::BLOB_ID];
            static_assert(sizeof(out.blob_cbname) == W::CBNAME_WORDS * 4u,
                          "blob_cbname must match CBNAME_WORDS * 4 bytes");
            char const *const cbname_src = reinterpret_cast<char const *>(blob_row + W::CBNAME_START);
            std::copy(cbname_src, cbname_src + out.blob_cbname.size(), out.blob_cbname.begin());
        }
    }
    return true;
}

// Pickle/barrel-walking prologue for locating and reading the AuxTag_moeVaTable record out of a
// raw buffer. Returns bool rather than pickle_metadata.h's PickleStatus_t (see file header comment
// for why); pickle_metadata.h's get_moe_va_table_info maps failures here to PICKLE_READ_ERROR.
namespace moe_va_pickle {

/// Read the uint32 at byte offset @p posn in @p buf (@p buf_len bytes long) into @p out_word.
/// Returns false, leaving @p out_word unset, if the 4-byte read would run past @p buf_len.
inline bool try_read_uint32_at(const unsigned char *buf, uint32_t buf_len, uint64_t posn, uint32_t &out_word)
{
    if (posn + 4u > buf_len) return false;
    unsigned char *const out_word_bytes = reinterpret_cast<unsigned char *>(&out_word);
    std::copy(buf + posn, buf + posn + sizeof(out_word), out_word_bytes);
    return true;
}

/// Classify @p pickle_buf as a single-core pickle (Hdr_MAGIC) or a barrel (Hdr_MAGIC_MULTI),
/// reporting which in @p magic_out.
inline bool classify_pickle(const unsigned char *pickle_buf, uint32_t pickle_len, uint32_t &magic_out)
{
    if (pickle_buf == nullptr || pickle_len < 8u) {
        return false;
    }
    uint32_t magic = 0;
    unsigned char *const magic_bytes = reinterpret_cast<unsigned char *>(&magic);
    std::copy(pickle_buf, pickle_buf + sizeof(magic), magic_bytes);
    if (magic != Hdr_MAGIC && magic != Hdr_MAGIC_MULTI) {
        return false;
    }
    magic_out = magic;
    return true;
}

/// Narrow a barrel buffer to its @p core_idx-th NSP pickle, reported as
/// @p nsp_buf_out / @p nsp_len_out.
inline bool locate_nsp_pickle_in_barrel(const unsigned char *barrel_buf, uint32_t barrel_len, uint32_t core_idx,
                                        const unsigned char *&nsp_buf_out, uint32_t &nsp_len_out)
{
    size_t blob_size = 0;
    unsigned blob_id = 0;
    size_t const blob_offset = htp_header_barrel_blob_loc(barrel_buf, barrel_len, core_idx, blob_size, blob_id);
    if (blob_offset == 0 || blob_size == 0 || blob_offset + blob_size > barrel_len) {
        return false;
    }
    nsp_buf_out = barrel_buf + blob_offset;
    nsp_len_out = static_cast<uint32_t>(blob_size);
    return true;
}

/// Scan @p pickle_buf — already narrowed to a single-core pickle, i.e. starting with Hdr_MAGIC —
/// for the AuxTag_moeVaTable record and copy its payload words into @p payload_out. An empty
/// @p payload_out on success means the record is absent (feature off / no MOE pools), which is
/// not an error.
inline bool read_table_payload(const unsigned char *pickle_buf, uint32_t pickle_len, std::vector<uint32_t> &payload_out)
{
    payload_out.clear();

    unsigned const total_words = pickle_len / 4u;

    // Validate and skip the single-core pickle header. pickle_len >= 8 is already guaranteed by
    // classify_pickle(), so these first two reads cannot go out of bounds.
    uint32_t header_0 = 0, header_1 = 0;
    try_read_uint32_at(pickle_buf, pickle_len, 0, header_0);
    try_read_uint32_at(pickle_buf, pickle_len, 4, header_1);
    unsigned const hdr_len = header_1 & 0xFFFFu;
    unsigned const hdr_ver = header_1 >> 16u;
    if (header_0 != Hdr_MAGIC || hdr_ver != HdrVersion_VERSION || hdr_len < 4u || hdr_len + 5u > total_words) {
        return false;
    }

    // Walk aux records. AuxTag_moeVaTable is a 16-bit value stored in the upper 16 bits of the
    // tag word. The first tag-word read is guaranteed in bounds by the hdr_len + 5u <= total_words
    // check above; reads inside the loop are individually bounds-checked via try_read_uint32_at.
    uint64_t posn = static_cast<uint64_t>(hdr_len) * 4u;
    uint32_t next_word = 0;
    std::copy(pickle_buf + posn, pickle_buf + posn + sizeof(next_word), reinterpret_cast<unsigned char *>(&next_word));
    posn += 4u;
    while (next_word != serialization_AUX_END) {
        unsigned const tag = next_word >> 16u;
        uint64_t const after_tag = posn; // position right after the tag word
        uint32_t payload_len = 0;
        if (!try_read_uint32_at(pickle_buf, pickle_len, posn, payload_len)) {
            return false; // truncated aux record
        }
        posn += 4u;
        // Widen payload_len before the +1 so a crafted 0xFFFFFFFF cannot wrap in u32 and
        // produce a record_end that spuriously fits under pickle_len.
        uint64_t const record_end = after_tag + (static_cast<uint64_t>(payload_len) + 1u) * 4u;

        if (record_end > pickle_len) {
            return false; // truncated aux record
        }

        if (tag == AuxTag_moeVaTable) {
            payload_out.resize(payload_len);
            unsigned char *const payload_bytes = reinterpret_cast<unsigned char *>(payload_out.data());
            std::copy(pickle_buf + posn, pickle_buf + posn + static_cast<size_t>(payload_len) * 4u, payload_bytes);
            return true;
        }

        posn = record_end;
        if (!try_read_uint32_at(pickle_buf, pickle_len, posn, next_word)) {
            return false; // truncated pickle, missing AUX_END
        }
        posn += 4u;
    }
    // AUX_END reached without finding the record — legitimate (feature off / no MOE pools).
    return true;
}

/// Shared prologue for the per-core entry points: classify the buffer, narrow a barrel to the
/// requested NSP pickle, and read out the AuxTag_moeVaTable payload (empty if absent).
inline bool read_payload_for_core(const unsigned char *pickle_buf, uint32_t pickle_len, uint32_t core_idx,
                                  std::vector<uint32_t> &payload_out)
{
    payload_out.clear();
    uint32_t magic = 0;
    if (!classify_pickle(pickle_buf, pickle_len, magic)) {
        return false;
    }

    const unsigned char *nsp_buf = pickle_buf;
    uint32_t nsp_len = pickle_len;
    if (magic == Hdr_MAGIC_MULTI) {
        if (!locate_nsp_pickle_in_barrel(pickle_buf, pickle_len, core_idx, nsp_buf, nsp_len)) {
            return false;
        }
    } else if (core_idx != 0) {
        return false; // core_idx > 0 on a single-core pickle
    }

    return read_table_payload(nsp_buf, nsp_len, payload_out);
}

} // namespace moe_va_pickle

} // namespace hnnx
