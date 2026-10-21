package com.mrj.fancyai.update

import android.content.Intent
import androidx.activity.ComponentActivity
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
                    if (isNewer(tag, BuildConfig.VERSION_NAME)) promptUpdate(tag, url)
                }
            }
        })
    }


    fun unregister() = Unit

    internal fun promptUpdate(tag: String, releaseUrl: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val root = activity.findViewById<android.view.View>(android.R.id.content) ?: return@runOnUiThread
            val snackbar = Snackbar.make(
                root,
                activity.getString(R.string.update_available, tag),
                Snackbar.LENGTH_INDEFINITE,
            )
            snackbar.setAction(R.string.update_get) {
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, releaseUrl.toUri()))
                }.onFailure { e -> AppLog.write(android.util.Log.ERROR, TAG, "failed to open release page", e) }
            }
            snackbar.show()
        }
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
    )

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
