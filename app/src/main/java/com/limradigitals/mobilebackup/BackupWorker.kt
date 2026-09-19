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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        private const val CHANNEL_ID = "backup_progress"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_CONCURRENT_UPLOADS = 3
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("Starting upload…", 0, 0))

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
        val selectionStore = SelectionStore(applicationContext)
        val knownBackedUpKeys = ConcurrentHashMap.newKeySet<String>().apply {
            addAll(selectionStore.loadBackedUp())
        }

        val uploaded = AtomicInteger(0)
        val alreadyBackedUp = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val completedBytes = AtomicLong(0L)
        val activeBytes = ConcurrentHashMap<String, Long>()
        val totalBytes = items.sumOf { it.size }
        val semaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
        val saveLock = Any()

        fun saveKnownKeys() {
            synchronized(saveLock) {
                selectionStore.saveBackedUp(knownBackedUpKeys)
            }
        }

        setProgress(
            workDataOf(
                "completed" to 0,
                "completedBytes" to 0L,
                "totalBytes" to totalBytes,
                "uploaded" to 0,
                "already" to 0,
                "failed" to 0,
                "total" to items.size,
                "name" to "",
                "phase" to "starting"
            )
        )
        setForeground(createForegroundInfo("Uploading 0 of ${items.size}…", 0, items.size))

        val results = coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isStopped) return@withPermit FileOutcome.STOPPED

                        activeBytes[item.selectionKey] = 0L
                        try {
                            val result = drive.upload(
                                item,
                                knownBackedUpKeys,
                                { fraction ->
                                    val current = (item.size * fraction.coerceIn(0.0, 1.0)).toLong()
                                    activeBytes[item.selectionKey] = current

                                    if (fraction >= 1.0 || current >= 256L * 1024L) {
                                        setProgressAsync(
                                            workDataOf(
                                                "completed" to completed.get(),
                                                "completedBytes" to (completedBytes.get() + activeBytes.values.sum()),
                                                "totalBytes" to totalBytes,
                                                "uploaded" to uploaded.get(),
                                                "already" to alreadyBackedUp.get(),
                                                "failed" to failed.get(),
                                                "total" to items.size,
                                                "name" to item.name,
                                                "phase" to "uploading"
                                            )
                                        )
                                    }
                                },
                                prefs.driveDestinationId
                            )

                            when (result) {
                                UploadResult.UPLOADED -> {
                                    uploaded.incrementAndGet()
                                    knownBackedUpKeys.add(drive.backupKey(item))
                                }
                                UploadResult.ALREADY_BACKED_UP -> {
                                    alreadyBackedUp.incrementAndGet()
                                    knownBackedUpKeys.add(drive.backupKey(item))
                                }
                            }

                            completedBytes.addAndGet(item.size)
                            activeBytes.remove(item.selectionKey)
                            val done = completed.incrementAndGet()

                            if ((uploaded.get() + alreadyBackedUp.get()) % 10 == 0) {
                                saveKnownKeys()
                            }

                            setProgressAsync(
                                workDataOf(
                                    "completed" to done,
                                    "completedBytes" to (completedBytes.get() + activeBytes.values.sum()),
                                    "totalBytes" to totalBytes,
                                    "uploaded" to uploaded.get(),
                                    "already" to alreadyBackedUp.get(),
                                    "failed" to failed.get(),
                                    "total" to items.size,
                                    "name" to item.name,
                                    "phase" to if (done == items.size) "complete" else "uploaded"
                                )
                            )

                            FileOutcome.SUCCESS
                        } catch (e: Exception) {
                            activeBytes.remove(item.selectionKey)
                            failed.incrementAndGet()
                            val done = completed.incrementAndGet()

                            setProgressAsync(
                                workDataOf(
                                    "completed" to done,
                                    "completedBytes" to completedBytes.get(),
                                    "totalBytes" to totalBytes,
                                    "uploaded" to uploaded.get(),
                                    "already" to alreadyBackedUp.get(),
                                    "failed" to failed.get(),
                                    "total" to items.size,
                                    "name" to item.name,
                                    "phase" to "failed",
                                    "error" to (e.message ?: e.javaClass.simpleName)
                                )
                            )

                            if (e is IOException) {
                                saveKnownKeys()
                                FileOutcome.RETRYABLE_FAILURE
                            } else {
                                FileOutcome.PERMANENT_FAILURE
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        if (isStopped) {
            saveKnownKeys()
            return Result.retry()
        }

        saveKnownKeys()

        val retryableFailures = results.count { it == FileOutcome.RETRYABLE_FAILURE }
        val message = "Backup complete: ${uploaded.get()} uploaded, " +
            "${alreadyBackedUp.get()} already backed up, ${failed.get()} failed."

        if (retryableFailures > 0) {
            saveStatus("Some uploads need another attempt. " + message)
            return Result.retry()
        }

        saveStatus(message)
        return Result.success(
            workDataOf(
                "message" to message,
                "completed" to completed.get(),
                "completedBytes" to completedBytes.get(),
                "totalBytes" to totalBytes,
                "uploaded" to uploaded.get(),
                "already" to alreadyBackedUp.get(),
                "failed" to failed.get(),
                "total" to items.size
            )
        )
    }

    private enum class FileOutcome {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE,
        STOPPED
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
