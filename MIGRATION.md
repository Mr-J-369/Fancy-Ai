# FancyAI Architecture Migration Plan

**Status**: In Progress - Phase A started May 30, 2026  
**Target**: Consolidate dual UI systems → single WebView-based Virtual Phone OS  
**Owner**: Claude Code (automated)

---

## Executive Summary

**Problem**: Codebase has two incompatible UI systems:
- **Compose UI** (~2,000 LOC): Incomplete, duplicate screens, not wired to native services
- **JavaScript Virtual OS** (~7,100 LOC): Complete, fully styled, ready to run, but not loaded

**Solution**: Activate the intended WebView-based architecture and remove dead Compose code.

**Scope**: 3 phases, 5-8 days total
- **Phase A**: Restore WebView (1-2 days) ← **CURRENT**
- **Phase B**: Wire native services (2-3 days)
- **Phase C**: Polish & hardening (2-3 days)

---

## Phase A: Restore WebView Foundation

**Goal**: Launch the Virtual Phone OS; all JS apps functional; native layer available but not yet integrated.

### A1: Update MainActivity to Load WebView

**File**: `app/src/main/java/com/mrj/fancyai/MainActivity.kt`

**Changes**:
```kotlin
// OLD: Compose-based
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.initialize(this)
        setContent { NavGraph() }
    }
}

// NEW: WebView-based
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.initialize(this)
        
        // Load WebView with Virtual Phone OS
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // ... full WebView config
            }
            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
    }
}
```

**Reference**: See A2 for full AndroidBridge implementation.

### A2: Implement AndroidBridge Interface

**File**: `app/src/main/java/com/mrj/fancyai/AndroidBridge.kt` (NEW)

**Scope**: 
- Expose FileService methods (readFile, saveToFile, file I/O)
- Expose ModelManager methods (load, unload, getStatus)
- Stream LlamaInference tokens back to WebView
- Handle TTS/STT calls
- Permission requests

**Template**:
```kotlin
class AndroidBridge(private val context: Context) {
    @JavascriptInterface
    fun readFile(path: String): String { /* delegate to FileService */ }
    
    @JavascriptInterface
    fun saveToFile(path: String, content: String) { /* delegate to FileService */ }
    
    @JavascriptInterface
    fun runLlamaInference(prompt: String, params: String, callbackId: Int) {
        // Trigger LlamaInference.inferenceStream()
        // Connect StreamBridge callbacks to JS via callbackId
    }
    
    // ... more methods
}
```

**Checklist**:
- [ ] Copy all `@JavascriptInterface` methods from the JS layer's expected API (see state.js docs)
- [ ] Wire FileService for I/O
- [ ] Wire ModelManager for model lifecycle
- [ ] Implement StreamBridge to route LlamaInference tokens
- [ ] Add permission request handlers
- [ ] Test bidirectional communication (JS → Kotlin → JS)

### A3: Wire LlamaInference → WebView Callbacks

**File**: `app/src/main/java/com/mrj/fancyai/LlamaInference.kt`

**Change**: Update the StreamBridge to call WebView JavaScript callbacks:

```kotlin
// In MainActivity.onCreate() or AndroidBridge init:
LlamaInference.streamBridge = object : LlamaInference.StreamBridge {
    override fun onToken(cbId: Int, token: String) {
        webView.evaluateJavascript(
            """window.onLlamaToken($cbId, '$token')"""
        ) { }
    }
    
    override fun onDone(cbId: Int) {
        webView.evaluateJavascript(
            """window.onLlamaDone($cbId)"""
        ) { }
    }
}
```

### A4: Verify WebView Asset Loading

**File**: `app/src/main/assets/index.html`

