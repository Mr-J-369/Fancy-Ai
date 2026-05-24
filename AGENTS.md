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
- **Version**: `2.0.9` (versionCode: `1`)
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
        db.js                 # Media storage (Android Native Disk via Bridge, with localStorage fallback)
        state.js              # Global state (characters, settings, sessions, social feeds)
      apps/
        contacts.js           # Character manager (create/edit/delete identities)
        gallery.js            # Stored image gallery from IndexedDB
        games.js              # Games hub (Adventure, RPG, Hacking, etc.)
        imaging.js            # Image generation engine (Forge/Local Dream)
        messenger.js          # Chat interface per character
        rebbit.js             # NSFW amateur feed — bots post explicit photos (subreddit categories, ImageDB storage)
        settings.js           # LLM config, API keys, system prompts, user profile, backup/restore
        ustagram.js           # SFW lifestyle social feed — bots post photorealistic Instagram-style photos
        y.js                  # Text-only micro-blog feed — bots post statuses with threaded replies
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

Key `OS` methods:
- `launch(appName, params)` — switches apps, pushes history state, calls `cleanup()` on previous app if it implements it
- `goHome()` — returns to home screen, calls `cleanup()` on current app
- `goBack()` — pops in-app nav stack (`OS.navStack`) or goes home
- `pushView(restoreFn)` — pushes an in-app view for custom back navigation
- `formatMarkdown(text)` — renders `**bold**`, `*italic*`, `***bold italic***` to HTML

Apps can optionally implement a `cleanup()` method to stop timers/intervals when the OS switches away from them.

### Core Modules

| Module | File | Role |
|---|---|---|
| `State` | `core/state.js` | Global singleton — holds `characters[]`, `settings{}` (with `systemPrompts[]`/`activePromptId`), `sessions{}`, `userProfile{}`, `instagramPosts[]`, `redditPosts[]`, `xPosts[]`, `activeCharId`, `maxSessionMessages`. Persisted to Android native file `state.json` via `AndroidBridge.saveToFile`, with `localStorage` key `fancy_ai_state` as fallback. |
| `API` | `core/api.js` | LLM communication — supports DeepInfra (`api.deepinfra.com`), OpenRouter (`openrouter.ai`), and custom OpenAI-compatible endpoints. Streaming support via `ReadableStream`. Injects role directives based on context (`chat`, `social`, `game`). Uses `systemPrompts`/`activePromptId` for global guidance. Image generation triggered via `flux prompt:` in response text (not JSON tool calls). |
| `ImageDB` | `core/db.js` | Media storage using Android Native Disk via Bridge (`saveImageToDisk`/`loadImageFromDisk`), with `localStorage` key `fancy_ai_media_registry` as fallback. Registry persisted as `media_registry.json`. Supports `purgeOrphanedFiles()` for cleaning up stale disk files. |

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
|---|---|---|
| **MessengerApp** | Per-character chat with streaming LLM responses. Supports text formatting (`**bold**`, `*italic*`), image generation triggers, regenerate/delete/copy messages, img2img transforms, character selector popup, typing indicator. |
| **ImagingApp** | Image generation via two pipelines: **Forge** (Stable Diffusion WebUI API at `/sdapi/v1/txt2img`) and **Local Dream** (Snapdragon NPU on-device server via SSE). Shared lightbox with touch gestures (pinch-zoom, drag-to-dismiss). |
| **UstagramApp** | SFW lifestyle social feed — bots post photorealistic Instagram-style photos (selfie, food, outfit, nature, travel, etc.) with captions. Uses ImageDB for storage. |
| **RebbitApp** | NSFW amateur feed — bots post explicit photos themed to configurable subreddit categories (r/gonewild, r/realgirls, etc.). Per-character enable/disable toggle via `char.enableRebbit`. |
| **YApp** | Text-only micro-blog feed — bots post short statuses (hot takes, daily life, questions, etc.) with auto-generated threaded replies from other characters. |
| **ContactsApp** | CRUD for AI character identities. Grid layout with avatar generation. Each character has `id`, `name`, `handle`, `bio`, `persona`, `follower_count`, `enableAutoPost`, `avatar`, `enableRebbit`. |
| **SettingsApp** | LLM provider config (provider, URL, API key, model ID with live model list fetch + search/filter), user profile (name, bio), system prompts manager (multiple prompts, CRUD), backup/restore (ZIP via JSZip + chunked `AndroidBridge`). |
| **GalleryApp** | Grid view of all images stored in ImageDB with multi-select, select-all, and bulk delete support. |
| **GamesApp** | Game hub with Adventure (CYOA), Dice Duel RPG, Truth or Dare, Tactical Command, Two Truths & A Lie, Oracle, Would You Rather, and a Security Bypass hacking minigame. |

### Android ↔ JavaScript Bridge

`MainActivity.java` registers `WebAppInterface` as `AndroidBridge`:

| JS Method | Native Action |
|---|---|---|
| `AndroidBridge.exportBackup(dataUrl)` | Save a single-shot file to Downloads/FancyAI |
| `AndroidBridge.startBackup()` | Start chunked backup session → returns a backup ID |
| `AndroidBridge.appendBackupChunk(backupId, chunk)` | Append a base64 chunk (≤512KB) |
| `AndroidBridge.finishBackup(backupId, extension)` | Finalize and save assembled file |
| `AndroidBridge.shareImage(dataUrl)` | Share image via Android share intent (FileProvider) |
| `AndroidBridge.saveImageToDisk(base64Data)` | Save a single image to `getFilesDir()/media/` → returns filename |
| `AndroidBridge.loadImageFromDisk(fileName)` | Load image from `getFilesDir()/media/` → returns data URL |
| `AndroidBridge.saveToFile(fileName, content)` | Save text content to `getFilesDir()` (used for `state.json`, `media_registry.json`) |
| `AndroidBridge.readFile(fileName)` | Read text content from `getFilesDir()` |
| `AndroidBridge.deleteFile(fileName)` | Delete a file from `getFilesDir()` |
| `AndroidBridge.listMediaFiles()` | List all files in `getFilesDir()/media/` → returns JSON array |

