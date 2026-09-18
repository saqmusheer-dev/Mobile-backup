package com.limradigitals.mobilebackup

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
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
    val driveAccountEmail: String? = null,
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
    val allFilesAccess: Boolean = false,
    val scannedItems: List<MediaItem> = emptyList(),
    val selectedKeys: Set<String> = emptySet(),
    val selectedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = BackupPrefs(app)
    private val selectionStore = SelectionStore(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(
        BackupUiState(
            driveConnected = DriveBackup(app).isConnected(),
            driveAccountEmail = DriveBackup(app).account()?.email,
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

    private val workObserver = Observer<List<WorkInfo>> { infos ->
        val work = infos.firstOrNull() ?: return@Observer
        when (work.state) {
            WorkInfo.State.ENQUEUED -> {
                _state.value = _state.value.copy(
                    message = if (prefs.wifiOnly)
                        "Backup queued. Waiting for Wi-Fi..."
                    else
                        "Backup queued. Starting..."
                )
            }
            WorkInfo.State.RUNNING -> {
                val uploaded = work.progress.getInt("uploaded", 0)
                val total = work.progress.getInt("total", _state.value.selectedKeys.size)
                val name = work.progress.getString("name").orEmpty()
                val detail = if (name.isBlank()) "" else " • " + name
                _state.value = _state.value.copy(
                    message = "Backing up " + uploaded + " of " + total + detail
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                _state.value = _state.value.copy(
                    message = work.outputData.getString("message") ?: "Backup complete."
                )
            }
            WorkInfo.State.FAILED -> {
                _state.value = _state.value.copy(
                    message = work.outputData.getString("message")
                        ?: "Backup failed. Please try again."
                )
            }
            WorkInfo.State.CANCELLED -> {
                _state.value = _state.value.copy(message = "Backup cancelled.")
            }
            WorkInfo.State.BLOCKED -> {
                _state.value = _state.value.copy(message = "Backup is waiting to start...")
            }
        }
    }

    init {
        workManager.getWorkInfosForUniqueWorkLiveData("mobile-backup-now")
            .observeForever(workObserver)
    }

    override fun onCleared() {
        workManager.getWorkInfosForUniqueWorkLiveData("mobile-backup-now")
            .removeObserver(workObserver)
        super.onCleared()
    }

    fun setMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun refreshStorageAccess() {
        _state.value = _state.value.copy(
            allFilesAccess = hasAllFilesAccess(),
            driveConnected = DriveBackup(getApplication()).isConnected(),
            driveAccountEmail = DriveBackup(getApplication()).account()?.email
        )
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
            val needsAllFiles = prefs.phoneDocuments || prefs.whatsappDocuments
            val warning = if (needsAllFiles && !hasAllFilesAccess() && Build.VERSION.SDK_INT >= 30) {
                " Documents need full storage access."
            } else ""

            val items = MediaScanner(getApplication()).scan(
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
            val selected = items.map { it.selectionKey }.toSet()
            val totalBytes = items.sumOf { it.size }
            selectionStore.saveSelected(selected)

            _state.value = _state.value.copy(
                message = "Scan complete." + warning,
                filesFound = items.size,
                pending = selected.size,
                allFilesAccess = hasAllFilesAccess(),
                scannedItems = items,
                selectedKeys = selected,
                selectedBytes = totalBytes,
                totalBytes = totalBytes
            )
        }
    }

    fun connectDrive(onIntent: (Intent) -> Unit) {
        try {
            _state.value = _state.value.copy(message = "Opening Google sign-in...")
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

            val signInIntent = com.google.android.gms.auth.api.signin.GoogleSignIn
                .getClient(getApplication<Application>(), options)
                .signInIntent
            onIntent(signInIntent)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                message = "Could not open Google sign-in: " + (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    fun driveConnected(email: String? = null) {
        val accountEmail = email ?: DriveBackup(getApplication()).account()?.email
        _state.value = _state.value.copy(
            driveConnected = true,
            driveAccountEmail = accountEmail,
            message = "Google Drive connected."
        )
    }

    fun toggleFile(item: MediaItem) {
        val selected = _state.value.selectedKeys.toMutableSet()
        if (!selected.add(item.selectionKey)) selected.remove(item.selectionKey)
        applySelection(selected)
    }

    fun selectAllFiles() {
        applySelection(_state.value.scannedItems.map { it.selectionKey }.toSet())
    }

    fun clearAllFiles() {
        applySelection(emptySet())
    }

    private fun applySelection(selected: Set<String>) {
        val items = _state.value.scannedItems
        selectionStore.saveSelected(selected)
        _state.value = _state.value.copy(
            selectedKeys = selected,
            selectedBytes = items.filter { selected.contains(it.selectionKey) }.sumOf { it.size },
            pending = selected.size
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
        if (_state.value.selectedKeys.isEmpty()) {
            _state.value = _state.value.copy(message = "Select at least one file to back up.")
            return
        }

        selectionStore.saveSelected(_state.value.selectedKeys)

        if (!DriveBackup(getApplication()).isConnected()) {
            _state.value = _state.value.copy(
                driveConnected = false,
                driveAccountEmail = null,
                message = "Connect Google Drive first."
            )
            return
        }

        enqueueBackup()
        _state.value = _state.value.copy(
            message = if (prefs.wifiOnly)
                "Backup queued. Waiting for Wi-Fi..."
            else
                "Backup queued. Starting..."
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
            ExistingWorkPolicy.REPLACE,
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
