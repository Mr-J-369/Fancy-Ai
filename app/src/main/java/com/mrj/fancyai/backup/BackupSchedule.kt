package com.mrj.fancyai.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel as cancelScope

internal object BackupSchedule {
    private const val JOB_ID = 45301

    fun configure(context: Context, folder: Uri?, enabled: Boolean) {
        val prefs = context.getSharedPreferences("content_backup", Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            putBoolean("enabled", enabled)
            if (folder != null) {
                if (folder.toString() != prefs.getString("folder", null)) remove("last_automatic")
                putString("folder", folder.toString())
            }
        }
        schedule(context)
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!context.getSharedPreferences("content_backup", Context.MODE_PRIVATE).getBoolean("enabled", false)) { scheduler.cancel(JOB_ID); return }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
            .setPeriodic(TimeUnit.DAYS.toMillis(1)).setPersisted(true)
            .setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS)
    }

    suspend fun automatic(context: Context) = ContentBackup.lock.withLock {
        val prefs = context.getSharedPreferences("content_backup", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return@withLock false
        val lastBackup = prefs.getLong("last_automatic", 0)
        if (lastBackup > 0 && System.currentTimeMillis() - lastBackup < TimeUnit.DAYS.toMillis(1)) {
            return@withLock false
        }
        val tree = (prefs.getString("folder", null) ?: return@withLock false).toUri()
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val resolver = context.contentResolver
        prefs.getString("pending_file", null)?.let { pending ->
            runCatching { DocumentsContract.deleteDocument(resolver, pending.toUri()) }
        }
        val name = "FancyAI-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())}.zip"
        var document = DocumentsContract.createDocument(resolver, parent, "application/zip", "$name.partial")
            ?: error("Backup folder unavailable")
        var completed = false
        try {
            prefs.edit(commit = true) { putString("pending_file", document.toString()) }
            ContentBackup.write(context, document)
            document = DocumentsContract.renameDocument(resolver, document, name) ?: error("Backup finalization failed")
            val previous = Json.decodeFromString<List<String>>(prefs.getString("automatic_files", "[]") ?: "[]")
            val all = listOf(document.toString()) + previous
            val retained = all.take(3) + all.drop(3).filter {
                !runCatching { DocumentsContract.deleteDocument(resolver, it.toUri()) }.getOrDefault(false)
            }
            prefs.edit(commit = true) {
                putString("automatic_files", Json.encodeToString(retained))
                putLong("last_automatic", System.currentTimeMillis())
                putString("result", "automatic_done")
                remove("pending_file")
            }
            completed = true
        } finally {
            if (!completed) runCatching { DocumentsContract.deleteDocument(resolver, document) }
        }
        true
    }
}

class BackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try { BackupSchedule.automatic(this@BackupJobService) }
            finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancelScope(); super.onDestroy() }
}
