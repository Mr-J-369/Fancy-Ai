package com.mrj.fancyai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.ValueCallback
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat

class PermissionManager(
    private val context: Context,
    private val launchFileChooser: (Intent) -> Unit,
    private val launchStoragePerms: (Array<String>) -> Unit,
    private val onAudioGranted: () -> Unit,
    private val sendToJs: (String) -> Unit
) {
    var pendingUploadMessage: ValueCallback<Array<Uri>>? = null
    private var pendingFileIntent: Intent? = null

    fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, chooserIntent: Intent) {
        pendingUploadMessage?.onReceiveValue(null)
        pendingUploadMessage = callback
        checkStorageAndLaunch(chooserIntent)
    }

    fun onFileChooserResult(resultCode: Int, data: Intent?) {
        if (pendingUploadMessage == null) return
        var results: Array<Uri>? = null
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            results = when {
                data.dataString != null -> arrayOf(Uri.parse(data.dataString))
                data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
                else -> null
            }
        }
        pendingUploadMessage!!.onReceiveValue(results)
        pendingUploadMessage = null
    }

    fun onStoragePermResult(result: Map<String, Boolean>) {
        val allGranted = result.values.all { it }
        val pendingIntent = pendingFileIntent
        if (allGranted && pendingIntent != null) {
            launchFileChooser(pendingIntent)
        } else {
            pendingUploadMessage?.let { it.onReceiveValue(null); pendingUploadMessage = null }
            Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingFileIntent = null
    }

    fun onNotificationPermResult(granted: Boolean) {
        if (!granted) {
            Toast.makeText(context, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    fun onAudioPermResult(granted: Boolean) {
        if (granted) {
            onAudioGranted()
        } else {
            Toast.makeText(context, "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
            sendToJs("OS._onSpeechEvent('error', -1)")
        }
    }

    fun isAudioPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkStorageAndLaunch(intent: Intent) {
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
        val allGranted = perms.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            launchFileChooser(intent)
        } else {
            pendingFileIntent = intent
            launchStoragePerms(perms.toTypedArray())
        }
    }
}
