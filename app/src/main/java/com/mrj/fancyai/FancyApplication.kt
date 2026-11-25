package com.mrj.fancyai

import android.app.Application
import android.app.UiModeManager
import androidx.work.Configuration
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class FancyApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(0, 40_000)
            .build()
    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)

        if (getProcessName() != packageName) return

        CoroutineScope(Dispatchers.IO).launch {
            if (!File(filesDir, "qnn-packs").deleteRecursively()) {
                AppLog.write(android.util.Log.WARN, "AuraConvert", "Could not remove old conversion debug packs")
            }
            com.mrj.fancyai.ui.aura.ensureTaeComponentsInstalled(this@FancyApplication)
        }
        runCatching { com.mrj.fancyai.backup.BackupSchedule.schedule(this) }
            .onFailure { AppLog.write(android.util.Log.ERROR, "Backup", "Could not schedule automatic backup", it) }
        getSystemService(UiModeManager::class.java)?.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
    }
}
