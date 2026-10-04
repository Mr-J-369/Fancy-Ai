Source: https://github.com/AbrahamPaulJ/npuforge
Revision: c50e86bb70d44d61c43185a85cb4c6b53ac1a3a4

MIT-licensed checkpoint weight converters and compiler allocator, imported from
`native/` at the revision above. `LICENSE` and `NOTICE` are copied verbatim.
`compiler_new.cpp` supplies the upstream C++ allocation
hooks alongside `compiler_heap.c` and its symbol map. This revision includes
small-object compiler allocation pooling, DMD2 ResNet/sampler mappings, and
warning-only handling of extra unmatched LoRA tensors.

BF16 tensor decoding for checkpoint UNet/VAE/CLIP weights and UNet LoRAs is
ported from npuforge revision 302a57143a5840f96925a548be168cfece7eeb18.

Local inspection fixes guard `_GNU_SOURCE`, rename a shadowed failure-log buffer,
and mark the non-returning `die` function `[[noreturn]]`. These do not change the
conversion or allocation behavior. The project `.clangd` sets this directly
compiled allocator source to C11, matching `CompileQnnConversion`.

Locally generated CLIP recipes and VAE templates for SD1.5 and SDXL live under
the ignored `image/src/main/assets/qnn_convert/components_sd15` and
`components_sdxl` directories. SDK and model artifacts have separate provenance;
see `NOTICE` and the upstream `docs/CLIP-COMPONENTS.md`, `docs/VAE-TEMPLATES.md`,
and `docs/SD15-COMPONENTS.md`. The MIT license does not relicense these artifacts.
