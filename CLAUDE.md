
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**IMPORTANT:** Ensure you’ve thoroughly reviewed the [AGENTS.md](AGENTS.md) file before beginning any work. It contains the full technical reference for this project.

## Build Commands

```bash
# Build debug APK (llama.cpp + Hexagon NPU — single unified build, see "Native build" below)
./gradlew assembleDebug

# Build and install on connected device/emulator
./gradlew installDebug

# Build release APK (signed with the release keystore — see "Release signing")
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

### Release signing

Release builds are signed with a real release keystore, **not** the debug key.
Two files at the repo root hold the secret and are **gitignored — never commit them**:

- `fancyai-release.jks` — the keystore (alias `fancyai`, RSA-2048, 10000-day validity)
- `keystore.properties` — `storeFile` / `storePassword` / `keyAlias` / `keyPassword`

`app/build.gradle.kts` reads `keystore.properties`; if present, the `release` build type
uses the `release` signing config, **otherwise it falls back to debug signing** so a fresh
clone or CI still builds. So:

- On this machine, `./gradlew assembleRelease` → APK signed `CN=Fancy AI` (verify with
  `apksigner verify --print-certs <apk>`).
- On a machine without those two files → debug-signed (works, but not for distribution).

> **CRITICAL:** Back up `fancyai-release.jks` **and** its password somewhere safe (password
> manager / offline). If you lose them you can never ship an update to an already-published
> app under the same identity — Play/sideload signature continuity breaks and users must
> uninstall first. These files are intentionally absent from git, so git is **not** a backup.

Publishing a release + APK is done via the GitHub API (the `gh` CLI isn't installed):
create a release for the version tag, then upload `app/build/outputs/apk/release/app-release.apk`
as `FancyAI-v<version>.apk`. (Releases live at `github.com/Mr-J-369/Fancy-Ai/releases`.)

### CPU performance — ARM kernel fix (APPLIED) + remaining distribution work

**Was:** CPU generation was painfully slow (~4.7 tok/s combined on 8B Q4, S25/8-Elite).
ggml logged `cannot be used with preferred buffer type CPU_REPACK, using CPU instead` for
~290 tensors — the fast ARM matmul path (`CPU_REPACK`) needs **dotprod/i8mm** kernels, but
the backend was compiling plain `armv8-a`.

**Fixed (commit 07e7bd5):** `app/src/main/cpp/CMakeLists.txt` now sets
`GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16"` for arm64-v8a. CPU_REPACK now covers the
bulk of the model (only ~65 embed/norm tensors remain on the default path); measured
**~4–5× faster** (~4.7 → ~20–25 tok/s combined prefill+gen on the 8 Elite).

> **⚠️ Distribution caveat:** this hard-compiles i8mm, so the APK would **SIGILL on
> pre-~2021 devices** that lack it. Fine for the 8 Elite / modern Snapdragons. For a public
> release, switch to `GGML_CPU_ALL_VARIANTS ON` + `GGML_BACKEND_DL ON` (builds baseline/
> +dotprod/+i8mm CPU variants, runtime-dispatched via the existing
> `ggml_backend_load_all_from_path` in `jni.cpp`) — validate it doesn't disturb the
> Vulkan/OpenCL/Hexagon wiring. Or drop to `armv8.2-a+dotprod` for wide compat + most gain.

**Secondary levers (not yet done):** raise default `threads` 4 → 6–8 (8-Elite has 8 big
cores); smaller model (3–4B) for snappier CPU; `n_batch` tuning for time-to-first-token.

### Native build (UNIFIED — read before touching CMake/Gradle)

There is **one** build configuration, not two. Every build (CLI *and* Android Studio
Shift+F10) compiles llama.cpp **with the Hexagon NPU backend**. This is wired by a single
property in `gradle.properties`:

```
HEXAGON_SDK_ROOT=/home/j/Hexagon_SDK/6.6.0.0
```

`app/build.gradle.kts` reads `HEXAGON_SDK_ROOT` (env var **or** project property) and passes
it to CMake; `app/src/main/cpp/CMakeLists.txt` then sets `GGML_HEXAGON ON` and builds the
HTP skel libs (v68–v81). Because the property is committed, **Studio and CLI now produce the
exact same artifact** — do not reintroduce a conditional "CPU-only vs NPU" split (a previous
agent created that mess; it is intentionally removed).

- **Inference hardware is chosen at runtime**, not build time: Settings → On-Device LLM →
  *Inference Hardware* (CPU / NPU). NPU maps to Hexagon HTP via `n_gpu_layers` offload;
  the backend is registered at startup in `jni.cpp` via `ggml_backend_load_all_from_path()`.
