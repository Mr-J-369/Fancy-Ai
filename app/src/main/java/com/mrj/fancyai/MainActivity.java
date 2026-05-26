package com.mrj.fancyai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private WebView myWebView;
    private ValueCallback<Uri[]> mUploadMessage;
    private Intent mPendingIntent;
    
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;

    private final ActivityResultLauncher<Intent> fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (mUploadMessage == null) return;
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getDataString() != null) {
                        results = new Uri[]{Uri.parse(data.getDataString())};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                mUploadMessage.onReceiveValue(results);
                mUploadMessage = null;
            }
    );

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean allGranted = true;
                for (boolean granted : result.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted && mPendingIntent != null) {
                    fileChooserLauncher.launch(mPendingIntent);
                } else {
                    if (mUploadMessage != null) {
                        mUploadMessage.onReceiveValue(null);
                        mUploadMessage = null;
                    }
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                }
                mPendingIntent = null;
            }
    );

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable Edge-to-Edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        setContentView(R.layout.activity_main);

        myWebView = findViewById(R.id.webview);

        WebSettings settings = myWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        myWebView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        myWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mUploadMessage != null) mUploadMessage.onReceiveValue(null);
                mUploadMessage = filePathCallback;
                checkPermissionsAndLaunch(fileChooserParams.createIntent());
                return true;
            }
        });

        myWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            saveBase64File(url, mimetype);
        });

        myWebView.loadUrl("file:///android_asset/index.html");

        initTTS();
        initSTT();
        createNotificationChannel();
        scheduleAutonomousWorker();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Delegate back navigation to the JS OS layer instead of using WebView.goBack()
                // This avoids history stack desync issues with overlays and in-app navigation
                myWebView.evaluateJavascript("if(typeof OS!=='undefined'&&typeof OS.goBack==='function'){OS.goBack();}else{try{__osGoBackFallback();}catch(e){}}", null);
            }
        });
    }

    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });
    }

    private void initSTT() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { sendToJs("OS._onSpeechEvent('ready')"); }
                @Override public void onBeginningOfSpeech() { sendToJs("OS._onSpeechEvent('beginning')"); }
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { sendToJs("OS._onSpeechEvent('end')"); }
                @Override public void onError(int error) { sendToJs("OS._onSpeechEvent('error', " + error + ")"); }
                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0).replace("'", "\\'");
                        sendToJs("OS._onSpeechResult('" + text + "')");
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void scheduleAutonomousWorker() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest autonomousWorkRequest =
                new PeriodicWorkRequest.Builder(AutonomousWorker.class, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "AutonomousAwareness",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                autonomousWorkRequest
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("fancy_ai_notifications", "Fancy AI Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Notifications from your AI companions");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void sendToJs(String script) {
        runOnUiThread(() -> myWebView.evaluateJavascript(script, null));
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        super.onDestroy();
    }

    private final ConcurrentHashMap<String, ByteArrayOutputStream> backupChunks = new ConcurrentHashMap<>();

    public class WebAppInterface {
        @SuppressWarnings("unused")
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "FancyAI_TTS");
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void stopSpeaking() {
            if (tts != null) {
                tts.stop();
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void startListening() {
            runOnUiThread(() -> {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                } else {
                    if (speechRecognizer != null) {
                        speechRecognizer.startListening(speechRecognizerIntent);
                    }
                }
            });
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(() -> {
                if (speechRecognizer != null) {
                    speechRecognizer.stopListening();
                }
            });
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void showNotification(String title, String message, String charId) {
            Intent intent = new Intent(MainActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            // We could pass charId to open specific chat, but for now just open app
            PendingIntent pendingIntent = PendingIntent.getActivity(MainActivity.this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(MainActivity.this, "fancy_ai_notifications")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(MainActivity.this);
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) {
                notificationManager.notify((int) System.currentTimeMillis(), builder.build());
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void exportBackup(String dataUrl) {
            saveBase64File(dataUrl, null);
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public String startBackup() {
            String id = "bk_" + System.currentTimeMillis();
            backupChunks.put(id, new ByteArrayOutputStream());
            return id;
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void appendBackupChunk(String backupId, String base64Chunk) {
            ByteArrayOutputStream baos = backupChunks.get(backupId);
            if (baos == null) return;
            try {
                byte[] decoded = Base64.decode(base64Chunk, Base64.DEFAULT);
                synchronized (baos) {
                    baos.write(decoded);
                }
            } catch (Exception ignored) {}
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void finishBackup(String backupId, String extension) {
            ByteArrayOutputStream baos = backupChunks.remove(backupId);
            if (baos == null) return;
            try {
                byte[] allBytes;
                synchronized (baos) {
                    allBytes = baos.toByteArray();
                }
                String ext = (extension != null && !extension.isEmpty()) ? extension : ".zip";
                saveRawData(allBytes, "Backup_" + System.currentTimeMillis() + ext);
            } catch (Exception e) {
                Log.e("FancyAI", "Finish backup failed", e);
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public String saveImageToDisk(String base64Data) {
            if (base64Data == null || !base64Data.contains(",")) return null;
            try {
                String pureBase64 = base64Data.substring(base64Data.indexOf(",") + 1);
                byte[] decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT);
                
                File dir = new File(getFilesDir(), "media");
                if (!dir.exists() && !dir.mkdirs()) return null;
                
                String fileName = "img_" + System.currentTimeMillis() + ".png";
                File file = new File(dir, fileName);
                
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(decodedBytes);
                }
                return fileName;
            } catch (Exception e) {
                Log.e("FancyAI", "Disk save failed", e);
                return null;
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public String loadImageFromDisk(String fileName) {
            try {
                File file = new File(getFilesDir(), "media/" + fileName);
                if (!file.exists()) return null;
                
                FileInputStream fis = new FileInputStream(file);
                byte[] bytes = new byte[(int) file.length()];
                fis.read(bytes);
                fis.close();
                
                // NO_WRAP is essential for Data URLs in WebViews
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                return "data:image/png;base64," + b64;
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void saveToFile(String fileName, String content) {
            try {
                File file = new File(getFilesDir(), fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Log.e("FancyAI", "Save to file failed", e);
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public String readFile(String fileName) {
            try {
                File file = new File(getFilesDir(), fileName);
                if (!file.exists()) return null;
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] bytes = new byte[(int) file.length()];
                    int offset = 0;
                    int numRead;
                    while (offset < bytes.length && (numRead = fis.read(bytes, offset, bytes.length - offset)) >= 0) {
                        offset += numRead;
                    }
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void shareImage(String dataUrl) {
            if (dataUrl == null || !dataUrl.startsWith("data:image/")) return;
            try {
                String base64Content = dataUrl.substring(dataUrl.indexOf(",") + 1);
                byte[] decodedBytes = Base64.decode(base64Content, Base64.DEFAULT);
                File cachePath = new File(getCacheDir(), "shared_images");
                if (!cachePath.exists() && !cachePath.mkdirs()) return;
                File file = new File(cachePath, "shared_image_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(decodedBytes); }
                Uri contentUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", file);
                if (contentUri != null) {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    shareIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
                    shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                    shareIntent.setType("image/png");
                    startActivity(Intent.createChooser(shareIntent, "Share Image"));
                }
            } catch (Exception e) { Log.e("FancyAI", "Share failed", e); }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void deleteFile(String fileName) {
            try {
                File file = new File(getFilesDir(), fileName);
                if (file.exists()) file.delete();
            } catch (Exception e) {
                Log.e("FancyAI", "File delete failed", e);
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public String listMediaFiles() {
            try {
                File dir = new File(getFilesDir(), "media");
                if (!dir.exists() || !dir.isDirectory()) return "[]";
                File[] files = dir.listFiles();
                if (files == null) return "[]";
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < files.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(files[i].getName()).append("\"");
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void requestExit() {
            runOnUiThread(() -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Exit Fancy AI")
                        .setMessage("Are you sure you want to exit?")
                        .setPositiveButton("Exit", (dialog, which) -> {
                            finish();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void setForegroundServiceActive(boolean active, String text) {
            Intent intent = new Intent(MainActivity.this, FancyAiForegroundService.class);
            if (active) {
                intent.putExtra("content", text != null ? text : "Processing AI tasks...");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } else {
                intent.setAction("STOP_SERVICE");
                startService(intent);
            }
        }
    }

    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private final ActivityResultLauncher<String> audioPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    if (speechRecognizer != null) {
                        speechRecognizer.startListening(speechRecognizerIntent);
                    }
                } else {
                    Toast.makeText(this, "Microphone permission required for voice input", Toast.LENGTH_SHORT).show();
                    sendToJs("OS._onSpeechEvent('error', -1)");
                }
            }
    );

    private void checkPermissionsAndLaunch(Intent intent) {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        boolean allGranted = true;
        for (String p : perms) { if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; } }
        if (allGranted) fileChooserLauncher.launch(intent);
        else { mPendingIntent = intent; permissionLauncher.launch(perms.toArray(new String[0])); }
    }

    private void saveBase64File(String dataUrl, String mimeType) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return;
        try {
            if (mimeType == null || mimeType.isEmpty() || mimeType.contains("octet-stream")) {
                int start = dataUrl.indexOf(":") + 1;
                int end = dataUrl.indexOf(";");
                if (start > 0 && end > start) mimeType = dataUrl.substring(start, end);
            }
            String base64Content = dataUrl.substring(dataUrl.indexOf(",") + 1);
            byte[] decodedBytes = Base64.decode(base64Content, Base64.DEFAULT);
            String extension = ".bin";
            if (mimeType != null) {
                if (mimeType.contains("png")) extension = ".png";
                else if (mimeType.contains("jpeg") || mimeType.contains("jpg")) extension = ".jpg";
                else if (mimeType.contains("json")) extension = ".json";
                else if (mimeType.contains("zip")) extension = ".zip";
            }
            saveRawData(decodedBytes, "FancyAI_" + System.currentTimeMillis() + extension);
        } catch (Exception ignored) {}
    }

    private void saveRawData(byte[] bytes, String fileName) {
        try {
            File downloadFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FancyAI");
            if (!downloadFolder.exists() && !downloadFolder.mkdirs()) return;
            File file = new File(downloadFolder, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
                runOnUiThread(() -> Toast.makeText(this, "Saved to Downloads/FancyAI", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show());
        }
    }
}
