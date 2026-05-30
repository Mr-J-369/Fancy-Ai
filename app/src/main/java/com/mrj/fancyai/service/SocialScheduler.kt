package com.mrj.fancyai.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules autonomous character posting. The user picks a cadence in minutes; WorkManager
 * enforces a 15-minute floor for periodic background work, so anything shorter is clamped
 * (and run as close to it as the OS allows).
 */
object SocialScheduler {
    private const val WORK_NAME = "autonomous_social"
    const val MIN_INTERVAL_MINUTES = 15L

    /** Enables/updates autonomous posting at [intervalMinutes], or cancels it if [enabled] is false. */
    fun apply(context: Context, enabled: Boolean, intervalMinutes: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val minutes = intervalMinutes.toLong().coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<SocialWorker>(minutes, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
