package com.limradigitals.mobilebackup

import android.app.Application
import android.content.Intent
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
    val images: Boolean = true,
    val videos: Boolean = true,
    val audio: Boolean = true,
    val downloads: Boolean = true
)

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = BackupPrefs(app)
    private val scanner = MediaScanner(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(
        BackupUiState(
            driveConnected = DriveBackup(app).isConnected(),
            wifiOnly = prefs.wifiOnly,
            autoBackup = prefs.autoBackup,
            deleteAfterVerified = prefs.deleteAfterVerified,
            images = prefs.images,
            videos = prefs.videos,
            audio = prefs.audio,
            downloads = prefs.downloads
        )
    )
    val state = _state.asStateFlow()

    fun setMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun scan() {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "Scanning phone media...")
            val count = scanner.scan(prefs.images, prefs.videos, prefs.audio, prefs.downloads).size
            _state.value = _state.value.copy(
                message = "Scan complete.",
                filesFound = count,
                pending = count
            )
        }
    }

    fun connectDrive(onIntent: (Intent) -> Unit) {
        val options = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
        )
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(
                com.google.api.services.drive.DriveScopes.DRIVE_FILE
            ))
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
            "images" -> prefs.images = value
            "videos" -> prefs.videos = value
            "audio" -> prefs.audio = value
            "downloads" -> prefs.downloads = value
        }
        _state.value = _state.value.copy(
            images = prefs.images, videos = prefs.videos,
            audio = prefs.audio, downloads = prefs.downloads
        )
    }

    fun startBackup() {
        if (!DriveBackup(getApplication()).isConnected()) {
            _state.value = _state.value.copy(message = "Connect Google Drive first.")
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
        workManager.enqueueUniqueWork("mobile-backup-now", ExistingWorkPolicy.KEEP, request)
    }

    private fun scheduleAutomaticBackup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(prefs.networkType())
            .build()
        val request = androidx.work.PeriodicWorkRequestBuilder<BackupWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()
        workManager.enqueueUniquePeriodicWork(
            "mobile-backup", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }
}
