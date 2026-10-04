package com.mrj.fancyai

import android.app.Application
import android.app.UiModeManager
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class FancyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)

        if (getProcessName() != packageName) return

        CoroutineScope(Dispatchers.IO).launch {
            if (!File(filesDir, "qnn-packs").deleteRecursively()) {
                AppLog.write(android.util.Log.WARN, "AuraConvert", "Could not remove old conversion debug packs")
            }
        }
        runCatching { com.mrj.fancyai.backup.BackupSchedule.schedule(this) }
            .onFailure { AppLog.write(android.util.Log.ERROR, "Backup", "Could not schedule automatic backup", it) }
        getSystemService(UiModeManager::class.java)?.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
    }
}
