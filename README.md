# Fancy AI

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Support on Ko-fi](https://img.shields.io/badge/Support_on-Ko--fi-FF5E5B?style=flat&logo=ko-fi&logoColor=white)](https://ko-fi.com/mrj369)
[![Platform](https://img.shields.io/badge/Platform-Android_64--bit-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-brightgreen.svg)](https://developer.android.com/jetpack/compose)

> **A completely private, offline-first AI world living entirely on your Android device.**  
> Characters who chat, remember, call, post, play, and create — with local AI at the center.

Fancy AI is a fully on-device ecosystem designed to run local language models, image diffusion generators, and speech synthesis directly on Android hardware without requiring an internet connection or cloud subscriptions.

---

## Key Highlights

- **100% Offline & Private:** Your conversations, generated photos, character memories, and voice samples never leave your device.
- **On-Device LLMs:** Run open-weights language models locally via Alibaba MNN or GGUF (llama.cpp) backends. Cloud API keys (OpenRouter, DeepSeek, Anthropic, OpenAI) remain completely optional.
- **Local Diffusion Studio (Aura):** Generate images offline using Stable Diffusion 1.5, SDXL, and FLUX.2 Klein models. Features on-device `.safetensors` to `.mnn` conversion, LCM acceleration, and ESRGAN upscaling.
- **Local Voice & Speech:** Talk with characters using on-device Speech-to-Text (Whisper, Zipformer) and realistic Text-to-Speech (Kokoro, Pocket, Supertonic).
- **Persistent Vector Memory:** Semantic retrieval powered by multilingual MiniLM on-device embeddings, allowing characters to remember past conversations and context.
- **Living Character World:** Simulated messaging, multi-character group chats, simulated social networks (*Ustagram*, *Rebbit*, *Y*), and character generation.
- **Linux Terminal Subsystem:** Integrated PRoot sandboxed Linux environment and terminal emulator inside the app.

---

## Architecture & Project Structure

The project is structured into modular Android and C++/Rust libraries:

```text
├── app/          # Jetpack Compose UI, Material 3, navigation, and controllers
├── engine/       # Core LLM inference engines (MNN, llama.cpp) and tokenizers
├── image/        # Aura diffusion engine, on-device SafeTensor converter, ESRGAN upscaler
├── voice/        # Local STT (Whisper, Zipformer) and TTS (Kokoro, Pocket, Supertonic)
├── memory/       # Semantic memory vector embedding runtime (MiniLM via ONNX Runtime)
├── terminal/     # PRoot-based sandboxed Linux subsystem and terminal UI
├── vision/       # Image processing and vision pipeline
└── tools/        # DiT engine patches and model export utilities
```

---

## Hardware Acceleration

- **CPU:** Highly optimized NEON assembly paths for broad compatibility across 64-bit ARM devices (`arm64-v8a`).
- **GPU (OpenCL / Vulkan):** Supported on compatible Adreno and Mali GPUs for image diffusion and neural network layers.
- **Qualcomm Snapdragon NPU:** Support for Hexagon NPU hardware pipelines on compatible Snapdragon chipsets.

---

## Getting Started

### Prerequisites

- **Android Studio:** Ladybug (2024.2.1) or newer
- **JDK:** Java 21
- **Android SDK:** Platform API 35 (targetSdk 35, minSdk 28)
- **Android NDK:** Version 27+ with CMake 3.22+
- **Host Architecture:** 64-bit Linux, macOS, or Windows

### Building from Source

Clone the repository and build the debug variant:

```bash
git clone https://github.com/Mr-J-369/Fancy-Ai.git
cd Fancy-Ai

# Build GitHub flavor debug APK
./gradlew :app:assembleGithubDebug

# Build Google Play flavor debug APK
./gradlew :app:assemblePlayDebug
```

The compiled APKs will be located in `app/build/outputs/apk/`.

### Code Quality & Inspection

To run the automated static analysis checks (code size limits, Detekt complexity, and duplication checks):

```bash
./gradlew checkCodeSize complexityReport duplicationReport
```

---

## Models & Weights

Fancy AI downloads model weights on demand directly into the app's sandboxed storage. Starter models, converted graphs, and quantized weights are hosted on Hugging Face:

- **Hugging Face Hub:** [huggingface.co/Mr-J-369](https://huggingface.co/Mr-J-369)

You can also import your own `.safetensors`, GGUF, or MNN model packages directly from local device storage.

---

## Support & Donations

If you appreciate the open-source release of Fancy AI or find the on-device AI runtime engines helpful, you can support ongoing work and future projects:

- **Ko-fi:** [ko-fi.com/mrj369](https://ko-fi.com/mrj369)

---

## License

Fancy AI is licensed under the **Apache License, Version 2.0**. See the [LICENSE](LICENSE) file for the full license text.

Third-party dependencies and native libraries remain governed by their respective open-source licenses; see the [NOTICE](NOTICE) file for complete attributions.
