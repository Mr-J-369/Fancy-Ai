# Release Notes

## v3.0.5 — On-Device LLM: llama.cpp + GPU Acceleration

This is a **huge native update**: the on-device intelligence stack was migrated off MNN and rebuilt on **llama.cpp**, with a real multi-backend inference engine and a memory-aware load/offload pipeline. Characters can now think **fully offline** — no cloud, no API key.

### ✨ Highlights
- **llama.cpp engine (JNI/C++)** replaces the previous MNN integration end-to-end.
- **Multi-backend inference**, selectable at runtime (Settings → On-Device LLM → Inference Hardware):
  - **CPU** — NEON-optimized, compiled at `-O3` even in debug builds.
  - **GPU (OpenCL)** — runs the full graph on the **Adreno** GPU; **Vulkan** fallback for other GPUs.
- **Any standard GGUF** model (Q4_K_M, Q5_K, Q8_0, Q4_0, …) via a themed **Active Model** picker.
- **KV-cache precision** selector — F16 / Q8_0 / Q4_0 (auto-enables flash attention when quantized).
- **Load/offload pipeline** — model loads on entering an AI app, unloads on returning Home; native mutex makes teardown crash-safe even mid-generation.
- **Streaming + responsive Stop** (now cancellable during prompt prefill, not just between tokens).

### 🛠️ Fixes
- Fixed catastrophically slow CPU inference (native libs were compiled `-O0` in debug; now forced `-O3`).
- Removed a broken Hexagon skeleton-signing task that re-signed its own output every build, ballooning `jniLibs` to ~459 MB (now ~66 MB).
- Made model loading **idempotent** to stop double-loading races between the Settings loader and lazy chat loads.
- Clear, specific errors when a model can't load (e.g. the deprecated `Q4_0_4_4` / `_4_8` / `_8_8` formats, which upstream llama.cpp removed — use a plain `Q4_0`).

### ⚠️ Known limitation — Hexagon NPU
The NPU backend is built and wired, but on retail/locked-down Snapdragon devices Qualcomm's cDSP gates third-party compute behind a **signed process domain**. Unsigned-PD inference loads the model but times out on compute (FastRPC `0xc`). **Use GPU (OpenCL) for acceleration; CPU is the universal fallback.**

### 🧱 Build notes
- Native build is unified (CPU + Vulkan + OpenCL + Hexagon) via `app/src/main/cpp/CMakeLists.txt`.
- Vulkan needs SPIRV-Headers + Vulkan-Headers; OpenCL needs OpenCL-Headers + an ICD loader — all vendored under `app/src/main/cpp/third_party/`.
- `libOpenCL.so` is intentionally **excluded** from the APK so it resolves to the device's vendor Adreno driver at runtime.
