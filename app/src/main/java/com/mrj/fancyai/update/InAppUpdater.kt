package com.mrj.fancyai.update

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.android.material.snackbar.Snackbar
import com.mrj.fancyai.BuildConfig
import com.mrj.fancyai.R
import com.mrj.fancyai.util.AppLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit


class InAppUpdater(private val activity: ComponentActivity) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }


    fun check() {
        if (checkedThisSession) return
        checkedThisSession = true

        val req = Request.Builder()
            .url(LATEST_RELEASE_URL)
            // GitHub 403s API requests that omit a User-Agent; Accept pins the stable JSON schema.
            .header("User-Agent", "FancyAi-Updater")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.write(android.util.Log.WARN, TAG, "release check failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        AppLog.write(android.util.Log.WARN, TAG, "release check HTTP ${resp.code}")
                        return
                    }
                    val body = resp.body.string()
                    val release = runCatching { json.decodeFromString<Release>(body) }.getOrNull()
                    val tag = release?.tagName ?: return
                    val url = release.htmlUrl ?: return
                    if (isNewer(tag, BuildConfig.VERSION_NAME)) promptUpdate(tag, url, release.apkUrl())
                }
            }
        })
    }


    fun unregister() = Unit

    internal fun promptUpdate(tag: String, releaseUrl: String, apkUrl: String?) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val root = activity.findViewById<android.view.View>(android.R.id.content) ?: return@runOnUiThread
            val snackbar = Snackbar.make(
                root,
                activity.getString(R.string.update_available, tag),
                Snackbar.LENGTH_INDEFINITE,
            )
            if (apkUrl != null) {
                snackbar.setAction(R.string.update_get) { downloadUpdate(tag, apkUrl, root) }
            } else {
                snackbar.setAction(R.string.update_get) {
                    runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, releaseUrl.toUri()))
                    }.onFailure { e -> AppLog.write(android.util.Log.ERROR, TAG, "failed to open release page", e) }
                }
            }
            snackbar.show()
        }
    }

    private fun downloadUpdate(tag: String, apkUrl: String, root: android.view.View) {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val file = File(dir, "fancyai-$tag.apk")
        val partial = File(dir, "fancyai-$tag.apk.partial")
        val progress = Snackbar.make(root, activity.getString(R.string.update_downloading, 0), Snackbar.LENGTH_INDEFINITE)
        progress.show()
        val req = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "FancyAi-Updater")
            .get()
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.write(android.util.Log.WARN, TAG, "update download failed", e)
                runCatching { partial.delete() }
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) progress.setText(activity.getString(R.string.update_failed))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onFailure(call, IOException("HTTP ${resp.code}"))
                        return
                    }
                    try {
                        val total = resp.body.contentLength()
                        var lastPercent = -1
                        resp.body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var read = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    read += count
                                    if (total > 0) {
                                        val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                                        if (percent != lastPercent) {
                                            lastPercent = percent
                                            activity.runOnUiThread { progress.setText(activity.getString(R.string.update_downloading, percent)) }
                                        }
                                    }
                                }
                            }
                        }
                        check(partial.renameTo(file)) { "Could not finalize update" }
                        activity.runOnUiThread {
                            progress.dismiss()
                            if (!activity.isFinishing && !activity.isDestroyed) promptInstall(tag, file, root)
                        }
                    } catch (e: Exception) {
                        AppLog.write(android.util.Log.WARN, TAG, "update download failed", e)
                        runCatching { partial.delete() }
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) progress.setText(activity.getString(R.string.update_failed))
                        }
                    }
                }
            }
        })
    }

    private fun promptInstall(tag: String, file: File, root: android.view.View) {
        val snackbar = Snackbar.make(root, activity.getString(R.string.update_ready, tag), Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction(R.string.update_install) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()))
                }
                return@setAction
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { activity.startActivity(install) }
                .onFailure { e -> AppLog.write(android.util.Log.ERROR, TAG, "failed to launch installer", e) }
        }
        snackbar.show()
    }

    @Serializable
    private data class Asset(
        @SerialName("name") val name: String? = null,
        @SerialName("browser_download_url") val url: String? = null,
    )

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        @SerialName("assets") val assets: List<Asset> = emptyList(),
    ) {
        fun apkUrl(): String? {
            val apks = assets.filter { it.url != null && it.name?.endsWith(".apk", ignoreCase = true) == true }
            if (apks.isEmpty()) return null
            return (apks.firstOrNull { it.name!!.contains("arm64", ignoreCase = true) } ?: apks.first()).url
        }
    }

    companion object {
        private const val TAG = "InAppUpdate"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Mr-J-369/Fancy-Ai/releases/latest"


        @Volatile private var checkedThisSession = false

        private val VERSION_CORE = Regex("""\d+(?:\.\d+)*""")


        internal fun isNewer(remoteTag: String, localName: String): Boolean {
            val remote = versionParts(remoteTag)
            if (remote.isEmpty()) return false
            val local = versionParts(localName)
            for (i in 0 until maxOf(remote.size, local.size)) {
                val r = remote.getOrElse(i) { 0 }
                val l = local.getOrElse(i) { 0 }
                if (r != l) return r > l
            }
            return false
        }

        private fun versionParts(raw: String): List<Int> {
            val core = VERSION_CORE.find(raw)?.value ?: return emptyList()
            return core.split(".").map { it.toIntOrNull() ?: 0 }
        }
    }
}
