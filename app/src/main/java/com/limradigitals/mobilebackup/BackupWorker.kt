package com.limradigitals.mobilebackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import java.io.IOException

class BackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        private const val CHANNEL_ID = "backup_progress"
        private const val NOTIFICATION_ID = 4101
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("Preparing backup…", 0, 0))

        val prefs = BackupPrefs(applicationContext)

        val account = GoogleSignIn.getLastSignedInAccount(applicationContext)
        if (account == null || !GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))) {
            val message = "Connect Google Drive before starting backup."
            saveStatus(message)
            return Result.failure(workDataOf("message" to message))
        }

        val allItems = MediaScanner(applicationContext).scan(
            phoneImages = prefs.phoneImages,
            phoneVideos = prefs.phoneVideos,
            phoneAudio = prefs.phoneAudio,
            phoneDocuments = prefs.phoneDocuments,
            whatsappImages = prefs.whatsappImages,
            whatsappVideos = prefs.whatsappVideos,
            whatsappAudio = prefs.whatsappAudio,
            whatsappDocuments = prefs.whatsappDocuments,
            downloads = prefs.downloads
        )

        val selectedKeys = SelectionStore(applicationContext).loadSelected()
        val items = allItems.filter { selectedKeys.contains(it.selectionKey) }

        if (items.isEmpty()) {
            val message = "No files selected for backup."
            saveStatus(message)
            return Result.success(workDataOf("message" to message))
        }

        val drive = DriveBackup(applicationContext)
        val knownBackedUpKeys: Set<String>? = try {
            drive.findExistingKeys(items.map { drive.backupKey(it) }.toSet())
        } catch (_: Exception) {
            null
        }
        // Check only the selected files in a small batched Drive query. This avoids
        // one network search per file while keeping duplicate detection reliable.
        var uploaded = 0
        var alreadyBackedUp = 0
        var failed = 0
        var completed = 0
        val totalBytes = items.sumOf { it.size }
        var completedBytes = 0L

        setProgress(workDataOf(
            "completed" to 0,
            "completedBytes" to 0L,
            "totalBytes" to totalBytes,
            "uploaded" to 0,
            "already" to 0,
            "failed" to 0,
            "total" to items.size,
            "name" to "",
            "phase" to "starting"
        ))
        setForeground(createForegroundInfo("Starting backup…", 0, items.size))

        for (item in items) {
            if (isStopped) return Result.retry()

            setProgress(workDataOf(
                "completed" to completed,
                "completedBytes" to completedBytes,
                "totalBytes" to totalBytes,
                "uploaded" to uploaded,
                "already" to alreadyBackedUp,
                "failed" to failed,
                "total" to items.size,
                "name" to item.name,
                "phase" to "uploading"
            ))
            setForeground(
                createForegroundInfo(
                    "Uploading " + (completed + 1).coerceAtMost(items.size) +
                        " of " + items.size + " • " + item.name,
                    completed,
                    items.size
                )
            )

            try {
                var lastReportedBytes = completedBytes
                when (drive.upload(item, knownBackedUpKeys, { currentFraction ->
                    val currentBytes = (item.size * currentFraction.coerceIn(0.0, 1.0)).toLong()
                    val overallBytes = completedBytes + currentBytes
                    if (overallBytes - lastReportedBytes >= 256L * 1024L ||
                        currentFraction >= 1.0
                    ) {
                        lastReportedBytes = overallBytes
                        setProgressAsync(workDataOf(
                            "completed" to completed,
                            "completedBytes" to overallBytes,
                            "totalBytes" to totalBytes,
                            "uploaded" to uploaded,
                            "already" to alreadyBackedUp,
                            "failed" to failed,
                            "total" to items.size,
                            "name" to item.name,
                            "phase" to "uploading"
                        ))
                    }
                }, prefs.driveDestinationId) {
                    UploadResult.UPLOADED -> {
                        uploaded++
                    }
                    UploadResult.ALREADY_BACKED_UP -> alreadyBackedUp++
                }
                completed++
                completedBytes += item.size

                setProgress(workDataOf(
                    "completed" to completed,
                    "completedBytes" to completedBytes,
                    "totalBytes" to totalBytes,
                    "uploaded" to uploaded,
                    "already" to alreadyBackedUp,
                    "failed" to failed,
                    "total" to items.size,
                    "name" to item.name,
                    "phase" to if (completed == items.size) "complete" else "uploaded"
                ))
                setForeground(
                    createForegroundInfo(
                        "Processed " + completed + " of " + items.size,
                        completed,
                        items.size
                    )
                )
            } catch (e: Exception) {
                failed++
                completed++

                setProgress(workDataOf(
                    "completed" to completed,
                    "completedBytes" to completedBytes,
                    "totalBytes" to totalBytes,
                    "uploaded" to uploaded,
                    "already" to alreadyBackedUp,
                    "failed" to failed,
                    "total" to items.size,
                    "name" to item.name,
                    "phase" to "failed",
                    "error" to (e.message ?: e.javaClass.simpleName)
                ))

                if (e is IOException) {
                    saveStatus("Paused after " + completed + " of " + items.size + " files. Will retry.")
                    return Result.retry()
                }
            }
        }

        val message = "Backup complete: " + uploaded + " uploaded, " +
            alreadyBackedUp + " already backed up, " + failed + " failed."
        saveStatus(message)

        return if (failed == 0) {
            Result.success(workDataOf(
                "message" to message,
                "completed" to completed,
                "completedBytes" to completedBytes,
                "totalBytes" to totalBytes,
                "uploaded" to uploaded,
                "already" to alreadyBackedUp,
                "failed" to failed,
                "total" to items.size
            ))
        } else {
            Result.retry()
        }
    }

    private fun createForegroundInfo(text: String, completed: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Backup progress",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Mobile Backup")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(0), completed.coerceAtLeast(0), total <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }


    private fun saveStatus(message: String) {
        applicationContext.getSharedPreferences("backup_status", Context.MODE_PRIVATE)
            .edit().putString("message", message).apply()
    }
}