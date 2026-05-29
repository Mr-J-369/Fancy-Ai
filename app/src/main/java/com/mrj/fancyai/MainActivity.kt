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
    private var mUploadMessage: ValueCallback<Array<Uri>>? = null
    private var mPendingIntent: Intent? = null
    private lateinit var assetLoader: WebViewAssetLoader

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIntent: Intent? = null

    private val backupChunks = ConcurrentHashMap<String, ByteArrayOutputStream>()

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
                val modelsDir = File(filesDir, "models").apply { mkdirs() }
                val destFile = File(modelsDir, fileName)
                runOnUiThread { myWebView.evaluateJavascript("OS.toast('Copying $fileName, please wait...', 'info')", null) }
                (contentResolver.openInputStream(uri)
                    ?: throw Exception("InputStream is null for uri: $uri")).use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(131072)
                        var n: Int
                        var total = 0L
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            total += n
                            if (total % (256L * 1024 * 1024) == 0L)
                                Log.d("FancyAI", "Copied ${total / (1024 * 1024)}MB")
                        }
                        Log.d("FancyAI", "GGUF copied: ${total / (1024 * 1024)}MB → $destFile")
                    }
                }
                runOnUiThread { myWebView.evaluateJavascript("OS.toast('Loading model...', 'info')", null) }
                val svcIntent = Intent(this, FancyAiForegroundService::class.java).apply {
                    putExtra("content", "Loading AI model: $fileName")
                }
                ContextCompat.startForegroundService(this, svcIntent)
                var ok = false
                try {
                    ok = LlamaInference.loadModel(destFile.absolutePath)
                    if (ok) {
                        getSharedPreferences("fancy_ai", MODE_PRIVATE).edit()
                            .putString("last_model_path", destFile.absolutePath).apply()
                    }
                } catch (t: Throwable) {
                    Log.e("FancyAI", "Critical error in LlamaInference.loadModel: ${t.message}", t)
                } finally {
                    stopService(svcIntent)
                }
                val msg = if (ok) "OS.toast('Model loaded!', 'success')" else "OS.toast('Copy done but load failed', 'error')"
                runOnUiThread {
                    myWebView.evaluateJavascript(msg, null)
                    if (ok) {
                        myWebView.evaluateJavascript("if(window.SettingsApp) SettingsApp.autoDetectTemplate('$fileName')", null)
                    }
                    myWebView.evaluateJavascript("if(window.SettingsApp) SettingsApp.updateLlamaStatus()", null)
                }
            } catch (e: Exception) {
                Log.e("FancyAI", "GGUF copy failed: ${e.message}", e)
                val safeMsg = (e.message ?: "unknown error").replace("'", "")
                runOnUiThread { myWebView.evaluateJavascript("OS.toast('Error: $safeMsg', 'error')", null) }
            }
        }.start()
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (mUploadMessage == null) return@registerForActivityResult
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK && result.data != null) {
            val d = result.data!!
            results = when {
                d.dataString != null -> arrayOf(Uri.parse(d.dataString))
                d.clipData != null -> Array(d.clipData!!.itemCount) { d.clipData!!.getItemAt(it).uri }
                else -> null
            }
        }
        mUploadMessage!!.onReceiveValue(results)
        mUploadMessage = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        val pendingIntent = mPendingIntent
        if (allGranted && pendingIntent != null) {
            fileChooserLauncher.launch(pendingIntent)
        } else {
            mUploadMessage?.let { it.onReceiveValue(null); mUploadMessage = null }
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
        mPendingIntent = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            speechRecognizer?.startListening(speechRecognizerIntent)
        } else {
            Toast.makeText(this, "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
            sendToJs("OS._onSpeechEvent('error', -1)")
        }
    }

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

        // Wire C++ streaming callbacks → WebView JS
        LlamaInference.streamBridge = object : LlamaInference.StreamBridge {
            override fun onToken(cbId: Int, token: String) {
                val escaped = token
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                sendToJs("(function(){var f=window._llamaToken&&window._llamaToken[$cbId];if(f)f(\"$escaped\");})()")
            }
            override fun onDone(cbId: Int) {
                sendToJs("(function(){var f=window._llamaDone&&window._llamaDone[$cbId];if(f){delete window._llamaToken[$cbId];delete window._llamaDone[$cbId];f();}})()")
            }
        }

        myWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if ("media.fancy.ai" == uri.host && "1" == uri.getQueryParameter("thumb")) {
                    serveThumbnail(uri)?.let { return it }
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
                mUploadMessage?.onReceiveValue(null)
                mUploadMessage = filePathCallback
                checkPermissionsAndLaunch(fileChooserParams.createIntent())
                return true
            }
        }

        myWebView.setDownloadListener { url, _, _, mimetype, _ -> saveBase64File(url, mimetype) }

        // Pre-initialize LlamaInference to avoid thread deadlocks during native library load
        Log.d("FancyAI", "Pre-initializing LlamaInference...")
        LlamaInference.isModelLoaded()

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

    private fun serveThumbnail(uri: Uri): WebResourceResponse? {
        return try {
            val fileName = uri.lastPathSegment ?: return null
            val file = File(filesDir, "media/$fileName")
            if (!file.exists()) return null

            val target = 256
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            if (bounds.outHeight > target || bounds.outWidth > target) {
                val halfH = bounds.outHeight / 2
                val halfW = bounds.outWidth / 2
                while ((halfH / sample) >= target && (halfW / sample) >= target) sample *= 2
            }

            val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            bmp.recycle()

            WebResourceResponse(
                "image/jpeg", null, 200, "OK",
                mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(baos.toByteArray())
            )
        } catch (e: Exception) {
            Log.w("FancyAI", "Thumbnail decode failed: ${e.message}")
            null
        }
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

    private fun checkPermissionsAndLaunch(intent: Intent) {
        val perms = buildList {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                else ->
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val allGranted = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (allGranted) fileChooserLauncher.launch(intent)
        else { mPendingIntent = intent; permissionLauncher.launch(perms.toTypedArray()) }
    }

    private fun saveBase64File(dataUrl: String?, mimeType: String?) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return
        try {
            var mt = mimeType
            if (mt.isNullOrEmpty() || mt.contains("octet-stream")) {
                val start = dataUrl.indexOf(":") + 1
                val end = dataUrl.indexOf(";")
                if (start > 0 && end > start) mt = dataUrl.substring(start, end)
            }
            val base64Content = dataUrl.substring(dataUrl.indexOf(",") + 1)
            val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
            val extension = when {
                mt?.contains("png") == true -> ".png"
                mt?.contains("jpeg") == true || mt?.contains("jpg") == true -> ".jpg"
                mt?.contains("json") == true -> ".json"
                mt?.contains("zip") == true -> ".zip"
                else -> ".bin"
            }
            saveRawData(decodedBytes, "FancyAI_${System.currentTimeMillis()}$extension")
        } catch (_: Exception) {}
    }

    private fun saveRawData(bytes: ByteArray, fileName: String) {
        try {
            val downloadFolder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FancyAI"
            )
            if (!downloadFolder.exists() && !downloadFolder.mkdirs()) return
            FileOutputStream(File(downloadFolder, fileName)).use { it.write(bytes) }
            runOnUiThread { Toast.makeText(this, "Saved to Downloads/FancyAI", Toast.LENGTH_SHORT).show() }
        } catch (_: Exception) {
            runOnUiThread { Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show() }
        }
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
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    speechRecognizer?.startListening(speechRecognizerIntent)
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
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || Build.VERSION.SDK_INT < 33
            ) {
                nm.notify(System.currentTimeMillis().toInt(), builder.build())
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun exportBackup(dataUrl: String) = saveBase64File(dataUrl, null)

        @Suppress("unused")
        @JavascriptInterface
        fun startBackup(): String {
            val id = "bk_${System.currentTimeMillis()}"
            backupChunks[id] = ByteArrayOutputStream()
            return id
        }

        @Suppress("unused")
        @JavascriptInterface
        fun appendBackupChunk(backupId: String, base64Chunk: String) {
            val baos = backupChunks[backupId] ?: return
            try {
                val decoded = Base64.decode(base64Chunk, Base64.DEFAULT)
                synchronized(baos) { baos.write(decoded) }
            } catch (_: Exception) {}
        }

        @Suppress("unused")
        @JavascriptInterface
        fun finishBackup(backupId: String, extension: String?) {
            val baos = backupChunks.remove(backupId) ?: return
            try {
                val allBytes = synchronized(baos) { baos.toByteArray() }
                saveRawData(allBytes, "Backup_${System.currentTimeMillis()}${if (!extension.isNullOrEmpty()) extension else ".zip"}")
            } catch (e: Exception) {
                Log.e("FancyAI", "Finish backup failed", e)
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun saveImageToDisk(base64Data: String?): String? {
            if (base64Data == null || !base64Data.contains(",")) return null
            return try {
                val pureBase64 = base64Data.substring(base64Data.indexOf(",") + 1)
                val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
                val dir = File(filesDir, "media")
                if (!dir.exists() && !dir.mkdirs()) return null
                val fileName = "img_${System.currentTimeMillis()}.png"
                FileOutputStream(File(dir, fileName)).use { it.write(decodedBytes) }
                fileName
            } catch (e: Exception) {
                Log.e("FancyAI", "Disk save failed", e)
                null
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun loadImageFromDisk(fileName: String): String? {
            return try {
                val file = File(filesDir, "media/$fileName")
                if (!file.exists()) null
                else "data:image/png;base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}"
            } catch (_: Exception) { null }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun saveToFile(fileName: String, content: String) {
            try {
                File(filesDir, fileName).writeText(content, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e("FancyAI", "Save to file failed", e)
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun readFile(fileName: String): String? {
            return try {
                val file = File(filesDir, fileName)
                if (!file.exists()) null else file.readText(Charsets.UTF_8)
            } catch (_: Exception) { null }
        }

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
                        if (file.exists()) saveRawData(file.readBytes(), "FancyAI_${System.currentTimeMillis()}.png")
                    }
                    dataUrl.startsWith("data:image/") -> saveBase64File(dataUrl, null)
                }
            } catch (e: Exception) { Log.e("FancyAI", "Download failed", e) }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun deleteFile(fileName: String) {
            try {
                File(filesDir, fileName).takeIf { it.exists() }?.delete()
            } catch (e: Exception) {
                Log.e("FancyAI", "File delete failed", e)
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun listMediaFiles(): String {
            return try {
                val dir = File(filesDir, "media")
                if (!dir.exists() || !dir.isDirectory) return "[]"
                "[${(dir.listFiles() ?: emptyArray()).joinToString(",") { "\"${it.name}\"" }}]"
            } catch (_: Exception) { "[]" }
        }

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
        fun llamaLoadModel(modelPath: String): Boolean = LlamaInference.loadModel(modelPath)

        @Suppress("unused")
        @JavascriptInterface
        fun llamaLoadModelByName(fileName: String): Boolean {
            val file = File(File(filesDir, "models"), fileName)
            val ok = if (file.exists()) LlamaInference.loadModel(file.absolutePath) else false
            if (ok) {
                getSharedPreferences("fancy_ai", MODE_PRIVATE).edit()
                    .putString("last_model_path", file.absolutePath).apply()
            }
            return ok
        }

        @Suppress("unused")
        @JavascriptInterface
        fun llamaListModels(): String {
            return try {
                val dir = File(filesDir, "models")
                if (!dir.exists() || !dir.isDirectory) return "[]"
                val files = dir.listFiles { _, name -> name.endsWith(".gguf") } ?: emptyArray()
                val names = files.map { it.name.replace("\"", "\\\"") }
                "[${names.joinToString(",") { "\"$it\"" }}]"
            } catch (_: Exception) { "[]" }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun llamaDeleteModel(fileName: String): Boolean {
            return try {
                val file = File(File(filesDir, "models"), fileName)
                if (file.exists()) {
                    if (LlamaInference.getLoadedModelPath() == file.absolutePath) {
                        LlamaInference.unloadModel()
                    }
                    file.delete()
                } else false
            } catch (_: Exception) { false }
        }

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
            return try {
                val uri = Uri.parse(contentUriStr)
                val modelsDir = File(filesDir, "models").apply { mkdirs() }
                val destFile = File(modelsDir, "model.gguf")
                Log.d("FancyAI", "Copying GGUF from: $contentUriStr")
                val input = contentResolver.openInputStream(uri) ?: run {
                    Log.e("FancyAI", "Failed to open input stream for: $contentUriStr")
                    return false
                }
                input.use { stream ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(65536)
                        var n: Int
                        var total = 0L
                        while (stream.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            total += n
                        }
                        Log.d("FancyAI", "Copied ${total / (1024 * 1024)}MB → ${destFile.absolutePath}")
                    }
                }
                LlamaInference.loadModel(destFile.absolutePath)
            } catch (e: Exception) {
                Log.e("FancyAI", "llamaCopyAndLoad failed: ${e.message}", e)
                false
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun llamaUnloadModel() = LlamaInference.unloadModel()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaIsModelLoaded(): Boolean = LlamaInference.isModelLoaded()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaGetLoadedModelPath(): String? = LlamaInference.getLoadedModelPath()

        @Suppress("unused")
        @JavascriptInterface
        fun llamaGetChatTemplate(): String = LlamaInference.getChatTemplate()

        /**
         * JS calls this with a JS-generated integer cbId.
         * Before calling, JS sets up:
         *   window._llamaToken[cbId] = (token) => { ... }
         *   window._llamaDone[cbId]  = ()      => { ... }
         */
        @Suppress("unused")
        @JavascriptInterface
        fun llamaInferenceAsync(prompt: String, maxTokens: Int, callbackId: Int,
                                temperature: Float, topK: Int, topP: Float) {
            Thread {
                LlamaInference.inferenceStream(prompt, maxTokens, callbackId, temperature, topK, topP)
            }.start()
        }

        @Suppress("unused")
        @JavascriptInterface
        fun llamaCancelInference() = LlamaInference.cancelInference()

        // Async so the UI (e.g. returning to the home screen) never blocks waiting
        // for an in-flight decode to release the native inference lock.
        @Suppress("unused")
        @JavascriptInterface
        fun llamaUnloadModelAsync() {
            LlamaInference.cancelInference()
            Thread { LlamaInference.unloadModel() }.start()
        }

        // Update engine params and reinitialize context if model is loaded.
        // Context params (nCtx, nThreads, flashAttn) apply immediately.
        // Model params (useMmap, useMlock) take effect on next model load.
        @Suppress("unused")
        @JavascriptInterface
        fun llamaSetEngineParams(nCtx: Int, nThreads: Int, flashAttn: Boolean,
                                 useMmap: Boolean, useMlock: Boolean, gpuLayers: Int, backend: Int, kvType: Int) {
            LlamaInference.setEngineParams(nCtx, nThreads, flashAttn, useMmap, useMlock, gpuLayers, backend, kvType)
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