File chooser (upload) uses `ActivityResultLauncher` with permission handling for Android 13+ (`READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`).

### Image Storage Strategy

- **Small state** → persisted to both Android native file `state.json` (via `AndroidBridge.saveToFile`) and `localStorage` key `fancy_ai_state` as fallback
- **Media registry** (id→file mapping) → `media_registry.json` via `AndroidBridge.saveToFile` + `localStorage` key `fancy_ai_media_registry`
- **Large image data** (Base64) → Android internal storage via `AndroidBridge.saveImageToDisk` to `getFilesDir()/media/`; referenced as `file:<filename>` in registry
- Image references in state use `db:<id>` format; resolved via `ImageDB.get(id)` which looks up the registry and loads from disk or returns inline `data:image` URLs
- `ImageDB.purgeOrphanedFiles()` scans disk via `AndroidBridge.listMediaFiles()` and deletes files not referenced in the registry — call after clearing feeds, deleting characters, or bulk gallery deletes

---

## Key Conventions

### Adding a new app
1. Create `app/src/main/assets/js/apps/<name>.js` with the standard pattern
2. Add a home screen button in `index.html`
3. Add the `<script>` tag after the core scripts in `index.html`

### Character data model (extends the fields below into `State.characters[]`)
```js
{
    id: 'c1',                    // unique string ID
    name: 'Companion',           // display name
    handle: '@companion',        // social handle (optional)
    persona: 'You are a...',     // LLM system prompt for this character
    bio: '',                     // profile bio
    follower_count: 0,           // social follower count
    virtual_gallery: [],         // unused
    enableAutoPost: true,        // whether bot auto-posts to social feed
    enableRebbit: true,          // (default true) whether bot posts on Rebbit
    avatar: null                 // `db:<id>` reference to generated avatar image
}
```
Default character (created if `State.characters` is empty on init): `{ id: 'c1', name: 'Companion', persona: 'You are a warm, thoughtful companion...', follower_count: 0, virtual_gallery: [] }`

### LLM provider configuration (`State.settings`)
```js
State.settings = {
    provider: 'deepinfra',         // 'deepinfra' | 'openrouter' | 'custom'
    model: 'meta-llama/Meta-Llama-3-70B-Instruct',
    key: '<api-key>',
    url: '',                       // custom base URL
    systemPrompts: [               // array of {id, name, content}
        { id: 'p1', name: 'Default', content: 'You are a unique individual...' }
    ],
    activePromptId: 'p1',          // currently active system prompt ID
    // ... imaging params are handled by ImagingApp (forge, localDreamUrl, imgWidth, etc.)
}
```

Note: `globalSystemPrompt`, `enableBotSocial`, and `useLocalDream` have been removed. System prompts are now managed as a named array with active selection via the SettingsApp UI.

### LLM API context types
The `API.sendMessage(charId, userText, onUpdate, includeHistory, context)` method accepts a `context` parameter that changes the role directive injected into the system message:

| Context | Use Case | Behavior |
|---|---|---|
| `'chat'` (default) | Messenger conversations | Personal chat role directive |
| `'social'` | Social feed posts/replies | Social media content creation directive |
| `'game'` | Game sessions | Game scenario directive, no small talk |

### Image generation trigger format
Image generation is triggered by `flux prompt:` in the LLM response text (not JSON tool calls). The API's system message includes an `[IMAGE GENERATION]` section instructing models to append:
```
flux prompt: [detailed visual description]
```
The messenger and social feed apps parse this from the response, extract the visual prompt, call `ImagingApp.generate()`, and insert the resulting image into the conversation or feed.

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
- **API key required**: Social feed posting (UstagramApp, RebbitApp, YApp) and LLM chat will fail silently if no API key is configured in Settings
- **State persistence**: State is dual-persisted to `AndroidBridge.saveToFile('state.json')` and `localStorage.fancy_ai_state`. If Android disk I/O fails, the localStorage fallback is used on next init.
- **Session message limit**: `State.maxSessionMessages` (default 100) caps per-character session length. On `QuotaExceededError`, `State.save()` emergency-trims all sessions to the last 20 messages.
- **Media registry corruption**: If `media_registry.json` (native) or `fancy_ai_media_registry` (localStorage) is lost, images stored as `file:*` on disk become orphaned. Run `ImageDB.purgeOrphanedFiles()` after recovery. The `ImageDB.get()` returns `null` for unresolvable `db:*` refs.
- **File upload permissions**: Android 14+ requires `READ_MEDIA_VISUAL_USER_SELECTED` — handled via `ActivityResultLauncher`. Falls back to `READ_MEDIA_IMAGES` (13+) or `READ_EXTERNAL_STORAGE` (≤12).
- **Backup size**: Base64 exports are chunked at 300KB per chunk (~400KB base64) to stay under the JavaScriptInterface string size limit
- **ImagingApp local storage settings**: ImagingApp persists its own settings (dimensions, steps, CFG, denoising) separately in `localStorage` key `ds_settings` (or `Store` if available)
