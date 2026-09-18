package com.limradigitals.mobilebackup

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class BackupUiState(
    val message: String = "Ready. Scan your phone.",
    val filesFound: Int = 0,
    val pending: Int = 0,
    val driveConnected: Boolean = false,
    val wifiOnly: Boolean = true,
    val autoBackup: Boolean = false,
    val deleteAfterVerified: Boolean = false,
    val phoneImages: Boolean = true,
    val phoneVideos: Boolean = true,
    val phoneAudio: Boolean = true,
    val phoneDocuments: Boolean = true,
    val whatsappImages: Boolean = true,
    val whatsappVideos: Boolean = true,
    val whatsappAudio: Boolean = true,
    val whatsappDocuments: Boolean = true,
    val downloads: Boolean = true,
    val allFilesAccess: Boolean = false
)

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = BackupPrefs(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(
        BackupUiState(
            driveConnected = DriveBackup(app).isConnected(),
            wifiOnly = prefs.wifiOnly,
            autoBackup = prefs.autoBackup,
            deleteAfterVerified = prefs.deleteAfterVerified,
            phoneImages = prefs.phoneImages,
            phoneVideos = prefs.phoneVideos,
            phoneAudio = prefs.phoneAudio,
            phoneDocuments = prefs.phoneDocuments,
            whatsappImages = prefs.whatsappImages,
            whatsappVideos = prefs.whatsappVideos,
            whatsappAudio = prefs.whatsappAudio,
            whatsappDocuments = prefs.whatsappDocuments,
            downloads = prefs.downloads,
            allFilesAccess = hasAllFilesAccess()
        )
    )
    val state = _state.asStateFlow()

    fun setMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun refreshStorageAccess() {
        _state.value = _state.value.copy(allFilesAccess = hasAllFilesAccess())
    }

    fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getApplication<Application>().packageName)
            )
            getApplication<Application>().startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun scan() {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "Scanning selected categories...")
            val count = MediaScanner(getApplication()).scan(
                phoneImages = prefs.phoneImages,
                phoneVideos = prefs.phoneVideos,
                phoneAudio = prefs.phoneAudio,
                phoneDocuments = prefs.phoneDocuments,
                whatsappImages = prefs.whatsappImages,
                whatsappVideos = prefs.whatsappVideos,
                whatsappAudio = prefs.whatsappAudio,
                whatsappDocuments = prefs.whatsappDocuments,
                downloads = prefs.downloads
            ).size

            val needsAllFiles = prefs.phoneDocuments || prefs.whatsappDocuments
            val warning = if (needsAllFiles && !hasAllFilesAccess() && Build.VERSION.SDK_INT >= 30) {
                " Documents need full storage access."
            } else ""

            _state.value = _state.value.copy(
                message = "Scan complete." + warning,
                filesFound = count,
                pending = count,
                allFilesAccess = hasAllFilesAccess()
            )
        }
    }

    fun connectDrive(onIntent: (Intent) -> Unit) {
        val options = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
        )
            .requestEmail()
            .requestScopes(
                com.google.android.gms.common.api.Scope(
                    com.google.api.services.drive.DriveScopes.DRIVE_FILE
                )
            )
            .build()

        onIntent(
            com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(
                getApplication(), options
            ).signInIntent
        )
    }

    fun driveConnected() {
        _state.value = _state.value.copy(
            driveConnected = true,
            message = "Google Drive connected."
        )
    }

    fun setWifiOnly(value: Boolean) {
        prefs.wifiOnly = value
        _state.value = _state.value.copy(wifiOnly = value)
    }

    fun setAutoBackup(value: Boolean) {
        prefs.autoBackup = value
        _state.value = _state.value.copy(autoBackup = value)
        if (value) scheduleAutomaticBackup()
        else workManager.cancelUniqueWork("mobile-backup")
    }

    fun setDeleteAfterVerified(value: Boolean) {
        prefs.deleteAfterVerified = value
        _state.value = _state.value.copy(deleteAfterVerified = value)
    }

    fun setCategory(which: String, value: Boolean) {
        when (which) {
            "phoneImages" -> prefs.phoneImages = value
            "phoneVideos" -> prefs.phoneVideos = value
            "phoneAudio" -> prefs.phoneAudio = value
            "phoneDocuments" -> prefs.phoneDocuments = value
            "whatsappImages" -> prefs.whatsappImages = value
            "whatsappVideos" -> prefs.whatsappVideos = value
            "whatsappAudio" -> prefs.whatsappAudio = value
            "whatsappDocuments" -> prefs.whatsappDocuments = value
            "downloads" -> prefs.downloads = value
        }

        _state.value = _state.value.copy(
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
    }

    fun startBackup() {
        if (!DriveBackup(getApplication()).isConnected()) {
            _state.value = _state.value.copy(
                driveConnected = false,
                message = "Connect Google Drive first."
            )
            return
        }

        enqueueBackup()
        _state.value = _state.value.copy(
            message = if (prefs.wifiOnly) "Backup queued. Waiting for Wi-Fi..." else "Backup queued."
        )
    }

    private fun enqueueBackup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(prefs.networkType())
            .build()

        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS
            )
            .build()

        workManager.enqueueUniqueWork(
            "mobile-backup-now",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleAutomaticBackup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(prefs.networkType())
            .build()

        val request = androidx.work.PeriodicWorkRequestBuilder<BackupWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        workManager.enqueueUniquePeriodicWork(
            "mobile-backup",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
