//==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause-Clear
//
//==============================================================================
#ifndef HEXNN_FAKE_TENSOR_H
#define HEXNN_FAKE_TENSOR_H 1

#include "tensor_base.h"
#include "macros_attribute.h"

#include <cstddef>
#include <memory>
#include <utility>

namespace hnnx {
class Allocator;
class MemBlockEnumerator;
class InterfaceRef;
} // namespace hnnx

// A FakeTensor is intended as an intermediate base for special subclasses which
// need to be based on Tensor but don't need to support most of the interface.
// subclassing should be done in .cc files or private headers where possible.
//
// All of the abstract 'virtual=0' methods (other than get_dtype) are overridden here;
// many (those shown as protected) will all throw exceptions if called; the others do
// null things as shown.
// So when you subclass, just override whatever ones you need and leave the rest.
//
// In particular, get_dtype() returns DType::None.
//
class FakeTensor : public Tensor {
  public:
    explicit FakeTensor(const Op *producer_in) : Tensor(producer_in) {}
    API_EXPORT explicit FakeTensor(hnnx::Deserz &);

  protected:
    // all will throw exception if called
    API_EXPORT void *element_addr(size_t rank, SIdx const coords_in[],
                                  hnnx::InterfaceRef *iref = nullptr) const noexcept override;
    API_EXPORT hnnx::InterfaceRef interface() const noexcept override;
    API_EXPORT void allocate_func(hnnx::Allocator &allocator, unsigned options) override;
    API_EXPORT void *raw_data() noexcept override;
    API_EXPORT size_t total_storage_elements() const override;
    API_EXPORT size_t total_storage_bytes() const override;
    API_EXPORT void **clone_util(hnnx::Allocator *allocator, std::unique_ptr<Tensor> *tensp,
                                 Tensor::tensor_blockinfo *infop, const OutputDef *od = nullptr) const override;
    API_EXPORT int compare_sametype(const Tensor *rhs) const override;

  public:
    // defined as shown
    API_EXPORT size_t rank() const noexcept override; //->0
    API_EXPORT size_t dim(size_t index) const noexcept override; //->0
    API_EXPORT std::pair<size_t const *, size_t> get_dims() const noexcept override; //->{null,0}
    API_EXPORT bool set_dims(const size_t dims[]) override; // -> false
    API_EXPORT bool set_dims(const Tensor &prototype) override; // ->false
    API_EXPORT void enum_memory_blocks(hnnx::MemBlockEnumerator &) const override; // nothing

    API_EXPORT DTypeScaleOff get_dtype_intfc() const noexcept override; // { return DTypeScaleOff(None); }
};

#endif
