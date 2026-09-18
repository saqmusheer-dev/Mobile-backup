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
            saveStatus("Connect Google Drive before starting backup.")
            return Result.failure()
        }

        val prefs = BackupPrefs(applicationContext)
        val items = MediaScanner(applicationContext).scan(
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

        if (items.isEmpty()) {
            saveStatus("No files found for the selected categories.")
            return Result.success()
        }

        val drive = DriveBackup(applicationContext)
        var uploaded = 0
        var failed = 0

        for (item in items) {
            if (isStopped) return Result.retry()
            try {
                drive.upload(item)
                uploaded++
                setProgress(workDataOf(
                    "uploaded" to uploaded,
                    "total" to items.size,
                    "name" to item.name,
                    "category" to item.category
                ))
            } catch (e: Exception) {
                failed++
                if (e is IOException) {
                    saveStatus("Paused after " + uploaded + " files. Will retry on Wi-Fi.")
                    return Result.retry()
                }
            }
        }

        saveStatus("Backup complete: " + uploaded + " uploaded, " + failed + " failed.")
        return if (failed == 0) Result.success() else Result.retry()
    }

    private fun saveStatus(message: String) {
        applicationContext.getSharedPreferences("backup_status", Context.MODE_PRIVATE)
            .edit().putString("message", message).apply()
    }
}
