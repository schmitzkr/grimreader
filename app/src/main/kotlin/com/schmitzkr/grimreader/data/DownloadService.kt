package com.schmitzkr.grimreader.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.schmitzkr.grimreader.MainActivity
import com.schmitzkr.grimreader.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process in the foreground while downloads run, with one
 * notification showing their progress and a cancel action. The transfer
 * itself lives in [DownloadManager]; this service only holds the
 * foreground state and mirrors the manager's state flow, stopping itself
 * when nothing is active.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var downloads: DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
            if (id >= 0) downloads.cancel(id)
        }
        val active = downloads.state.value.values.filter { it.isActive }
        if (active.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(notification(active))
        if (!watching) {
            watching = true
            scope.launch {
                downloads.state.collect { states ->
                    val now = states.values.filter { it.isActive }
                    if (now.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        manager().notify(NOTIFICATION_ID, notification(now))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun manager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun notification(active: List<DownloadState>): Notification {
        val manager = manager()
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of books being kept on this device"
                },
            )
        }
        val first = active.first()
        val title = if (active.size == 1) first.title else "Downloading ${active.size} books"
        val fraction = active.map { it.fraction }.average().toFloat()
        val text = if (active.size == 1) {
            if (first.status == DownloadStatus.QUEUED) "Waiting…" else "${(first.fraction * 100).toInt()}%"
        } else {
            "${first.title} and ${active.size - 1} more · ${(fraction * 100).toInt()}%"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, first.bookId.toInt(),
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_BOOK_ID, first.bookId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, (fraction * 100).toInt(), first.status == DownloadStatus.QUEUED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (active.size == 1) "Cancel" else "Cancel ${first.title}", cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 41
        const val ACTION_CANCEL = "com.schmitzkr.grimreader.download.CANCEL"
        const val EXTRA_BOOK_ID = "bookId"

        /** Called by the manager whenever a download is queued; safe to call while one already runs. */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
