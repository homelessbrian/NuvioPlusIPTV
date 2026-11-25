package com.nuvio.tv.livetv.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nuvio.tv.R

/**
 * Keeps recordings going while the app is in the background: a foreground service (Android
 * shows a small "Recording" notification for it), with the box kept awake and Wi-Fi kept on.
 * Started when a recording starts, stopped when none are left.
 */
class LiveTvRecordingService : Service() {

    companion object {
        private const val TAG = "LiveTvRecordingService"
        private const val CHANNEL_ID = "livetv_recording"
        private const val NOTIFICATION_ID = 7341

        /**
         * Already running in the foreground. Each start makes Android wait, on the app's main
         * thread, for every settings save still going to storage; with storage busy (a fast
         * catch-up download) that froze the app, so a running service isn't started again.
         */
        @Volatile private var running = false

        fun start(context: Context) {
            if (running) return
            val intent = Intent(context, LiveTvRecordingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { Log.w(TAG, "Couldn't start the recording service", it) }
        }

        fun stop(context: Context) {
            running = false
            runCatching { context.stopService(Intent(context, LiveTvRecordingService::class.java)) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Recordings", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Live TV is recording"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Recording Live TV")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running = true
        } catch (e: Exception) {
            // Not allowed right now (Android 12+ from the background): recording still runs while
            // the app is open.
            Log.w(TAG, "startForeground not allowed: ${e.message}")
        }
        if (wakeLock == null) {
            wakeLock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nuvio:LiveTvRecording")
                    .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
            }.getOrNull()
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nuvio:LiveTvRecording")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
