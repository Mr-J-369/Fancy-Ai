package com.mrj.fancyai

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var myWebView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var fileService: FileService
    private lateinit var modelManager: ModelManager
    private lateinit var permissionManager: PermissionManager

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIntent: Intent? = null

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    private val ggufPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("FancyAI", "gguf picker result: code=${result.resultCode}")
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: run {
            Log.w("FancyAI", "GGUF picker uri is null")
            return@registerForActivityResult
        }
        val fileName = getFileName(uri) ?: "model_${System.currentTimeMillis()}.gguf"

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w("FancyAI", "takePersistableUriPermission failed: ${e.message}")
        }

        Thread {
            try {
                sendToJs("OS.toast('Copying $fileName, please wait...', 'info')")
                val svcIntent = Intent(this, FancyAiForegroundService::class.java).apply {
                    putExtra("content", "Loading AI model: $fileName")
                }
                ContextCompat.startForegroundService(this, svcIntent)
                val ok = try {
                    modelManager.copyAndLoadFromUri(uri, fileName)
                } finally {
                    stopService(svcIntent)
                }
                runOnUiThread {
                    myWebView.evaluateJavascript(
                        if (ok) "OS.toast('Model loaded!', 'success')" else "OS.toast('Copy done but load failed', 'error')",
                        null
                    )
                    if (ok) {
                        myWebView.evaluateJavascript("if(window.SettingsApp) SettingsApp.autoDetectTemplate('$fileName')", null)
                    }
                    myWebView.evaluateJavascript("if(window.SettingsApp) SettingsApp.updateLlamaStatus()", null)
                }
            } catch (e: Exception) {
                Log.e("FancyAI", "Model picker failed: ${e.message}", e)
                val safeMsg = (e.message ?: "unknown error").replace("'", "")
                runOnUiThread { myWebView.evaluateJavascript("OS.toast('Error: $safeMsg', 'error')", null) }
            }
        }.start()
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> permissionManager.onFileChooserResult(result.resultCode, result.data) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> permissionManager.onStoragePermResult(result) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionManager.onNotificationPermResult(granted) }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionManager.onAudioPermResult(granted) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContentView(R.layout.activity_main)
        myWebView = findViewById(R.id.webview)
        fileService = FileService(applicationContext)
        modelManager = ModelManager(applicationContext, ::sendToJs)
        permissionManager = PermissionManager(
            context = this,
            launchFileChooser = { fileChooserLauncher.launch(it) },
            launchStoragePerms = { permissionLauncher.launch(it) },
            onAudioGranted = { speechRecognizer?.startListening(speechRecognizerIntent) },
            sendToJs = ::sendToJs
        )

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("media.fancy.ai")
            .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, "media")))
            .build()

        val settings = myWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        myWebView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        myWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if ("media.fancy.ai" == uri.host && "1" == uri.getQueryParameter("thumb")) {
                    fileService.serveThumbnail(uri)?.let { return it }
                }
                return assetLoader.shouldInterceptRequest(uri)
            }
        }

        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                permissionManager.onShowFileChooser(filePathCallback, fileChooserParams.createIntent())
                return true
            }
        }

        myWebView.setDownloadListener { url, _, _, mimetype, _ ->
            val ok = fileService.saveBase64File(url, mimetype)
            if (ok) runOnUiThread { Toast.makeText(this, "Saved to Downloads/FancyAI", Toast.LENGTH_SHORT).show() }
            else runOnUiThread { Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show() }
        }

        myWebView.loadUrl("file:///android_asset/index.html")

        initTTS()
        initSTT()
        createNotificationChannel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                myWebView.evaluateJavascript(
                    "if(typeof OS!=='undefined'&&typeof OS.goBack==='function'){OS.goBack();}else{try{__osGoBackFallback();}catch(e){}}",
                    null
                )
            }
        })
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.US)
        }
    }

    private fun initSTT() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = sendToJs("OS._onSpeechEvent('ready')")
            override fun onBeginningOfSpeech() = sendToJs("OS._onSpeechEvent('beginning')")
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = sendToJs("OS._onSpeechEvent('end')")
            override fun onError(error: Int) = sendToJs("OS._onSpeechEvent('error', $error)")
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0].replace("'", "\\'")
                    sendToJs("OS._onSpeechResult('$text')")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "fancy_ai_notifications",
                "Fancy AI Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications from your AI companions" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun sendToJs(script: String) {
        runOnUiThread { myWebView.evaluateJavascript(script, null) }
    }

    override fun onDestroy() {
        tts?.run { stop(); shutdown() }
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    inner class WebAppInterface {

        @Suppress("unused")
        @JavascriptInterface
        fun speak(text: String) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "FancyAI_TTS")
        }

        @Suppress("unused")
        @JavascriptInterface
        fun stopSpeaking() {
            tts?.stop()
        }

        @Suppress("unused")
        @JavascriptInterface
        fun startListening() {
            runOnUiThread {
                if (permissionManager.isAudioPermissionGranted()) {
                    speechRecognizer?.startListening(speechRecognizerIntent)
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun stopListening() {
            runOnUiThread { speechRecognizer?.stopListening() }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun showNotification(title: String, message: String, charId: String) {
            val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(this@MainActivity, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val builder = NotificationCompat.Builder(this@MainActivity, "fancy_ai_notifications")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
            val nm = NotificationManagerCompat.from(this@MainActivity)
            if (permissionManager.isNotificationPermissionGranted()) {
                nm.notify(System.currentTimeMillis().toInt(), builder.build())
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun exportBackup(dataUrl: String) {
            val ok = fileService.saveBase64File(dataUrl, null)
            if (!ok) Log.e("FancyAI", "Export backup failed")
        }

        @Suppress("unused")
        @JavascriptInterface
        fun startBackup(): String = fileService.startBackup()

        @Suppress("unused")
        @JavascriptInterface
        fun appendBackupChunk(backupId: String, base64Chunk: String) {
            fileService.appendBackupChunk(backupId, base64Chunk)
        }

        @Suppress("unused")
        @JavascriptInterface
        fun finishBackup(backupId: String, extension: String?) {
            val ok = fileService.finishBackup(backupId, extension)
            if (!ok) Log.e("FancyAI", "Finish backup failed")
        }

        @Suppress("unused")
        @JavascriptInterface
        fun saveImageToDisk(base64Data: String?): String? = fileService.saveImageToDisk(base64Data)

        @Suppress("unused")
        @JavascriptInterface
        fun loadImageFromDisk(fileName: String): String? = fileService.loadImageFromDisk(fileName)

        @Suppress("unused")
        @JavascriptInterface
        fun saveToFile(fileName: String, content: String) {
            fileService.saveTextFile(fileName, content)
        }

        @Suppress("unused")
        @JavascriptInterface
        fun readFile(fileName: String): String? = fileService.readTextFile(fileName)

        @Suppress("unused")
        @JavascriptInterface
        fun setSecureString(key: String, value: String) {
            try {
                val masterKey = MasterKey.Builder(this@MainActivity)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    this@MainActivity,
                    "fancy_ai_secure",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                prefs.edit().putString(key, value).apply()
            } catch (e: Exception) {
                Log.e("FancyAI", "Failed to set secure string", e)
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun getSecureString(key: String): String? {
            return try {
                val masterKey = MasterKey.Builder(this@MainActivity)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    this@MainActivity,
                    "fancy_ai_secure",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                prefs.getString(key, null)
            } catch (e: Exception) {
                Log.e("FancyAI", "Failed to get secure string", e)
                null
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun shareImage(dataUrl: String?) {
            if (dataUrl == null) return
            try {
                val file: File? = when {
                    dataUrl.startsWith("https://media.fancy.ai/") ->
                        File(filesDir, "media/${dataUrl.removePrefix("https://media.fancy.ai/")}")
                    dataUrl.startsWith("data:image/") -> {
                        val decoded = Base64.decode(dataUrl.substring(dataUrl.indexOf(",") + 1), Base64.DEFAULT)
                        val cacheDir = File(cacheDir, "shared_images").apply { if (!exists()) mkdirs() }
                        File(cacheDir, "shared_image_${System.currentTimeMillis()}.png").also {
                            FileOutputStream(it).use { s -> s.write(decoded) }
                        }
                    }
                    else -> null
                }
                if (file != null && file.exists()) {
                    val contentUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        setDataAndType(contentUri, contentResolver.getType(contentUri))
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        type = "image/png"
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Image"))
                }
            } catch (e: Exception) { Log.e("FancyAI", "Share failed", e) }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun downloadImage(dataUrl: String?) {
            if (dataUrl == null) return
            try {
                when {
                    dataUrl.startsWith("https://media.fancy.ai/") -> {
                        val file = File(filesDir, "media/${dataUrl.removePrefix("https://media.fancy.ai/")}")
                        if (file.exists()) {
                            val ok = fileService.saveRawData(file.readBytes(), "FancyAI_${System.currentTimeMillis()}.png")
                            if (ok) runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads/FancyAI", Toast.LENGTH_SHORT).show() }
                        }
                    }
                    dataUrl.startsWith("data:image/") -> {
                        val ok = fileService.saveBase64File(dataUrl, null)
                        if (ok) runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads/FancyAI", Toast.LENGTH_SHORT).show() }
                    }
                }
            } catch (e: Exception) { Log.e("FancyAI", "Download failed", e) }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun deleteFile(fileName: String) {
            fileService.deleteFile(fileName)
        }

        @Suppress("unused")
        @JavascriptInterface
        fun listMediaFiles(): String = fileService.listMediaFiles()

        @Suppress("unused")
        @JavascriptInterface
        fun requestExit() {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Exit Fancy AI")
                    .setMessage("Are you sure you want to exit?")
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun setForegroundServiceActive(active: Boolean, text: String?) {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, FancyAiForegroundService::class.java)
                    if (active) {
                        intent.putExtra("content", text ?: "Processing AI tasks...")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                        else startService(intent)
                    } else {
                        intent.action = "STOP_SERVICE"
                        startService(intent)
                    }
                } catch (e: Exception) {
                    Log.w("FancyAI", "Could not manage foreground service: ${e.message}")
                }
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun llamaLoadModel(modelPath: String): Boolean = modelManager.loadModel(modelPath)

        @Suppress("unused")
        @JavascriptInterface
        fun llamaLoadModelByName(fileName: String): Boolean = modelManager.loadModelByName(fileName)

        @Suppress("unused")
        @JavascriptInterface
        fun llamaListModels(): String = modelManager.listModels()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaDeleteModel(fileName: String): Boolean = modelManager.deleteModel(fileName)

        @Suppress("unused")
        @JavascriptInterface
        fun llamaOpenFilePicker() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread { ggufPickerLauncher.launch(intent) }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun llamaCopyAndLoad(contentUriStr: String): Boolean {
            val uri = Uri.parse(contentUriStr)
            return modelManager.copyAndLoadFromUri(uri, "model.gguf")
        }

        @Suppress("unused")
        @JavascriptInterface
        fun llamaUnloadModel() = modelManager.unloadModel()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaIsModelLoaded(): Boolean = modelManager.isModelLoaded()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaGetLoadedModelPath(): String? = modelManager.getLoadedModelPath()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaGetChatTemplate(): String = modelManager.getChatTemplate()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaInferenceAsync(prompt: String, maxTokens: Int, callbackId: Int,
                                temperature: Float, topK: Int, topP: Float) {
            modelManager.inferenceAsync(prompt, maxTokens, callbackId, temperature, topK, topP)
        }

        @Suppress("unused")
        @JavascriptInterface
        fun llamaCancelInference() = modelManager.cancelInference()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaUnloadModelAsync() = modelManager.unloadModelAsync()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaSetEngineParams(nCtx: Int, nThreads: Int, flashAttn: Boolean,
                                 useMmap: Boolean, useMlock: Boolean, gpuLayers: Int, backend: Int, kvType: Int) {
            modelManager.setEngineParams(nCtx, nThreads, flashAttn, useMmap, useMlock, gpuLayers, backend, kvType)
        }

        // Fully restart the process. Switching inference hardware (CPU/NPU/GPU)
        // re-pins the offload device at model load and is safest from a clean
        // process, so the UI calls this after the user confirms a backend switch.
        @Suppress("unused")
        @JavascriptInterface
        fun restartApp() {
            runOnUiThread {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                Runtime.getRuntime().exit(0)
            }
        }
    }
}