**Check**:
- [ ] HTML loads without 404 errors
- [ ] JS bundles (js/core/*.js, js/apps/*.js) are referenced correctly
- [ ] CSS inline styles apply (Material Design 3 tokens visible)
- [ ] Initial app (home screen or contacts) renders

**Debug**:
```bash
adb logcat | grep "WebView\|console\|index.html"
```

### A5: Remove Compose UI Code

**Files to delete** (or move to a `deprecated/` folder for now):
```
app/src/main/java/com/mrj/fancyai/ui/  (entire folder)
app/src/main/java/com/mrj/fancyai/data/repository/  (partly—keep as-is for now, will refactor in Phase B)
```

**Files to keep** (backend services):
```
app/src/main/java/com/mrj/fancyai/service/  (VoiceService, VisionService, AgentService)
app/src/main/java/com/mrj/fancyai/  (MainActivity, AndroidBridge, FileService, ModelManager, etc.)
```

**Update build.gradle.kts**:
- Remove Compose dependencies (if not needed by AndroidBridge)
- Keep androidx.webkit for WebView

### A6: Build & Test Phase A

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mrj.fancyai/.MainActivity
```

**Success Criteria**:
- ✅ App launches without crash
- ✅ WebView renders with home screen / contact list
- ✅ Navigation between apps works (tap contacts, messaging, gallery, etc.)
- ✅ Logcat shows no JS console errors
- ✅ Settings screen accessible

**Debugging**:
```bash
# Enable WebView debugging in Android Studio
chrome://inspect
```

---

## Phase B: Wire Native Services

**Goal**: JS apps can call native services; LlamaInference works; Voice/Vision/Agent services integrated.

### B1: Complete AndroidBridge Implementation

**Methods to expose** (from `js/core/api.js` and `index.html` expectations):

| Method | Source | Purpose |
|--------|--------|---------|
| `readFile(path)` | FileService | Load state.json, media registry |
| `saveToFile(path, content)` | FileService | Save state.json, user edits |
| `runLlamaInference(prompt, params, cbId)` | LlamaInference | Local LLM inference |
| `speak(text, voiceId)` | VoiceService | Text-to-speech |
| `listen()` | SttService | Speech-to-text |
| `loadModel(modelPath)` | ModelManager | Load .gguf model |
| `requestPermission(type)` | PermissionManager | Camera, microphone, etc. |
| `captureImage()` | VisionService | Take photo |
| `recognizeText(imageBase64)` | VisionService | OCR |

### B2: Implement StreamBridge Token Streaming

Connect LlamaInference callbacks to WebView:
- Routes tokens to `window.onLlamaToken(callbackId, token)`
- Routes completion to `window.onLlamaDone(callbackId)`
- Handles batch token delivery for performance

### B3: Test Native Service Integration

For each service:
```bash
# Example: Test LLM
adb logcat | grep "onLlamaToken"
# Watch tokens stream in real-time
```

**Acceptance**: Chat in messenger app works with local LLM.

### B4: Clean Up Data Layer

**Decision**: state.json is source of truth (keep it).
- Remove Room database usage from repositories
- Update repositories to read/write state.json via AndroidBridge
- Or: Deprecate repositories entirely if JS state.json handles persistence

---

## Phase C: Polish & Hardening

### C1: Remove Dead Code

Delete (or move to `deprecated/` folder):
- `app/src/main/java/com/mrj/fancyai/ui/` (all Compose screens)
- `app/src/main/java/com/mrj/fancyai/data/db/` (Room entities if not used)
- `app/src/main/java/com/mrj/fancyai/domain/` (if not used by services)

Keep:
- `service/` (native services)
- `MainActivity.kt`, `AndroidBridge.kt`, `FileService.kt`, `ModelManager.kt`, etc.
- `LlamaInference.kt`

### C2: Error Handling in AndroidBridge

Wrap all `@JavascriptInterface` methods with try-catch:
```kotlin
@JavascriptInterface
fun readFile(path: String): String = try {
    FileService.read(path)
} catch (e: Exception) {
    """{\"error\": \"${e.message}\"}"""
}
```

### C3: Performance Tuning

- Batch token callbacks (collect 5 tokens, send once)
- Cache model path to avoid reloads
- Optimize WebView memory (reduce history size)
- Profile LlamaInference token throughput

### C4: Documentation

Update CLAUDE.md:
- Architecture diagram: WebView ↔ AndroidBridge ↔ Services
- List all AndroidBridge methods
- Explain StreamBridge token flow
- Remove all references to Compose UI

### C5: End-to-End Testing

| Feature | Test | Expected |
|---------|------|----------|
| Contacts | Create/edit/delete | Works via JS |
| Chat | Send message, inference | Tokens stream, LLM responds |
| Voice | Speak/listen | TTS/STT functional |
| Images | Generate (if Forge configured) | Images appear in gallery |
| Settings | Change model, theme | Persists in state.json |

---

## If Session Ends During Phase A

### Immediately After You Restart:

1. **Check git status**:
   ```bash
   git status
   ```
   You'll see uncommitted changes to MainActivity and new files (AndroidBridge, updated WebView setup).

2. **Decide**:
   - **Continue Phase A**: Run `git add -A && git commit -m "feat: phase A - restore webview foundation"`
   - **Review first**: `git diff app/src/main/java/com/mrj/fancyai/MainActivity.kt` to see what changed

3. **Pick up where it left off**:
   - Look for `TODO` comments in code
   - Check build errors: `./gradlew assembleDebug`
   - Fix any issues, test incrementally

4. **Reference this document**:
   - Section A1-A6 tells you exactly what Phase A needs
   - Section B/C are ready for next phases

---

## Rollback Plan

If Phase A goes wrong and you want to restore the Compose UI:
```bash
git restore app/src/main/java/com/mrj/fancyai/MainActivity.kt
git checkout HEAD -- app/src/main/java/com/mrj/fancyai/ui/
git restore app/build.gradle.kts
```

Then rebuild:
```bash
./gradlew clean assembleDebug
```

---

## Key Files Overview

### Will Modify (Phase A):
- `MainActivity.kt` - Swap Compose → WebView
- `build.gradle.kts` - Update dependencies (Compose → WebView)

### Will Create (Phase A):
- `AndroidBridge.kt` - JS ↔ Kotlin interface

### Will Remove (Phase A):
- `ui/` folder (all Compose screens, eventually)
- Compose dependency bloat

### Will Keep (All Phases):
- `service/` (VoiceService, VisionService, AgentService)
- `LlamaInference.kt` (with updated StreamBridge)
- `FileService.kt`, `ModelManager.kt`, etc.
- `assets/js/**` (Virtual Phone OS, completely untouched)
- `assets/index.html` (the real UI)

---

## Success Metrics

**Phase A Complete When**:
- ✅ App builds without error
- ✅ App launches and shows Virtual Phone OS home screen
- ✅ Users can navigate between apps (contacts, messages, gallery)
- ✅ No JS console errors in Chrome DevTools
- ✅ Compose UI folder deleted or moved

**Phase B Complete When**:
- ✅ Chat app works with local LLM (messages → inference → responses)
- ✅ Voice app can speak and listen
- ✅ Settings persist to state.json
- ✅ All native services are callable from JS

**Phase C Complete When**:
- ✅ No dead code remains
- ✅ Error handling in AndroidBridge
- ✅ CLAUDE.md updated
- ✅ All features tested end-to-end
- ✅ Ready for release

---

## Timeline Estimate

| Phase | Duration | Key Deliverable |
|-------|----------|-----------------|
| A | 1-2 days | WebView launches; Virtual OS visible |
| B | 2-3 days | Native services integrated; chat works |
| C | 2-3 days | Polish, docs, testing, release-ready |
| **Total** | **5-8 days** | **Single, unified, production architecture** |

---

## Contact Points / Questions

If you're stuck:
1. Check the `TODO` comments in code
2. Review the relevant section in this document (A1-A6, B1-B4, C1-C5)
3. Run `git log --oneline` to see what was changed last
4. Rebuild and check logcat for errors

---

**Last Updated**: May 30, 2026, 02:45 UTC  
**Next Steps**: Start A1 - update MainActivity to load WebView
