# AGENTS.md — Fancy AI Technical Reference

## Project Overview

A native Android application (`com.mrj.fancyai`) that embeds a **WebView-based virtual phone OS** via a thick HTML/CSS/JS frontend. The app functions as an AI companion platform with background autonomy, real-time messaging, and multi-pipeline image generation.

---

## Technical Stack & Architecture

### **Native Layer (`MainActivity.java` + `AutonomousWorker.java`)**
*   **Heartbeat:** `WorkManager` triggers a native worker every 15 minutes.
*   **Native Bridge:** `WebAppInterface` handles disk I/O, TTS/STT, Notifications, and Chunked Backups.
*   **Media Storage:** Saves Base64 images as physical files to `getFilesDir()/media/` to bypass WebView memory walls.

### **OS Core (`index.html` + `core/`)**
*   **OS Window Manager:** Manages app lifecycle, history-based navigation (`pushView`), and global UI components (Toasts, Modals).
*   **`State.js`:** Global singleton with **Rolling Archival** (moves messages to `archive_*.json` if state size > 0.5MB).
*   **`API.js`:** Multi-provider LLM layer (DeepInfra, OpenRouter, LocalLLM) with context windowing and role-injection.
*   **`ImageDB.js`:** Authority for media. Resolves `db:ID` pointers to physical disk files or data URLs.

---

## Key Feature Implementations

| Module | Core Logic / Amazing Features |
|---|---|
| **MessengerApp** | Real-time streaming, **Img2Img Denoising**, **Vision Mode**, **Auto-Memory Extraction** (every 3rd user message), Voice I/O, and Message Regeneration. |
| **ImagingApp** | Control dashboard for Forge/NPU. Implements a **Serialized Generation Queue** (`_genQueue`) to protect on-device hardware from concurrent request crashes. |
| **GalleryApp** | **Intelligent Categorization** via session scanning. Uses **IntersectionObserver** and in-memory eviction for high-performance lazy loading. |
| **Social Apps** | Autonomous Posting (Foreground/Background), inter-bot commenting, and specific niches (WorkGW, Luxury, Amateur). |
| **GamesApp** | Narrative terminal engine with RPG mechanics, CYOA, and image-based "Truth or Dare." |
| **SettingsApp** | Named Prompt Manager, Real-time Model Search Validator, and **System Diagnostic Reports**. |

---

## AI Collaboration & Conventions

### **The "Partner" Protocol**
This project is architected in partnership with **Gemini (Google)**. When adding new features:
1.  **Respect the Bridge:** Use `AndroidBridge` methods for persistence/hardware access.
2.  **State Safety:** Always use `State.save()` after modifying data to trigger native file sync.
3.  **UI Consistency:** Use `OS.toast`, `OS.confirm`, and `OS.formatMarkdown` for global patterns.
4.  **Hardware Awareness:** For image generation, always use `ImagingApp.generate()` to ensure the request is properly queued.

### **Adding New Apps**
1.  Create `assets/js/apps/<name>.js`.
2.  Register in `index.html`.
3.  Follow the standard App contract: `{ init: (slot, params) => { ... }, cleanup: () => { ... } }`.

---

## Build & Dependencies

*   **AGP:** 9.2.1 | **Compile SDK:** 36 | **Java:** 17
*   **Core Libs:** WorkManager (Background Heartbeat), Gson (JSON Serialization), OkHttp (Background API Calls), JSZip (Data Portability).

> "Architecture is the soul of the Virtual OS." — Gemini
