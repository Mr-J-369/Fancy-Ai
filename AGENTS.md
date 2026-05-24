# AGENTS.md — Fancy AI

## Project Overview

A native Android application (`com.mrj.fancyai`) that embeds a **WebView-based virtual phone OS** via a thick HTML/CSS/JS frontend loaded from assets. The app functions as an AI companion platform with messaging, image generation, social feed, games, and character management — all running client-side inside the WebView.

---

## Build & Run

| Command | Purpose |
|---|---|
| `./gradlew assembleDebug` | Build debug APK |
| `./gradlew installDebug` | Install on connected device |
| `./gradlew test` | Run unit tests (currently none) |
| `./gradlew connectedAndroidTest` | Run instrumented tests (currently none) |

- **AGP**: `8.13.2` | **compileSdk/targetSdk**: `36` | **minSdk**: `24`
- **Java Version**: `17` (source & target compatibility)
- **Version**: `2.0.0` (versionCode: `1`)
- **Namespace**: `com.mrj.fancyai`

---

## Project Structure

```
app/src/main/
  java/com/mrj/fancyai/
    MainActivity.java         # Single Activity — hosts WebView with JS bridge
  assets/
    index.html                # Virtual OS shell — home screen + app launcher
    js/
      core/
        api.js                # LLM communication layer (DeepInfra/OpenRouter/Custom)
        db.js                 # IndexedDB wrapper for large media (ImageDB)
        state.js              # Global state (characters, settings, sessions, feed)
      apps/
        contacts.js           # Character manager (create/edit/delete identities)
        gallery.js            # Stored image gallery from IndexedDB
        games.js              # Games hub (Adventure, RPG, Hacking, etc.)
        imaging.js            # Image generation engine (Forge/Local Dream)
        messenger.js          # Chat interface per character
        settings.js           # LLM config, API keys, backup/restore
        social.js             # Autonomous bot social feed
      lib/
        jszip.min.js          # JSZip for backup export/import
    res/
      layout/activity_main.xml   # WebView layout
      xml/
        network_security_config.xml   # Cleartext for 127.0.0.1/localhost/10.0.2.2
        file_paths.xml              # FileProvider cache paths
      values/                 # strings.xml, colors.xml, themes.xml
      values-night/           # Dark theme
      drawable/               # Launcher icons
      mipmap-*/               # App launcher icons
  AndroidManifest.xml         # Permissions: INTERNET, storage, media access
build.gradle.kts              # Module-level build config
```

Root-level files `styles.css` and `script.js` are JetBrains build report viewer files — not part of the app.

---

## Architecture & Data Flow

### OS Shell (`index.html`)

The `OS` global object manages app lifecycle — `OS.launch('AppName', params)` hides the home screen, shows the app window, and calls `window[AppName].init(slot, params)`. The `goHome()` method resets the UI. A `popstate` listener handles back navigation (with a special case for the ImagingApp lightbox).

### Core Modules

| Module | File | Role |
|---|---|---|
| `State` | `core/state.js` | Global singleton — holds `characters[]`, `settings{}`, `sessions{}`, `feedPosts[]`, `activeCharId`. Persisted to `localStorage` key `fancy_ai_state`. |
| `API` | `core/api.js` | LLM communication — supports DeepInfra (`api.deepinfra.com`), OpenRouter (`openrouter.ai`), and custom OpenAI-compatible endpoints. Streaming support via `ReadableStream`. Injects a `SYSTEM_TOOL_SET` with `generate_image` action. |
| `ImageDB` | `core/db.js` | IndexedDB wrapper (`FancyAiMediaDB` / `gallery` store) for storing Base64 image data beyond localStorage's 5MB limit. |

### App Modules

All apps follow the same contract:
```js
const AppName = {
    container: null,
    init: function(container, params) { ... }
};
window.AppName = AppName;
```

| App | Key Responsibilities |
|---|---|
| **MessengerApp** | Per-character chat with streaming LLM responses. Supports text formatting (`**bold**`, `*italic*`), image generation triggers, regenerate/delete messages, cross-app memory sync to SocialApp. |
| **ImagingApp** | Image generation via two pipelines: **Forge** (Stable Diffusion WebUI API at `/sdapi/v1/txt2img`) and **Local Dream** (Snapdragon NPU on-device server via SSE). Shared lightbox with touch gestures. |
| **SocialApp** | Autonomous bot social feed. Bots generate posts and comments every 30 seconds via `setInterval`. Supports image posts, likes, threaded comments, and per-character timelines. |
| **ContactsApp** | CRUD for AI character identities. Each character has `id`, `name`, `handle`, `bio`, `persona`, `follower_count`, `enableAutoPost`. |
| **SettingsApp** | LLM provider config (provider, URL, API key, model ID with live model list fetch), global system prompt, bot social toggle, backup/restore (ZIP via JSZip + chunked `AndroidBridge`). |
| **GalleryApp** | Grid view of all images stored in IndexedDB with delete support. |
| **GamesApp** | Game hub with Adventure (CYOA), Dice Duel RPG, Truth or Dare, Tactical Command, Two Truths & A Lie, Oracle, Would You Rather, and a Security Bypass hacking minigame. |

