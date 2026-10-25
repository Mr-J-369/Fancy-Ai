package com.mrj.fancyai.service

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import com.mrj.fancyai.MainActivity
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.aura.AuraLanHost
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Keeps the FancyAI app session visible to Android until the user explicitly exits. */
class InferenceForegroundService : Service() {
    private val postingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        AuraLanHost.stop()
        AutomaticSocialPosts.stop()
        postingScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AuraLanHost.stop()
        AutomaticSocialPosts.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            exit(this)
            return START_NOT_STICKY
        }
        startForegroundNotification()
        AutomaticSocialPosts.start(this, postingScope)
        return START_STICKY
    }

    internal fun startForegroundNotification() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exitApp = PendingIntent.getService(
            this,
            1,
            Intent(this, InferenceForegroundService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActiveSessionForeground(NOTIFICATION_ID) {
            setContentText(getString(R.string.notification_active_session_text))
            setContentIntent(openApp)
            addAction(0, getString(R.string.action_exit), exitApp)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 100
        private const val ACTION_EXIT = "com.mrj.fancyai.action.EXIT"

        /** Shared by the in-app Exit and the notification action. */
        fun exit(context: Context) {
            AuraLanHost.stop()
            AutomaticSocialPosts.stop()
            val manager = context.getSystemService(ActivityManager::class.java)
            manager.runningAppProcesses.orEmpty()
                .asSequence()
                .filter { process ->
                    (process.uid == Process.myUid()) &&
                        (process.pid != Process.myPid()) &&
                        process.processName.startsWith("${context.packageName}:")
                }
                .forEach { process -> runCatching { Process.killProcess(process.pid) } }
            stop(context)
            manager.appTasks.forEach { task -> task.finishAndRemoveTask() }
            Process.killProcess(Process.myPid())
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context, InferenceForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context, InferenceForegroundService::class.java),
            )
        }
    }
}
