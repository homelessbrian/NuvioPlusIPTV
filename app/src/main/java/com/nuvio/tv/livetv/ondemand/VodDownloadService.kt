package com.nuvio.tv.livetv.ondemand

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** User-initiated downloads continue when the viewer leaves On Demand. No provider addresses or
 * titles are put in the service intent, system notifications, or Work/DownloadManager history. */
class VodDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads by lazy { vodDownloads(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, getString(com.nuvio.tv.R.string.vod_download_channel),
                NotificationManager.IMPORTANCE_LOW))
        startForeground(5728, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(com.nuvio.tv.R.string.vod_download_notification)).setOngoing(true)
            .setOnlyAlertOnce(true).setProgress(0, 0, true).build())
        val profile = intent?.getIntExtra("profile", 0) ?: 0
        scope.launch {
            try {
                if (profile > 0) downloads.drain(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Durable rows can be retried after Android unlocks or frees local storage.
            } finally {
                stopSelfResult(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloads.interruptTransfers()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        downloads.interruptTransfers()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private companion object { const val CHANNEL = "playlist_vod_downloads" }
}