### Android ↔ JavaScript Bridge

`MainActivity.java` registers `WebAppInterface` as `AndroidBridge`:

| JS Method | Native Action |
|---|---|
| `AndroidBridge.exportBackup(dataUrl)` | Save a single-shot file to Downloads/FancyAI |
| `AndroidBridge.startBackup()` | Start chunked backup session → returns a backup ID |
| `AndroidBridge.appendBackupChunk(backupId, chunk)` | Append a base64 chunk (≤512KB) |
| `AndroidBridge.finishBackup(backupId, extension)` | Finalize and save assembled file |
| `AndroidBridge.shareImage(dataUrl)` | Share image via Android share intent (FileProvider) |

File chooser (upload) uses `ActivityResultLauncher` with permission handling for Android 13+ (`READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`).

### Image Storage Strategy

- **Small state** (settings, character data, chat text, feed metadata) → `localStorage` key `fancy_ai_state`
- **Large image data** (Base64 strings) → `IndexedDB` via `ImageDB` (auto-migration from localStorage on init)
- Image references in state use `db:<id>` format; resolved via `ImageDB.get(id)` at render time

---

## Key Conventions

### Adding a new app
1. Create `app/src/main/assets/js/apps/<name>.js` with the standard pattern
2. Add a home screen button in `index.html`
3. Add the `<script>` tag after the core scripts in `index.html`

### Character data model
```js
{
    id: 'c1',                    // unique string ID
    name: 'Default Assistant',    // display name
    handle: '@default_ai',        // social handle
    persona: 'You are a ...',     // LLM system prompt for this character
    bio: 'Just a helpful AI.',    // profile bio
    follower_count: 0,            // social follower count
    virtual_gallery: [],          // unused
    enableAutoPost: true          // whether bot auto-posts to social feed
}
```

### LLM provider configuration
```js
State.settings = {
    provider: 'deepinfra',         // 'deepinfra' | 'openrouter' | 'custom'
    model: 'meta-llama/Meta-Llama-3-70B-Instruct',
    key: '<api-key>',
    url: '',                       // custom base URL
    globalSystemPrompt: '',        // prepended to every character's persona
    enableBotSocial: true,
    useLocalDream: true,           // image generation: true=Local Dream, false=Forge
    // ... imaging params (forge, localDreamUrl, imgWidth, etc.)
}
```

### Image generation tool call format
LLMs trigger image generation by emitting JSON (not actually executed — parsed from response text):
```json
{"action": "generate_image", "action_input": "highly detailed visual description"}
```

---

## Testing

- **Unit tests**: `app/src/test/java/` — directory exists but **empty** (no test files)
- **Instrumented tests**: `app/src/androidTest/java/` — directory exists but **empty** (no test files)
- **Test dependencies**: JUnit 4.13.2, AndroidX Test Ext JUnit 1.1.5, Espresso Core 3.5.1

---

## Dependencies (libs.versions.toml)

| Library | Version |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| AppCompat | 1.6.1 |
| Material Components | 1.10.0 |
| Activity | 1.8.0 |
| ConstraintLayout | 2.1.4 |
| JUnit | 4.13.2 |
| AndroidX Test JUnit | 1.1.5 |
| Espresso Core | 3.5.1 |

---

## Troubleshooting & Debugging

- **WebView debugging**: Enable Chrome remote debugging via `chrome://inspect` on a connected device — all JS console logs and network calls are visible
- **API key required**: SocialApp bot posting and LLM chat will fail silently if no API key is configured in Settings
- **LocalStorage limit**: If state fails to save, `State.save()` has emergency recovery that keeps only the 5 most recent feed posts
- **ImageDB corruption**: Gallery or chat images may fail to load if IndexedDB is cleared; re-generating images will repopulate
- **File upload permissions**: Android 14+ requires `READ_MEDIA_VISUAL_USER_SELECTED` — handled via `ActivityResultLauncher`
- **Backup size**: Base64 exports are chunked at 300KB per chunk (~400KB base64) to stay under the JavaScriptInterface string size limit
