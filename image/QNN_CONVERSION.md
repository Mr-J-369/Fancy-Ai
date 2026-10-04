# Aura QNN conversion and CPU file backing

## Model import

Aura Models → Import models offers the existing ZIP/SD1.5 MNN path and an
SD1.5/SDXL QNN conversion path. QNN conversion runs the native weight converter
and QAIRT context generator as child processes on the phone, using O=3. SDXL
retains the two disabled source-destructive settings from the successful
npuforge experiment, plus its storage-backed compiler allocator with small-object
pooling from 16 bytes, shared slabs and C++ allocation hooks. Each conversion's
working files live under `noBackupFilesDir`, outside reclaimable app cache.

Both families now convert the selected checkpoint's own CLIP encoder(s), token
and position embeddings, VAE encoder/decoder and UNet. Only the standard
tokenizer is shared. Conversion no longer downloads or consumes cached donor
weights. The existing SD1.5 MNN import already converts checkpoint components;
this change replaces the separate QNN import's shared-component path.

The native component writer emits the existing MNN runtime format directly:
SD1.5 keeps clip-skip 2 plus final layer normalization; SDXL uses FP16 CLIP-L
and the verified INT8 CLIP-G graph and embeddings. VAE encoder/decoder compile
sequentially before UNet, with 512px SD1.5 and 1024px SDXL templates. VAE graphs
keep float32 planar I/O with FP16 HTP arithmetic. VAEs requiring float32 internal
arithmetic and checkpoints missing their component weights remain unsupported.

UNet LoRA merging uses the existing imported Aura adapters before quantization.
The vendored converter retains npuforge's 128 MiB merged-weight cache and
supports DMD2 ResNet, sampler and embedding mappings. Extra unsupported or
unmatched tensors warn while recognized UNet layers merge. Text-encoder LoRA
merging remains unsupported. F16, F32 and BF16 checkpoint weights and UNet LoRA
weights are supported. UNet and VAE packs are temporary inputs,
removed after their compiler child exits. Main-process startup removes the
retired `qnn-packs` debug directory left by earlier builds.

The converted model is installed directly with SdModel's existing atomic
replacement owner, without generating and re-importing a multi-GB ZIP. Existing
models remain installed if conversion fails. SDXL receives the SDXL marker and
231_masked_v1 context marker, matching npuforge's successful Aura import.

Conversion uses FancyAI's foreground session and the existing app-wide
screen-awake flag. Aura Converter shows stage progress, RAM, elapsed time,
and Cancel. Native tool output is discarded instead of saved or displayed.
Cancellation kills and waits
for native children before removing work files. Leaving Aura cancels its owned
import job. The existing local-session mutex serializes conversion with local
LLM/image work; image models are unloaded, the LLM session is released and then
restored after conversion cleanup.

## Generation memory

QNN still loads precompiled context files through the existing read-only mmap
path. CPU-side input/output buffers of at least 64 KiB now use preallocated,
unlinked MAP_SHARED files. Buffers belong to their graph and are reused between
executions, retaining the original zero-initialization behavior. Patched-context
compressed/decompressed staging also uses file backing. Smaller buffers stay
on the normal heap. These changes do not intercept QNN-internal allocations or
register file mappings as DSP memory.

MNN SD1.5 and SDXL Low Memory mode now selects the existing CPU runtime's
weight/feature-map mmap allocators. Each stage has a unique backing directory;
normal release removes it. GPU buffers stay with their backend. CPU VAE and CPU
fallback operations can use the new backing even when OpenCL is selected.
Balanced and Speed First retain their prior allocation selection.

Two local MNN integration changes are recorded here:
- Interpreter::setExternalFile now routes ExternalPathType flags below 128 to
  Session::ModeGroup::setExternalPath. Its default 128 behavior, selecting the
  external .weight file, is unchanged. This exposes existing runtime mmap paths
  to Interpreter-based Aura models; it is a local extension, not an upstream API claim.
- On Android, auto-remove mmap files are unlinked immediately after successful
  mapping so process death releases the backing. Persistent weight caches keep
  their names and sync markers. Empty stage directories can remain after abrupt
  process death; the unlinked mappings do not retain their multi-GB payloads.

File backing uses storage and can add I/O latency. It is not physical RAM and
cannot offload GPU/NPU-owned buffers. The npuforge success proves the compiler
configuration for its tested phone, not these new Aura generation paths.

## Build inputs and provenance

The Linux NDK build task compiles vendored aura_qnn native source, MIT licensed
at revision c50e86bb70d44d61c43185a85cb4c6b53ac1a3a4. Its license, notice and origin are
under src/main/cpp/sd/third_party/aura_qnn. The tested generated templates are
under src/main/assets/qnn_convert: `template` / `template_sdxl` for UNet and
`components_sd15` / `components_sdxl` for checkpoint-owned components. Each
component directory contains the sparse CLIP recipe, standard tokenizer and
separate VAE encoder/decoder assets. The native writer is packaged as
`libaura_componentconv.so`; no new JNI or MNN dependency is introduced.
QAIRT compilation components are from the
same 2.50.0.260828 SDK as npuforge; the DSP preparation libraries are extracted
by the existing SkelExtractor alongside inference skeletons. That owner sets
the SDK directory to 0755 and its library files to 0444 for FastRPC access;
conversion no longer reapplies permissions after extraction. SDK/model assets
retain their separate terms and are not covered by the native source license.
New QNN binaries and generated conversion templates are ignored by Git and
must be supplied locally at the paths above before building; Gradle does not
download these build inputs. Existing tracked runtime libraries remain versioned.

## Verification status

All 30 component asset files match npuforge's verified revision byte for byte.
Native sources include the BF16 reader changes from npuforge revision 302a571,
with provenance and local inspection changes listed in the vendored README.
All 51 upstream regression tests pass against FancyAI's actual native sources,
including BF16 CLIP/VAE/UNet and LoRA coverage, existing F16/F32 cases and the
compiler allocator. The targeted Android native build and source-size check
also pass. These host results do not establish device execution of this FancyAI
integration.

On 2026-09-16, `:app:assembleGithubDebug` and `checkCodeSize` passed after the
latest conversion fixes were ported.
The APK contains all 30 component assets with exact input hashes, both native
weight writers, and the compiler allocator. All three native binaries are ARM64
with 16 KB load alignment; the allocator retains its C/C++ allocation exports.
APK signature and 16 KB ZIP alignment checks pass. Source-size and diff checks
also pass. Android Studio file inspection was unavailable because FancyAI was
not open in the running IDE.

`:app:lintGithubDebug` ran and failed on 161 resource findings: 138 duplicate
strings, 10 ellipsis, 8 plural-quantity, and one each for spelling, locale config,
an unused resource, quotation typography and button capitalization. Every
finding matches the saved pre-port lint report exactly, including its message
and location: zero new or changed findings. These existing resource findings
remain unresolved; lint is not clean. No baseline or suppression was added.

No phone installation or model conversion was performed. The existing atomic
model replacement, child-process cancellation and LLM release/restore owner
remain in place. Phone verification still covers full conversion, cancellation,
image quality and image-to-image for both families.
Earlier npuforge O=3 results were 437 seconds total conversion and 15 seconds for
1024×1024, 8-step CFG=1 generation; these are not FancyAI conversion or new
memory-path measurements.
