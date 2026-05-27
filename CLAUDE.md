# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build and install on connected device/emulator
./gradlew installDebug

# Build release APK (uses debug signing config)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

## Architecture Overview

Fancy AI is a native Android app that wraps a complete **WebView-based Virtual Phone OS**. The two layers communicate via a JavaScript bridge.

### Two-Layer Architecture

**Native Layer** (`app/src/main/java/com/mrj/fancyai/`):
- `MainActivity.java` — Single activity host. Sets up WebView, `WebViewAssetLoader`, TTS/STT, `WorkManager`, and the `AndroidBridge` JavaScript interface.
- `WebAppInterface` (inner class of `MainActivity`) — All `@JavascriptInterface` methods callable from JS as `window.AndroidBridge.*`. Handles disk I/O, notifications, file sharing, chunked backup streaming, and battery exemption.
- `FancyAiForegroundService.java` — Keeps the OS alive during long-running tasks (image generation, autonomous posting). Started/stopped via `AndroidBridge.setForegroundServiceActive(bool, text)`.
- `AutonomousWorker.java` — Legacy `WorkManager` background worker. Currently **disabled** (skips if app is in foreground). Background autonomy now lives in the JS layer.

**Frontend OS** (`app/src/main/assets/`):
- `index.html` — The hardware shell. Defines all global CSS (inline, no external stylesheets), the home screen, app launcher, modals, toasts, and lightbox. Exposes `window.OS` and `window.Autonomous` globals.
- `js/core/state.js` — `window.State` singleton. Manages all persistent data (characters, sessions, memories, social posts, settings). Persists to `state.json` on disk via `AndroidBridge`, with `localStorage` fallback. Includes rolling archival when state exceeds ~0.5MB.
- `js/core/api.js` — `window.API` singleton. Multi-provider LLM dispatcher (DeepInfra, OpenRouter, Local LLM). Handles macro resolution, social graph context injection, and Living Dossier evolution.
- `js/core/db.js` — `window.ImageDB` singleton. The sole authority for image storage. Saves base64 images to Android's `getFilesDir()/media/` and returns `db:ID` pointers. Maintains `media_registry.json` on disk.

**Mini-Apps** (`js/apps/`): Each is a single self-contained `.js` file loaded dynamically by `OS.launch()`. Keep files under 800 lines.

### Data & Media Flow

- **State** is read/written as `state.json` in `getFilesDir()` via `AndroidBridge.readFile`/`saveToFile`.
- **Images** are stored on disk as `img_<timestamp>.png` in `getFilesDir()/media/`. The JS layer uses `db:ID` references which `ImageDB` resolves to `https://media.fancy.ai/<filename>` URLs.
- **`https://media.fancy.ai/`** is a virtual domain intercepted by `WebViewAssetLoader`, mapping to `getFilesDir()/media/` on disk. Always prefer this URL format when displaying images in the DOM.
- **Chunked backups** stream through the bridge via `startBackup()` → `appendBackupChunk()` → `finishBackup()` in 300KB packets using JSZip.

### Network Security

Cleartext HTTP is only permitted for `127.0.0.1`, `localhost`, and `10.0.2.2` (emulator host). To allow a real LAN backend (e.g., a local Stable Diffusion Forge server), add its IP to `app/src/main/res/xml/network_security_config.xml`.

### Key Global Singletons

| Global | File | Role |
|---|---|---|
| `window.OS` | `index.html` | App lifecycle, navigation, toasts, modals |
| `window.Autonomous` | `index.html` | Auto-posting scheduler |
| `window.State` | `js/core/state.js` | All persistent data |
| `window.API` | `js/core/api.js` | LLM calls, macro resolution |
| `window.ImageDB` | `js/core/db.js` | Media storage authority |
| `window.AndroidBridge` | `MainActivity.java` | Native Java ↔ JS bridge |

## Development Conventions

1. **Single-file apps**: Each mini-app is one `.js` file. Do not split into modules.
2. **State safety**: Always call `State.save()` after mutating any data on `State`.
3. **Macros**: Use `{{user}}` and `{{char}}` placeholders in all prompts/personas. Resolve them via `API.applyMacros(text, charName, userName)` — it is case-insensitive.
4. **Styling**: Use CSS variables from the `:root` block in `index.html` (e.g., `--accent`, `--bg-card`, `--text-muted`). All CSS is inline in `index.html` — do not create external stylesheet files.
5. **Image generation**: Always route image generation through `ImagingApp.generate()` to respect the serialized queue (`_genQueue`) and the global lock `window.isSystemGenerating`.
6. **Media display**: Use `https://media.fancy.ai/<filename>` URLs (not base64 data URLs) for any image displayed in the DOM.
7. **Social Graph**: When constructing AI system prompts, inject the Social Graph block first as high-authority Ground Truth to prevent identity hallucinations.
8. **Back navigation**: Back presses are delegated to `OS.goBack()` in JS — do not use `WebView.goBack()` to avoid history stack desync.