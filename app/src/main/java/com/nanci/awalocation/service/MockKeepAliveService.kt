package com.nanci.awalocation.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nanci.awalocation.MainActivity
import com.nanci.awalocation.R

/**
 * 轻量前台保活服务：在定位/路线模拟期间维持前台进程优先级，
 * 避免 Android 17 后台进程冻结与澎湃 OS 墓碑机制导致路线模拟切后台后中断。
 */
class MockKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission(this)) {
            Log.w(TAG, "Skip foreground keep-alive: location permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        val isRouteRunning = intent?.getBooleanExtra(EXTRA_IS_ROUTE, false) ?: false
        ensureNotificationChannel()
        val notification = buildNotification(isRouteRunning)

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }

        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        }.onFailure {
            Log.w(TAG, "Failed to enter foreground state", it)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mock_keep_alive_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.mock_keep_alive_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(isRouteRunning: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        val title = getString(R.string.mock_keep_alive_title)
        val text = if (isRouteRunning) {
            getString(R.string.mock_keep_alive_text_route)
        } else {
            getString(R.string.mock_keep_alive_text_location)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_my_location_24)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "MockKeepAliveService"
        private const val CHANNEL_ID = "awalocation_mock_keep_alive"
        private const val NOTIFICATION_ID = 10042
        private const val EXTRA_IS_ROUTE = "extra_is_route"

        private fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }

        fun start(context: Context, isRoute: Boolean) {
            val appContext = context.applicationContext
            if (!hasLocationPermission(appContext)) return
            val intent = Intent(appContext, MockKeepAliveService::class.java).apply {
                putExtra(EXTRA_IS_ROUTE, isRoute)
            }
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
            }.onFailure {
                Log.w(TAG, "Unable to start MockKeepAliveService (isRoute=$isRoute)", it)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, MockKeepAliveService::class.java))
            }.onFailure {
                Log.w(TAG, "Unable to stop MockKeepAliveService", it)
            }
        }
    }
}
