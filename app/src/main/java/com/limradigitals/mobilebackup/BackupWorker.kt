package com.limradigitals.mobilebackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
        private const val MAX_CONCURRENT_UPLOADS = 2
        private const val MAX_FILE_UPLOAD_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("Starting upload…", 0, 0))

        val prefs = BackupPrefs(applicationContext)

        // Manual backups intentionally do not use a WorkManager network
        // constraint. Check the active network here so a connected Wi-Fi or
        // mobile-data connection starts the upload immediately.
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        if (!hasInternet) {
            saveStatus("Waiting for an internet connection...")
            return Result.retry()
        }

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
        val firstError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val completedBytes = AtomicLong(0L)
        val activeBytes = ConcurrentHashMap<String, Long>()
        val totalBytes = items.sumOf { it.size }
        val semaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
        val progressMutex = Mutex()
        val saveLock = Any()

        suspend fun publishProgress(
            name: String,
            phase: String,
            bytes: Long = (completedBytes.get() + activeBytes.values.sum()).coerceIn(0L, totalBytes)
        ) {
            // Serialize progress writes. Multiple upload coroutines can finish
            // in a different order than they started; without this, an older
            // WorkManager progress snapshot can overwrite a newer one in the UI.
            progressMutex.withLock {
                setProgress(
                    workDataOf(
                        "completed" to completed.get(),
                        "completedBytes" to bytes,
                        "totalBytes" to totalBytes,
                        "uploaded" to uploaded.get(),
                        "already" to alreadyBackedUp.get(),
                        "failed" to failed.get(),
                        "total" to items.size,
                        "name" to name,
                        "phase" to phase
                    )
                )
            }
        }

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
        setForeground(createForegroundInfo("Preparing Drive destination…", 0, items.size))

        // Resolve Drive folders BEFORE starting parallel uploads. This removes
        // folder discovery/creation from the critical upload path and prevents
        // concurrent workers from racing on the same category folder.
        val resolvedParents = HashMap<String, String>()
        try {
            items.forEach { item ->
                val parentKey = prefs.driveDestinationId?.takeIf { it.isNotBlank() }
                    ?: item.category
                if (!resolvedParents.containsKey(parentKey)) {
                    resolvedParents[parentKey] = drive.resolveUploadParent(
                        item,
                        prefs.driveDestinationId
                    )
                }
            }
        } catch (e: Exception) {
            saveKnownKeys()
            val message = "Could not prepare Google Drive destination: " +
                (e.message ?: e.javaClass.simpleName)
            saveStatus(message)
            return if (e is IOException) Result.retry()
            else Result.failure(workDataOf("message" to message))
        }

        setForeground(createForegroundInfo("Uploading 0 of ${items.size}…", 0, items.size))

        val results = coroutineScope {
            // Publish UI progress independently from the upload callbacks.
            // This is important: blocking the Google HTTP thread on
            // WorkManager progress writes can make tiny uploads appear to
            // take minutes.
            val progressTicker = launch {
                while (isActive) {
                    publishProgress(
                        items.firstOrNull { activeBytes.containsKey(it.selectionKey) }?.name.orEmpty(),
                        "uploading"
                    )
                    delay(250L)
                }
            }

            val uploadResults = items.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isStopped) return@withPermit FileOutcome.STOPPED

                        activeBytes[item.selectionKey] = 0L
                        try {
                            publishProgress(item.name, "uploading")

                            var result: UploadResult? = null
                            var lastException: Exception? = null

                            repeat(MAX_FILE_UPLOAD_ATTEMPTS) { attempt ->
                                if (result != null) return@repeat

                                try {
                                    result = drive.upload(
                                        item,
                                        knownBackedUpKeys,
                                        { fraction ->
                                            val current = (item.size * fraction.coerceIn(0.0, 1.0)).toLong()
                                            activeBytes[item.selectionKey] = current
                                        },
                                        prefs.driveDestinationId,
                                        resolvedParents[
                                            prefs.driveDestinationId?.takeIf { it.isNotBlank() }
                                                ?: item.category
                                        ]
                                    )
                                } catch (e: Exception) {
                                    lastException = e
                                    if (attempt < MAX_FILE_UPLOAD_ATTEMPTS - 1) {
                                        delay(1000L * (attempt + 1))
                                    }
                                }
                            }

                            if (result == null) {
                                throw lastException ?: IOException("Upload failed for " + item.name)
                            }

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

                            publishProgress(
                                item.name,
                                if (done == items.size) "complete" else "uploaded"
                            )

                            FileOutcome.SUCCESS
                        } catch (e: Exception) {
                            activeBytes.remove(item.selectionKey)
                            failed.incrementAndGet()
                            completed.incrementAndGet()
                            val detail = e.message?.take(180) ?: e.javaClass.simpleName
                            firstError.compareAndSet(null, item.name + ": " + detail)

                            publishProgress(
                                item.name,
                                "failed",
                                completedBytes.get()
                            )

                            saveKnownKeys()
                            FileOutcome.PERMANENT_FAILURE
                        }
                    }
                }
            }.awaitAll()

            progressTicker.cancelAndJoin()
            uploadResults
        }

        if (isStopped) {
            saveKnownKeys()
            return Result.retry()
        }

        saveKnownKeys()

        val message = "Backup complete: ${uploaded.get()} uploaded, " +
            "${alreadyBackedUp.get()} already backed up, ${failed.get()} failed."
        val finalMessage = if (failed.get() > 0) {
            message + " Last error: " + (firstError.get() ?: "Unknown upload error.")
        } else {
            message
        }

        // Individual files already received their own short retry loop.
        // Do not return Result.retry() for the whole batch: that would restart
        // every file after WorkManager backoff and make the UI jump backwards.
        saveStatus(finalMessage)
        prefs.lastBackupCompleted = completed.get()
        prefs.lastBackupTotal = items.size
        prefs.lastBackupUploaded = uploaded.get()
        prefs.lastBackupAlready = alreadyBackedUp.get()
        prefs.lastBackupFailed = failed.get()
        prefs.lastBackupBytesCompleted = completedBytes.get()
        prefs.lastBackupBytesTotal = totalBytes
        prefs.lastBackupMessage = finalMessage

        val output = workDataOf(
            "message" to finalMessage,
            "completed" to completed.get(),
            "completedBytes" to completedBytes.get(),
            "totalBytes" to totalBytes,
            "uploaded" to uploaded.get(),
            "already" to alreadyBackedUp.get(),
            "failed" to failed.get(),
            "total" to items.size
        )

        return if (failed.get() > 0) {
            Result.failure(output)
        } else {
            Result.success(output)
        }
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