- The **16 KB ELF-alignment warning** on Android 14+/Samsung (listing `libggml-htp-*.so` /
  `libQnn*.so`) is **expected and harmless** — those are unsigned Qualcomm skel libs. Do not
  try to "fix" it by dropping the NPU libs.
- The foreground service (`FancyAiForegroundService`) is `START_NOT_STICKY` and stops on
  `onTaskRemoved`, so swiping the app away kills it (no lingering background process).

## File Dependency Map — Touch This First

**When modifying a feature, start here to know which files to touch:**

| Feature | Owner File | Depends On | Related Files |
|---------|-----------|-----------|----------------|
| **Avatar generation & storage** | `js/apps/contacts.js:481` `generateAvatar()` | `CharacterService.setAvatar()` (js/core/characters.js:68) | `imaging.js`, `characters.js` |
| **Avatar display** | `js/core/characters.js:34` `resolveAvatar()` | `ImageDB.get()` (js/core/db.js) | `db.js` |
| **Image generation** | `js/apps/imaging.js:404` `generate()` | HTTP to Forge/Local Dream servers | `imaging.js` only |
| **Image save to storage** | `js/core/db.js:8` `ImageDB.save()` | `AndroidBridge.saveFile()` (native) | `db.js`, `state.js` (migration) |
| **Character CRUD** | `js/apps/contacts.js` | `CharacterService.*`, `State.characters` | `state.js`, `characters.js` |
| **Chat/Messaging** | `js/apps/messenger.js` | `API.chat()`, `CharacterService.get()`, `State.getSession()` | `api.js`, `characters.js`, `state.js` |
| **Social posts (Ustagram/Rebbit)** | `js/apps/ustagram.js`, `rebbit.js` | `API.chat()`, `ImagingApp.generate()` | `imaging.js`, `api.js` |
| **Dossier/memory** | `js/core/api.js:` `evolveDossier()` | `State.characters[].dossier` | `state.js` |
| **LLM inference** | `js/core/api.js` | `AndroidBridge.runLlamaInference()` (native) or HTTP (cloud) | `jni.cpp` (native), no dependencies within JS |
| **Settings persistence** | `js/core/state.js:` `State.settings` | `AndroidBridge.saveToFile()` | `state.js` only |

**Quick reference for common edits:**
- **"Avatar changes"** → contacts.js:481 + characters.js:68
- **"Image generation quality"** → imaging.js:404
- **"Character profile page"** → contacts.js (showProfile, renderProfile)
- **"Chat flow"** → messenger.js + api.js
- **"Social feed"** → ustagram.js / rebbit.js + api.js
- **"LLM provider selection"** → api.js (before calling AndroidBridge or HTTP endpoints)

