package com.mrj.fancyai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mrj.fancyai.R

private const val CHANNEL_ID = "active_session"

/**
 * Marks an inference service as a special-use foreground service so Android and OEM memory
 * managers do not reclaim the isolated process while it holds a model in RAM. Every service posts
 * its own notification id, because NotificationManager keys notifications by (uid, id).
 */
internal fun Service.startActiveSessionForeground(
    notificationId: Int,
    configure: NotificationCompat.Builder.() -> Unit = {},
) {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_active_session_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_active_session_title))
        .setSmallIcon(R.drawable.ic_foreground)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .apply(configure)
        .build()
    ServiceCompat.startForeground(
        this,
        notificationId,
        notification,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        },
    )
}
