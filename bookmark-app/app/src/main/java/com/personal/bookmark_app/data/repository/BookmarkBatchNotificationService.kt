package com.personal.bookmark_app.data.repository

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.personal.bookmark_app.MainActivity
import com.personal.bookmark_app.data.remote.api.BookmarkBatchJobStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BookmarkBatchNotificationService : Service() {
    @Inject lateinit var batchStatuses: BookmarkBatchStatusRepository
    @Inject lateinit var folderChanges: FolderChanges

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var monitoredId: String? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(JOB_ID)
        val sessionId = intent?.getStringExtra(SESSION_ID)
        val deleting = intent?.getBooleanExtra(DELETING, false) == true
        if (id == null || sessionId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (monitoredId == id && monitor?.isActive == true) return START_REDELIVER_INTENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "收藏批量操作", NotificationManager.IMPORTANCE_LOW)
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(null, deleting),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        monitor?.cancel()
        monitoredId = id
        monitor = scope.launch {
            try {
                val status = batchStatuses.watch(sessionId, id).first { status ->
                    if (!status.finished) notifications.notify(NOTIFICATION_ID, notification(status, deleting))
                    status.finished
                }
                folderChanges.notifyChanged()
                ServiceCompat.stopForeground(this@BookmarkBatchNotificationService, ServiceCompat.STOP_FOREGROUND_DETACH)
                notifications.notify(NOTIFICATION_ID, notification(status, deleting))
            } catch (cancelled: CancellationException) {
                if (!isActive) throw cancelled
            } catch (_: Exception) {
                // 任務仍由後端執行，回到首頁時可重新取得結果。
            }
            stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(status: BookmarkBatchJobStatus?, deleting: Boolean): Notification {
        val finished = status?.finished == true
        val action = if (status?.deleting ?: deleting) "刪除" else "移動"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (finished) "收藏${action}完成" else "正在${action}收藏")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setAutoCancel(finished)
        if (finished) {
            checkNotNull(status)
            builder.setContentText("成功 ${status.succeeded} 筆，失敗 ${status.failed} 筆")
        } else {
            val processed = status?.processed ?: 0
            builder.setContentText(status?.let { "已處理 $processed／${it.total} 筆" } ?: "正在取得進度…")
                .setProgress(status?.total ?: 0, processed, status == null)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "bookmark-move"
        private const val NOTIFICATION_ID = 153
        private const val JOB_ID = "job-id"
        private const val SESSION_ID = "session-id"
        private const val DELETING = "deleting"

        fun track(context: Context, job: BookmarkBatchJobStatus, sessionId: String) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BookmarkBatchNotificationService::class.java)
                        .putExtra(JOB_ID, job.id)
                        .putExtra(SESSION_ID, sessionId)
                        .putExtra(DELETING, job.deleting)
                )
            } catch (_: IllegalStateException) {
                // App 已進入背景時，可在下次開啟時從伺服器恢復追蹤。
            }
        }
    }
}
