package com.mrj.fancyai.update

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.mrj.fancyai.R
import com.mrj.fancyai.util.AppLog


class InAppUpdater(private val activity: ComponentActivity) {

    private val manager = AppUpdateManagerFactory.create(activity)

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK)
                AppLog.write(android.util.Log.WARN, TAG, "update flow not accepted (resultCode=${result.resultCode})")
        }

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) promptInstall()
    }

    init { manager.registerListener(listener) }

    
    fun check() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED -> promptInstall()
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> startFlow(info)
                }
            }
            .addOnFailureListener { AppLog.write(android.util.Log.WARN, TAG, "appUpdateInfo check failed", it) }
    }

    fun unregister() = manager.unregisterListener(listener)

    private fun startFlow(info: AppUpdateInfo) {
        runCatching {
            manager.startUpdateFlowForResult(
                info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
        }.onFailure { AppLog.write(android.util.Log.ERROR, TAG, "failed to start update flow", it) }
    }

    private fun promptInstall() {
        val root = activity.findViewById<android.view.View>(android.R.id.content) ?: return
        Snackbar.make(root, R.string.update_downloaded, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.update_restart_now) { manager.completeUpdate() }
            .show()
    }

    companion object { private const val TAG = "InAppUpdate" }
}
