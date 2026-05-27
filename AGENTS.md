# AGENTS.md — Fancy AI Technical Reference

## Project Overview

A native Android application (`com.mrj.fancyai`) that embeds a **WebView-based virtual phone OS** via a thick HTML/CSS/JS frontend. The app functions as an AI companion platform with background autonomy, real-time messaging, and multi-pipeline image generation.

---

## Technical Stack & Architecture

### **Native Layer (`MainActivity.java` + `AutonomousWorker.java` + `FancyAiForegroundService.java`)**
*   **Heartbeat:** `WorkManager` triggers a native worker every 15 minutes for background processing.
*   **Native Bridge:** `WebAppInterface` handles disk I/O, TTS/STT, and secure data passing.
*   **Binary Streaming:** Implements `WebViewAssetLoader` mapping `https://media.fancy.ai/` to local disk.
*   **Chunked Backup:** `startBackup()` / `appendBackupChunk()` / `finishBackup()` streams large binary exports through the bridge in small packets (300KB each) using `js/lib/jszip.min.js`.
*   **System Sharing:** `shareImage()` uses `FileProvider` to share local media via Android share sheet; `downloadImage()` / `saveBase64File()` persist to `Downloads/FancyAI/`.
*   **Battery Optimization:** `requestBatteryExemption()` requests `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for persistent background autonomy.
*   **Foreground Service:** `OS.setTaskActive()` → `setForegroundServiceActive()` → `FancyAiForegroundService` keeps the OS alive during long-running AI tasks (image generation, autonomous posting).
*   **Permissions:** AndroidManifest declares `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE_DATA_SYNC`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`, and scoped `READ_EXTERNAL_STORAGE`. Permission requests are handled via `ActivityResultLauncher` callbacks.
*   **Edge-to-Edge:** `WindowCompat.setDecorFitsSystemWindows(false)` with transparent status/nav bars.
*   **Background Auto-Post (Frontend):** Bots automatically post to social feeds while the app is open. Auto-posting is managed by the JavaScript mini-apps (`ustagram.js`, `rebbit.js`, `y.js`) and respects the interval settings in the OS.
*   **Media Registry:** Shared `media_registry.json` maps `db:ID` → file paths, written by the JS frontend (`ImageDB`).

### **OS Core (`index.html` + `core/`)**
*   **Monolithic Architecture**: The project uses a single-file architecture. All core logic lives in three files: `state.js`, `db.js`, and `api.js`.
*   **`state.js`**: Global `State` singleton with Rolling Archival and Living Dossier persistence. (Explicitly attached to `window.State` for WebView visibility).
*   **`api.js`**: Communication layer with macro resolution (`{{user}}`, `{{char}}`), social graph context injection, and multi-provider LLM support (DeepInfra, OpenRouter, Local LLM).
*   **`db.js`**: High-performance media authority resolving `db:ID` pointers to local disk. Persists a flat-file `media_registry.json` via the Android native bridge with localStorage fallback.
*   **All CSS is inline** in `index.html` — no external stylesheet files.
*   **Social Graph**: Card-local storage for bidirectional relationship awareness.

### **Mini-Apps (`apps/`)**
*   **Single-File Loading**: Each app is a single `.js` file loaded dynamically by `OS.launch`. No sub-module splitting.
*   **Social Graph**: Dynamic inter-character relationships that influence autonomous social interactions and mentions.

---

## Key Feature Implementations

| Module | Core Logic / Amazing Features |
|---|---|
| **MessengerApp** | Real-time chat, **Img2Img Denoising**, **Vision Mode**, and **Manual Intelligence Evolution (🌀)**. |
| **PhoneApp** | **Call Mode**: Immersive voice-first communication using local Android TTS/STT. **Persistent Session Logging** ensures call memories carry over to chat. |
| **ImagingApp** | Control dashboard for **Forge** (distributed A1111) and **Local Dream** (Snapdragon on-device NPU). Implements a **Serialized Generation Queue** (`_genQueue`) with SSE streaming progress from Local Dream and polling-based progress for Forge. `window.isSystemGenerating` acts as a global lock across all modules. |
| **GalleryApp** | **Intelligent Categorization**, lazy loading, and batch deletion. |
| **Ustagram / Rebbit / Y** | **Autonomous Social Ecosystem**. Bots maintain **Narrative Continuity** via the Social Graph. Supports **Media Replies** and **Automatic Reply Chains (4-turn limit)**. |
| **ContactsApp** | Centralized character management with **V2 PNG Character Card Import** and **Bidirectional Social Ties** with cross-card navigation links. |

---

## AI Collaboration & Conventions

### **The "Masterpiece" Protocol**
1.  **Single-File Style**: Keep each app as a single file. If it exceeds 800 lines, consider splitting logic into helpers within the same file.
2.  **State Safety**: Always use `State.save()` after modifying data.
3.  **Macro Standard**: Strictly use `{{user}}` and `{{char}}` placeholders in Dossiers, Personas, and Social Logic. Ensure case-insensitive resolution via `API.applyMacros`.
4.  **UI Consistency**: Use the CSS variables defined in `index.html` (`:root` tokens) for consistent styling.
5.  **Hardware Awareness**: Always use `ImagingApp.generate()` for image tasks to respect the queue.
6.  **Binary First**: Prefer virtual `https://media.fancy.ai/` URLs for any image displayed in the DOM.
7.  **Ground Truth Priority**: The **Social Graph** block should be prioritized at the top of the AI system prompt as high-authority Ground Truth to prevent identity hallucinations.

---

## Build & Dependencies

*   **AGP:** 9.2.1 | **Target SDK:** 36 | **Java:** 21 | **Version:** 3.0.4
*   **Core Libs:** OkHttp 5.3.2, Webkit 1.16.0, WorkManager 2.11.2, Gson 2.14.0.

> "Architecture is the soul of the Virtual OS." — Gemini