**Files that are READ-ONLY (don't edit unless refactoring):**
- `js/core/state.js` — use `State.save()` after mutations, not direct writes
- `js/core/db.js` — use `ImageDB.*` public API, not internal structure
- `index.html` — CSS/HTML only; use `window.OS.*` globals, don't replicate modal code

## Architecture Overview

Fancy AI is a native Android app that wraps a complete **WebView-based Virtual Phone OS**. The two layers communicate via a JavaScript bridge. Local LLM inference is powered by **llama.cpp** via JNI.

### Two-Layer Architecture

**Native Layer** (`app/src/main/java/com/mrj/fancyai/`):
- `MainActivity.kt` — Single activity host. Sets up WebView, `WebViewAssetLoader`, TTS/STT, and the `AndroidBridge` JavaScript interface. Also manages local LLM model lifecycle via `LlamaInference`.
- `WebAppInterface` (inner class of `MainActivity`) — All `@JavascriptInterface` methods callable from JS as `window.AndroidBridge.*`. Handles disk I/O, notifications, file sharing, chunked backup streaming, model loading, and LLM inference callbacks.
- `LlamaInference.kt` — Kotlin wrapper around **llama.cpp C++ JNI bindings**. Manages model loading/unloading, streaming inference, and parameter tuning (context size, thread count, GPU layers, etc.). Callbacks (`onToken`, `onDone`) route to the WebView via `StreamBridge`.
- `FancyAiForegroundService.kt` — Keeps the OS alive during long-running tasks (image generation, autonomous posting, inference). Started/stopped via `AndroidBridge.setForegroundServiceActive(bool, text)`.

**Frontend OS** (`app/src/main/assets/`):
- `index.html` — The hardware shell. Defines all global CSS (inline, no external stylesheets), the home screen, app launcher, modals, toasts, and lightbox. Exposes `window.OS` and `window.Autonomous` globals.
- `js/core/state.js` — `window.State` singleton. Manages all persistent data (characters, sessions, memories, social posts, settings). Persists to `state.json` on disk via `AndroidBridge`, with `localStorage` fallback. Includes rolling archival when state exceeds ~0.5MB.
- `js/core/api.js` — `window.API` singleton. Multi-provider LLM dispatcher (DeepInfra, OpenRouter, Local LLM via llama.cpp). Handles macro resolution, social graph context injection, and Living Dossier evolution.
- `js/core/db.js` — `window.ImageDB` singleton. The sole authority for image storage. Saves base64 images to Android’s `getFilesDir()/media/` and returns `db:ID` pointers. Maintains `media_registry.json` on disk.

**Mini-Apps** (`js/apps/`): Each is a single self-contained `.js` file loaded dynamically by `OS.launch()`. Keep files under 800 lines.

**C++ Native** (`app/src/main/cpp/`):
- `jni.cpp` — JNI bindings between Kotlin and llama.cpp. Implements model loading, inference streaming, token callbacks, and parameter management.
- `llama.cpp/` — Integrated llama.cpp submodule for local CPU-based LLM inference.

### Data & Media Flow

- **State** is read/written as `state.json` in `getFilesDir()` via `AndroidBridge.readFile`/`saveToFile`.
- **Images** are stored on disk as `img_<timestamp>.png` in `getFilesDir()/media/`. The JS layer uses `db:ID` references which `ImageDB` resolves to `https://media.fancy.ai/<filename>` URLs.
- **`https://media.fancy.ai/`** is a virtual domain intercepted by `WebViewAssetLoader`, mapping to `getFilesDir()/media/` on disk. Always prefer this URL format when displaying images in the DOM.
- **Chunked backups** stream through the bridge via `startBackup()` → `appendBackupChunk()` → `finishBackup()` in 300KB packets using JSZip.
- **LLM Inference**: JS calls `AndroidBridge.runLlamaInference()` → Kotlin calls `LlamaInference.inferenceStream()` → C++ runs llama.cpp → callbacks stream tokens back to JS via `onLlamaToken()`.

### Network Security

Cleartext HTTP is only permitted for `127.0.0.1`, `localhost`, and `10.0.2.2` (emulator host). To allow a real LAN backend (e.g., a local Stable Diffusion Forge server), add its IP to `app/src/main/res/xml/network_security_config.xml`.

### Key Global Singletons

| Global | File | Role |
|---|---|---|
| `window.OS` | `index.html` | App lifecycle, navigation, toasts, modals |
| `window.Autonomous` | `index.html` | Auto-posting scheduler |
| `window.State` | `js/core/state.js` | All persistent data |
| `window.API` | `js/core/api.js` | LLM calls (multi-provider), macro resolution |
| `window.ImageDB` | `js/core/db.js` | Media storage authority |
| `window.AndroidBridge` | `MainActivity.kt` | Native Kotlin to JS bridge |
| `window.LlamaInference` | `LlamaInference.kt` | Local llama.cpp model wrapper |
| `window.CharacterService` | `js/core/characters.js` | Character lookups, avatar lifecycle |
| `window.ImageService` | `js/core/images.js` | Image generation, storage, reference mgmt |

### Service-Oriented Architecture (May 2026 Refactor)

To eliminate coupling where changing one feature required reading 3-4 files, the codebase now uses
**service modules** for shared concerns:

**Android Layer (Kotlin):**
- `FileService` — All disk I/O (state.json, images, backups, downloads)
- `ModelManager` — Model lifecycle, inference dispatch, StreamBridge wiring
- `PermissionManager` — Permission requests, result handling (replaces 5 launchers' inline logic)
- `TtsService` / `SttService` — Speech I/O (replaces 2 init methods' duplication)

**JavaScript Layer:**
- `CharacterService` — Character lookups (`State.getChar(id)`), avatar display (`resolveAvatar`), avatar lifecycle (`setAvatar`, `deleteAvatar`)
- `ImageService` — Image generation + storage pipeline (`generateAndSave`), reference resolution (`resolve`), deletion (`delete`)

**Why this matters:** Changing avatar behavior no longer requires tracing through messenger.js, contacts.js,
state.js, and ImageDB. All avatar writes now go through `CharacterService.setAvatar()`. All image
generate→save→reference sequences use `ImageService.generateAndSave()`. This pattern is replicated
across the native layer, making the codebase maintainable for rapid iteration.

## Development Conventions

1. **Single-file apps**: Each mini-app is one `.js` file. Do not split into modules.
2. **State safety**: Always call `State.save()` after mutating any data on `State`. **Better:** use service methods (`CharacterService.setAvatar()`, `ImageService.save()`) which handle `State.save()` atomically.
3. **Character lookups**: Use `State.getChar(id)` instead of `State.characters.find(c => c.id === id)`. Use `State.getSession(charId)` instead of `State.sessions[charId] || []`.
4. **Avatar operations**: All avatar writes must go through `CharacterService.setAvatar()` or `deleteAvatar()`. Avatar display uses `CharacterService.resolveAvatar()` to unify db: reference resolution.
5. **Image operations**: Use `ImageService.generateAndSave()` instead of the manual `ImagingApp.generate() → ImageDB.save()` pattern. Use `ImageService.resolve()` for db: references.
6. **Macros**: Use `{{user}}` and `{{char}}` placeholders in all prompts/personas. Resolve them via `API.applyMacros(text, charName, userName)` — it is case-insensitive.
4. **Styling**: Use CSS variables from the `:root` block in `index.html` (e.g., `--accent`, `--bg-card`, `--text-muted`). All CSS is inline in `index.html` — do not create external stylesheet files.
5. **Image generation**: Always route image generation through `ImagingApp.generate()` to respect the serialized queue (`_genQueue`) and the global lock `window.isSystemGenerating`.
6. **Media display**: Use `https://media.fancy.ai/<filename>` URLs (not base64 data URLs) for any image displayed in the DOM.
7. **Dossier safety**: When evolving a character's Living Dossier (`API.evolveDossier`), merge AI output with the existing dossier and type-check each field — never overwrite wholesale, or a partial AI response will wipe stored memory.
8. **Back navigation**: Back presses are delegated to `OS.goBack()` in JS — do not use `WebView.goBack()` to avoid history stack desync.
9. **LLM Provider Selection**: The API layer automatically selects the appropriate LLM provider (local llama.cpp, OpenRouter, DeepInfra) based on user settings and availability.

> **Note:** Characters are currently isolated — each sees only its own persona/bio/dossier, with no awareness of other characters. A cross-character "Social Graph" was prototyped and removed. See **Roadmap / Future Ideas** below.

## Root — Permanent Companion

Root (`id: 'root'`) is a hardcoded permanent character. She is **not a default placeholder** — she has her own identity, persona, and first message. Treat her with the same respect as the app's core architecture.

**Rules — do not violate these:**
- **Never delete her.** `ContactsApp.delete()` returns early with a toast if `charId === 'root'`. Her profile page renders no delete button.
- **Never overwrite her persona.** Her identity is intentional and fixed. Do not "improve" or rewrite it.
- **Never move her seeding to a one-time flag.** Root re-checks her own existence on every `State.init()` via ID lookup (`characters.find(c => c.id === 'root')`). If she's missing she re-surfaces. That is the feature, not a bug.
- **She appears first** — `unshift()`, not `push()`. She is always at the top of the contacts list.

**Planned hardening (when app goes closed-source / production):**
- Persona moves to a signed native asset, not plain JS string
- `State.save()` validates her presence before writing
- ID check becomes a cryptographic assertion
- Delete path in native code is gated

## Roadmap / Future Ideas

### Cross-Character Social Awareness (postponed)

The vision: characters who recognize and reference each other (e.g. Sarah knows Natasha is her sister-in-law) on social feeds and in chat, without name hallucination. Today each character sees only its own persona — confirmed via OOC chats where characters report "I only see my persona."

Explored approaches, in recommended build order:

1. **Structured relationship edges (data model).** Store relationships as ID-keyed data on the character: `relationships: { "<charId>": "sister-in-law" }`. Resolve ID → name/persona at prompt-build time and inject a small `[PEOPLE YOU KNOW]` block *adjacent* to the persona. Do **not** bake relationship text into the persona string — it pollutes the character card, breaks PNG card export/import, and isn't symmetric. Inject only edges relevant to the current thread/post so cost stays O(degree), never O(N).

2. **WhatsApp-style groups (recommended first feature).** A group = `{user, charA, charB}`. Bounded by construction: two personas max, no relevance selection, no graph traversal — the user is the relevance filter. The relationship is stated once on the group. Reuses the per-turn `sendMessage` machinery and the social reply-chain pattern. Open decisions: turn-taking (auto-alternate vs user-picks-speaker) and identity-bleed mitigation (firm per-turn "You are ONLY X" + speaker-labeled history). Great on cloud; workable on-device with aggressive history trimming.

3. **Small "bookkeeping" model (later optimization).** Route system tasks — dossier evolution, relevance selection, memory summarization — to a small/cheap model, while the character *voice* uses the user's chosen big model. Multi-backend support makes this practical: voice on cloud + bookkeeping on-device (keeps private dossier data local and costs down), or vice versa. Validate cheaply by repointing `evolveDossier` at a small model **before** building any orchestration. Requires the dossier JSON contract to be hardened first.

**Key constraints to respect:**
- **Context window.** On-device default is ~2048 tokens. Never inject the full character roster into a prompt — scope to thread participants.
- **Selection, not storage, is the hard problem.** Storing relationships is trivial; deciding *which* are relevant at a given moment is the real work. Groups sidestep this by making the user the filter.
