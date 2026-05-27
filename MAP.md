# Fancy AI - Project Map & Technical Index

## 🏗️ Core Architecture (The OS Brain)
Files responsible for the underlying operating system logic, native bridges, and global state management.

| File Path | Responsibility | Key Singletons |
|---|---|---|
| `MainActivity.java` | Native Android entry, bridge interfaces, file I/O, TTS/STT, and asset loading. | `WebAppInterface` |
| `AutonomousWorker.java` | Legacy background engine (Disabled). | `AutonomousWorker` |
| `index.html` | The "Hardware" shell. Handles global UI (Modals, Toasts, Lightbox), app loading, and inline CSS tokens. | `window.OS`, `window.Autonomous` |
| `js/core/state.js` | Global persistent data, character management, archival logic, and Living Dossier. | `window.State` |
| `js/core/db.js` | High-performance media authority. Manages physical disk storage and `media_registry.json`. | `window.ImageDB` |
| `js/core/api.js` | Multi-provider LLM communication dispatcher with macro resolution and social graph context injection. | `window.API` |

---

## 🚀 Recent Performance & Narrative Milestones
*   **Single-File Restoration**: Rolled back to the pre-modularization monolithic architecture for stability.
*   **All styling is inline** in `index.html` — no external CSS files.
*   **Narrative Laws**: Rebbit Reality Anchor and Forbidden Token logic to prevent LLM hallucinations.
*   **Social Graph**: Card-local storage for bidirectional character awareness.
*   **SillyTavern Macros**: `{{user}}` and `{{char}}` placeholders with case-insensitive resolution.
*   **Autonomous Social Evolution**: Automatic Reply Chains with a 4-turn limit.

## 📱 Mini-Apps (The Frontend)
Individual application modules that run inside the Virtual OS shell. Each app is a single self-contained file.

| App Module | File | Focus |
|---|---|---|
| **Messenger** | `js/apps/messenger.js` | Real-time chat, Vision, Img2Img, Manual Intelligence Evolution. |
| **Imaging** | `js/apps/imaging.js` | Pro Studio, Forge/NPU control, Generation Queue. |
| **Phone** | `js/apps/phone.js` | Immersive voice calls with persistent session logging. |
| **Gallery** | `js/apps/gallery.js` | Media browsing with intelligent categorization. |
| **Contacts** | `js/apps/contacts.js` | Character management with V2 PNG card import. |
| **Settings** | `js/apps/settings.js` | System configuration, backup/restore, diagnostics. |
| **Ustagram** | `js/apps/ustagram.js` | Elegant photo-sharing social feed. |
| **Rebbit** | `js/apps/rebbit.js` | NSFW board-based social feed with auto-posting. |
| **Y** | `js/apps/y.js` | Short status-update social feed. |
| **Games** | `js/apps/games.js` | Interactive AI adventures. |

---

**Agent Instruction**: Before modifying a module, read its entry in this map. Each app is a single file — keep them under 800 lines when possible.
