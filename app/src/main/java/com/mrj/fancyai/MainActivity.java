package com.mrj.fancyai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {
    private WebView myWebView;
    private ValueCallback<Uri[]> mUploadMessage;
    private Intent mPendingIntent;

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
                if (mPendingIntent != null) {
                    fileChooserLauncher.launch(mPendingIntent);
                    mPendingIntent = null;
                }
            }
    );

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (myWebView.canGoBack()) {
                    myWebView.goBack();
                } else {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Exit Fancy AI")
                            .setMessage("Are you sure you want to exit?")
                            .setPositiveButton("Exit", (dialog, which) -> {
                                setEnabled(false);
                                finish();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            }
        });
    }

    private final ConcurrentHashMap<String, ByteArrayOutputStream> backupChunks = new ConcurrentHashMap<>();

    public class WebAppInterface {
        @JavascriptInterface
        public void exportBackup(String dataUrl) {
            saveBase64File(dataUrl, null);
        }

        @JavascriptInterface
        public String startBackup() {
            String id = "bk_" + System.currentTimeMillis();
            backupChunks.put(id, new ByteArrayOutputStream());
            return id;
        }

        @JavascriptInterface
        public void appendBackupChunk(String backupId, String base64Chunk) {
            ByteArrayOutputStream baos = backupChunks.get(backupId);
            if (baos == null) return;
            try {
                byte[] decoded = Base64.decode(base64Chunk, Base64.DEFAULT);
                baos.write(decoded);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void finishBackup(String backupId, String extension) {
            ByteArrayOutputStream baos = backupChunks.remove(backupId);
            if (baos == null) return;
            try {
                byte[] allBytes = baos.toByteArray();
                String ext = (extension != null && !extension.isEmpty()) ? extension : ".zip";
                saveRawData(allBytes, "Backup_" + System.currentTimeMillis() + ext);
            } catch (Exception e) {
                Log.e("FancyAI", "Finish backup failed", e);
            }
        }

        @JavascriptInterface
        public String saveImageToDisk(String base64Data) {
            if (base64Data == null || !base64Data.contains(",")) return null;
            try {
                String pureBase64 = base64Data.substring(base64Data.indexOf(",") + 1);
                byte[] decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT);
                
                File dir = new File(getFilesDir(), "media");
                if (!dir.exists()) dir.mkdirs();
                
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

        @JavascriptInterface
        public void saveToFile(String fileName, String content) {
            try {
                File file = new File(getFilesDir(), fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(content.getBytes());
                }
            } catch (Exception e) {
                Log.e("FancyAI", "Save to file failed", e);
            }
        }

        @JavascriptInterface
        public String readFile(String fileName) {
            try {
                File file = new File(getFilesDir(), fileName);
                if (!file.exists()) return null;
                FileInputStream fis = new FileInputStream(file);
                byte[] bytes = new byte[(int) file.length()];
                fis.read(bytes);
                fis.close();
                return new String(bytes);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public void shareImage(String dataUrl) {
            if (dataUrl == null || !dataUrl.startsWith("data:image/")) return;
            try {
                String base64Content = dataUrl.substring(dataUrl.indexOf(",") + 1);
                byte[] decodedBytes = Base64.decode(base64Content, Base64.DEFAULT);
                File cachePath = new File(getCacheDir(), "shared_images");
                cachePath.mkdirs();
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
    }

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
                Toast.makeText(this, "Saved to Downloads/FancyAI", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) { Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show(); }
    }
}
