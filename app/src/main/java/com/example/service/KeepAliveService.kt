package com.example.service

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
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

private const val TAG = "KeepAliveService"

class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        runCatching { createNotificationChannel() }
            .onFailure { Log.w(TAG, "Unable to create notification channel", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shouldStop = pendingStop || intent?.action == ACTION_STOP
        val serverLabel = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty()

        // Always satisfy startForeground() first if the service may have been started via
        // startForegroundService(), avoiding ForegroundServiceDidNotStartInTimeException.
        val startedForeground = runCatching {
            val notification = runCatching { buildNotification(serverLabel) }
                .getOrElse { buildMinimalFallbackNotification() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.onFailure { err ->
            Log.w(TAG, "startForeground failed cleanly", err)
        }.getOrDefault(false)

        if (shouldStop || !startedForeground) {
            pendingStop = false
            isForegroundActive = false
            if (startedForeground) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        isForegroundActive = true
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "KeepAliveService foreground time limit reached (type=$fgsType); stopping cleanly.")
        pendingStop = false
        isForegroundActive = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        isServiceAlive = false
        isForegroundActive = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.keep_alive_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildMinimalFallbackNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("VS Shell")
            .setContentText("Workspace session active")
            .setOngoing(true)
            .build()
    }

    private fun buildNotification(serverLabel: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = if (serverLabel.isNotBlank()) {
            getString(R.string.keep_alive_notif_text, serverLabel)
        } else {
            getString(R.string.keep_alive_notif_idle)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notif)
            .setContentTitle(getString(R.string.keep_alive_notif_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.action_stop_service), stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "vsshell_keep_alive"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.example.service.ACTION_STOP_KEEP_ALIVE"
        private const val EXTRA_SERVER_NAME = "extra_server_name"

        @Volatile
        private var isServiceAlive = false

        @Volatile
        private var isForegroundActive = false

        @Volatile
        private var pendingStop = false

        fun hasNotificationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun start(context: Context, serverName: String) {
            val appCtx = context.applicationContext
            if (!hasNotificationPermission(appCtx)) {
                Log.d(TAG, "Skipping KeepAliveService start until POST_NOTIFICATIONS is granted.")
                return
            }
            pendingStop = false
            runCatching {
                val intent = Intent(appCtx, KeepAliveService::class.java).apply {
                    putExtra(EXTRA_SERVER_NAME, serverName)
                }
                // Starting via startService() while app is in foreground avoids arming the fatal
                // OS 5-second startForegroundService() crash timer if OEM/API-36 policy rejects FGS.
                appCtx.startService(intent)
            }.onFailure { err ->
                Log.w(TAG, "Unable to start KeepAliveService", err)
            }
        }

        fun stop(context: Context) {
            pendingStop = true
            if (!isServiceAlive && !isForegroundActive) {
                pendingStop = false
                return
            }
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, KeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { appCtx.stopService(intent) }
        }
    }
}
