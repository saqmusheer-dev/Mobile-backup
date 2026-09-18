package com.limradigitals.mobilebackup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import java.io.IOException

class BackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val account = GoogleSignIn.getLastSignedInAccount(applicationContext)
        if (account == null || !GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))) {
            val message = "Connect Google Drive before starting backup."
            saveStatus(message)
            return Result.failure(workDataOf("message" to message))
        }

        val prefs = BackupPrefs(applicationContext)
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
        var uploaded = 0
        var alreadyBackedUp = 0
        var failed = 0
        var completed = 0

        setProgress(workDataOf(
            "completed" to 0,
            "uploaded" to 0,
            "already" to 0,
            "failed" to 0,
            "total" to items.size,
            "name" to "",
            "phase" to "starting"
        ))

        for (item in items) {
            if (isStopped) return Result.retry()

            setProgress(workDataOf(
                "completed" to completed,
                "uploaded" to uploaded,
                "already" to alreadyBackedUp,
                "failed" to failed,
                "total" to items.size,
                "name" to item.name,
                "phase" to "uploading"
            ))

            try {
                when (drive.upload(item)) {
                    UploadResult.UPLOADED -> uploaded++
                    UploadResult.ALREADY_BACKED_UP -> alreadyBackedUp++
                }
                completed++

                setProgress(workDataOf(
                    "completed" to completed,
                    "uploaded" to uploaded,
                    "already" to alreadyBackedUp,
                    "failed" to failed,
                    "total" to items.size,
                    "name" to item.name,
                    "phase" to if (completed == items.size) "complete" else "uploaded"
                ))
            } catch (e: Exception) {
                failed++
                completed++

                setProgress(workDataOf(
                    "completed" to completed,
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
                "uploaded" to uploaded,
                "already" to alreadyBackedUp,
                "failed" to failed,
                "total" to items.size
            ))
        } else {
            Result.retry()
        }
    }

    private fun saveStatus(message: String) {
        applicationContext.getSharedPreferences("backup_status", Context.MODE_PRIVATE)
            .edit().putString("message", message).apply()
    }
}
