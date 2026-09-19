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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BackupUiState(
    val message: String = "Ready. Scan your phone.",
    val filesFound: Int = 0,
    val pending: Int = 0,
    val driveConnected: Boolean = false,
    val driveAccountEmail: String? = null,
    val driveDestinationId: String? = null,
    val driveDestinationName: String = "Mobile Backup (default)",
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
    val totalBytes: Long = 0L,
    val backedUpKeys: Set<String> = emptySet(),
    val backupRunning: Boolean = false,
    val backupCompleted: Int = 0,
    val backupTotal: Int = 0,
    val backupUploaded: Int = 0,
    val backupAlready: Int = 0,
    val backupFailed: Int = 0,
    val backupCurrentName: String = "",
    val backupBytesCompleted: Long = 0L,
    val backupBytesTotal: Long = 0L,
    val lastZipUri: Uri? = null,
    val localFolders: List<String> = emptyList(),
    val organizeRunning: Boolean = false,
    val organizeTotal: Int = 0,
    val organizeCompleted: Int = 0,
    val organizeMoved: Int = 0,
    val organizeFailed: Int = 0,
    val localStorageItems: List<MediaItem> = emptyList(),
    val localStorageScanning: Boolean = false
)

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = BackupPrefs(app)
    private val selectionStore = SelectionStore(app)
    private val folderStore = LocalFolderStore(app)
    private val workManager = WorkManager.getInstance(app)
    private var observedWorkId: String? = null

    private val _state = MutableStateFlow(
        BackupUiState(
            driveConnected = DriveBackup(app).isConnected(),
            driveAccountEmail = DriveBackup(app).account()?.email,
            driveDestinationId = prefs.driveDestinationId,
            driveDestinationName = prefs.driveDestinationName,
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
            allFilesAccess = hasAllFilesAccess(),
            localFolders = folderStore.list(),
            backupCompleted = prefs.lastBackupCompleted,
            backupTotal = prefs.lastBackupTotal,
            backupUploaded = prefs.lastBackupUploaded,
            backupAlready = prefs.lastBackupAlready,
            backupFailed = prefs.lastBackupFailed,
            backupBytesCompleted = prefs.lastBackupBytesCompleted,
            backupBytesTotal = prefs.lastBackupBytesTotal,
            message = prefs.lastBackupMessage
        )
    )
    val state = _state.asStateFlow()

    private val workObserver = Observer<List<WorkInfo>> { infos ->
        // Unique work can expose more than one WorkInfo while a request is
        // being replaced. Observe the active request first; never rely on
        // firstOrNull(), because that can make progress appear to jump back.
        val work = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
            ?: infos.firstOrNull()
            ?: return@Observer

        observedWorkId = work.id.toString()
        when (work.state) {
            WorkInfo.State.ENQUEUED -> {
                _state.value = _state.value.copy(
                    backupRunning = true,
                    message = "Preparing upload... Wi-Fi or mobile data will be used automatically."
                )
            }
            WorkInfo.State.RUNNING -> {
                val completed = work.progress.getInt("completed", 0)
                val completedBytes = work.progress.getLong("completedBytes", 0L)
                val totalBytes = work.progress.getLong("totalBytes", 0L)
                val uploaded = work.progress.getInt("uploaded", 0)
                val already = work.progress.getInt("already", 0)
                val failed = work.progress.getInt("failed", 0)
                val total = work.progress.getInt("total", _state.value.selectedKeys.size)
                val name = work.progress.getString("name").orEmpty()
                val phase = work.progress.getString("phase").orEmpty()

                val message = when (phase) {
                    "uploading" -> "Uploading " +
                        (completed + 1).coerceAtMost(total) + " of " + total + " • " + name
                    "uploaded" -> "Processed " + completed + " of " + total
                    else -> "Starting backup..."
                }

                // WorkManager progress can be delivered after another
                // update. Keep the visible counters monotonic for this run.
                val safeCompleted = maxOf(_state.value.backupCompleted, completed)
                val safeUploaded = maxOf(_state.value.backupUploaded, uploaded)
                val safeAlready = maxOf(_state.value.backupAlready, already)
                val safeFailed = maxOf(_state.value.backupFailed, failed)
                val safeBytes = maxOf(_state.value.backupBytesCompleted, completedBytes)
                val safeTotal = maxOf(_state.value.backupTotal, total)
                val safeTotalBytes = maxOf(_state.value.backupBytesTotal, totalBytes)

                _state.value = _state.value.copy(
                    backupRunning = true,
                    backupCompleted = safeCompleted,
                    backupTotal = safeTotal,
                    backupUploaded = safeUploaded,
                    backupAlready = safeAlready,
                    backupFailed = safeFailed,
                    backupCurrentName = name,
                    backupBytesCompleted = safeBytes,
                    backupBytesTotal = safeTotalBytes,
                    pending = (safeTotal - safeCompleted).coerceAtLeast(0),
                    message = message
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                val completed = work.outputData.getInt("completed", _state.value.backupTotal)
                val completedBytes = work.outputData.getLong("completedBytes", _state.value.backupBytesCompleted)
                val totalBytes = work.outputData.getLong("totalBytes", _state.value.backupBytesTotal)
                val uploaded = work.outputData.getInt("uploaded", _state.value.backupUploaded)
                val already = work.outputData.getInt("already", _state.value.backupAlready)
                val failed = work.outputData.getInt("failed", _state.value.backupFailed)
                val successfulKeys = if (failed == 0) {
                    _state.value.selectedKeys
                } else {
                    emptySet()
                }

                val finalMessage = work.outputData.getString("message") ?: "Backup complete."
                _state.value = _state.value.copy(
                    backupRunning = false,
                    backupCompleted = completed,
                    backupTotal = maxOf(_state.value.backupTotal, completed),
                    backupUploaded = uploaded,
                    backupAlready = already,
                    backupFailed = failed,
                    backedUpKeys = _state.value.backedUpKeys + successfulKeys,
                    pending = 0,
                    backupCurrentName = "",
                    backupBytesCompleted = completedBytes,
                    backupBytesTotal = totalBytes,
                    message = finalMessage
                )
            }
            WorkInfo.State.FAILED -> {
                val completed = work.outputData.getInt("completed", _state.value.backupCompleted)
                val total = work.outputData.getInt("total", _state.value.backupTotal)
                val completedBytes = work.outputData.getLong("completedBytes", _state.value.backupBytesCompleted)
                val totalBytes = work.outputData.getLong("totalBytes", _state.value.backupBytesTotal)
                val uploaded = work.outputData.getInt("uploaded", _state.value.backupUploaded)
                val already = work.outputData.getInt("already", _state.value.backupAlready)
                val failed = work.outputData.getInt("failed", _state.value.backupFailed)
                _state.value = _state.value.copy(
                    backupRunning = false,
                    backupCompleted = completed,
                    backupTotal = total,
                    backupUploaded = uploaded,
                    backupAlready = already,
                    backupFailed = failed,
                    backupBytesCompleted = completedBytes,
                    backupBytesTotal = totalBytes,
                    pending = (total - completed).coerceAtLeast(0),
                    message = work.outputData.getString("message")
                        ?: "Backup failed. Please try again."
                )
            }
            WorkInfo.State.CANCELLED -> {
                _state.value = _state.value.copy(
                    backupRunning = false,
                    message = "Backup cancelled."
                )
            }
            WorkInfo.State.BLOCKED -> {
                _state.value = _state.value.copy(
                    backupRunning = true,
                    message = "Backup is waiting to start..."
                )
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
        viewModelScope.launch(Dispatchers.IO) {
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

            if (DriveBackup(getApplication()).isConnected()) {
                _state.value = _state.value.copy(message = "Checking Google Drive backup status...")
                try {
                    val backedUp = DriveBackup(getApplication()).findBackedUpKeys()
                    selectionStore.saveBackedUp(backedUp)
                    _state.value = _state.value.copy(
                        backedUpKeys = backedUp,
                        message = "Scan complete. Green checks are already backed up."
                    )
                } catch (_: Exception) {
                    _state.value = _state.value.copy(
                        message = "Scan complete. Drive status check was skipped."
                    )
                }
            }
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

    fun signOutDrive() {
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

        com.google.android.gms.auth.api.signin.GoogleSignIn
            .getClient(getApplication<Application>(), options)
            .signOut()
            .addOnCompleteListener {
                _state.value = _state.value.copy(
                    driveConnected = false,
                    driveAccountEmail = null,
                    backedUpKeys = emptySet(),
                    message = "Signed out. Connect another Google account."
                )
            }
    }

    fun toggleFile(item: MediaItem) {
        val selected = _state.value.selectedKeys.toMutableSet()
        if (!selected.add(item.selectionKey)) selected.remove(item.selectionKey)
        applySelection(selected)
    }

    fun selectAllFiles() {
        applySelection(_state.value.scannedItems.map { it.selectionKey }.toSet())
    }

    fun selectedUris(): List<Uri> =
        _state.value.scannedItems
            .filter { _state.value.selectedKeys.contains(it.selectionKey) }
            .map { it.uri }

    fun finishDeleteSelectedFiles() {
        val selected = _state.value.selectedKeys
        if (selected.isEmpty()) {
            setMessage("Select files before deleting.")
            return
        }

        val remaining = _state.value.scannedItems.filterNot {
            selected.contains(it.selectionKey)
        }

        _state.value = _state.value.copy(
            scannedItems = remaining,
            selectedKeys = emptySet(),
            selectedBytes = 0L,
            pending = 0,
            filesFound = remaining.size,
            message = selected.size.toString() +
                " file" + if (selected.size == 1) " deleted." else "s deleted."
        )
        selectionStore.saveSelected(emptySet())
    }

    fun deleteSelectedFiles() {
        val uris = selectedUris()
        if (uris.isEmpty()) {
            setMessage("Select files before deleting.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var deleted = 0
            var failed = 0
            uris.forEach { uri ->
                try {
                    val count = getApplication<Application>().contentResolver.delete(uri, null, null)
                    if (count > 0) deleted++ else failed++
                } catch (_: Exception) {
                    failed++
                }
            }
            val deletedKeys = uris.toSet()
            val remaining = _state.value.scannedItems.filterNot { deletedKeys.contains(it.uri) }
            _state.value = _state.value.copy(
                scannedItems = remaining,
                selectedKeys = emptySet(),
                selectedBytes = 0L,
                pending = 0,
                filesFound = remaining.size,
                message = if (failed == 0)
                    "$deleted file" + if (deleted == 1) " deleted." else "s deleted."
                else
                    "$deleted deleted, $failed could not be deleted."
            )
            selectionStore.saveSelected(emptySet())
        }
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

    fun setDriveDestination(id: String?, name: String) {
        prefs.driveDestinationId = id
        prefs.driveDestinationName = name
        _state.value = _state.value.copy(
            driveDestinationId = id,
            driveDestinationName = name,
            message = "Drive upload destination: " + name
        )
    }

    suspend fun listDriveFolders(parentId: String): List<DriveFolder> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            DriveBackup(getApplication()).listFolders(parentId)
        }

    suspend fun createDriveFolder(name: String, parentId: String): DriveFolder =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            DriveBackup(getApplication()).createDriveFolder(name, parentId)
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

        val total = _state.value.selectedKeys.size
        _state.value = _state.value.copy(
            backupRunning = true,
            backupCompleted = 0,
            backupTotal = total,
            backupUploaded = 0,
            backupAlready = 0,
            backupFailed = 0,
            backupCurrentName = "",
            backupBytesCompleted = 0L,
            backupBytesTotal = _state.value.scannedItems
                .filter { _state.value.selectedKeys.contains(it.selectionKey) }
                .sumOf { it.size },
            pending = total,
            message = "Starting backup as soon as a network connection is available..."
        )

        enqueueBackup()
    }

    fun cancelBackup() {
        workManager.cancelUniqueWork("mobile-backup-now")
        _state.value = _state.value.copy(
            backupRunning = false,
            message = "Backup cancelled."
        )
    }

    fun createZip() {
        val items = _state.value.scannedItems.filter {
            _state.value.selectedKeys.contains(it.selectionKey)
        }
        if (items.isEmpty()) {
            setMessage("Select files before creating a ZIP.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.value = _state.value.copy(message = "Creating ZIP backup...")
                val uri = ZipManager.createZip(getApplication(), items)
                _state.value = _state.value.copy(
                    lastZipUri = uri,
                    message = "ZIP created in Downloads/Mobile Backup."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    message = "ZIP failed: " + (e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    fun clearZipUri() {
        _state.value = _state.value.copy(lastZipUri = null)
    }

    fun saveZipTo(destination: Uri) {
        val source = _state.value.lastZipUri ?: run {
            setMessage("Create a ZIP first.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                resolver.openInputStream(source)?.use { input ->
                    resolver.openOutputStream(destination)?.use { output ->
                        input.copyTo(output, 64 * 1024)
                    } ?: throw IOException("Cannot write the selected location.")
                } ?: throw IOException("Cannot read the ZIP file.")
                _state.value = _state.value.copy(message = "ZIP saved to the selected location.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    message = "Save failed: " + (e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    fun uploadZipToDrive() {
        val uri = _state.value.lastZipUri ?: run {
            setMessage("Create a ZIP first.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.value = _state.value.copy(message = "Uploading ZIP to Google Drive...")
                DriveBackup(getApplication()).uploadUri(
                    uri,
                    "MobileBackup_" + System.currentTimeMillis() + ".zip",
                    "application/zip"
                )
                _state.value = _state.value.copy(message = "ZIP uploaded to Drive/Exports.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    message = "ZIP upload failed: " + (e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    fun beginMoveRequest() {
        if (_state.value.selectedKeys.isEmpty()) {
            _state.value = _state.value.copy(message = "Select files before organizing.")
            return
        }
        val total = _state.value.selectedKeys.size
        _state.value = _state.value.copy(
            organizeRunning = true,
            organizeTotal = total,
            organizeCompleted = 0,
            organizeMoved = 0,
            organizeFailed = 0,
            message = "Requesting Android permission to move " + total + " file" +
                if (total == 1) "..." else "s..."
        )
    }

    fun failMoveRequest(message: String) {
        _state.value = _state.value.copy(
            organizeRunning = false,
            message = message
        )
    }

    fun selectedMediaUris(): List<Uri> = _state.value.scannedItems
        .filter { _state.value.selectedKeys.contains(it.selectionKey) }
        .filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") || it.mimeType.startsWith("audio/") }
        .map { it.uri }

    fun cancelMoveRequest() {
        _state.value = _state.value.copy(
            organizeRunning = false,
            message = "Move cancelled. Android permission was not granted."
        )
    }

    fun addLocalFolder(name: String) {
        val clean = name.trim().replace(Regex("""[/\\:*?"<>|]"""), "_").trim()
        if (clean.isBlank()) {
            _state.value = _state.value.copy(message = "Enter a new folder name.")
            return
        }
        if (!folderStore.add(clean)) {
            _state.value = _state.value.copy(message = "That folder already exists.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val created = try {
                LocalOrganizer.ensureFolder(getApplication(), clean)
            } catch (_: Exception) {
                false
            }
            _state.value = _state.value.copy(
                localFolders = folderStore.list(),
                message = if (created)
                    "Folder created: Downloads/Mobile Backup/" + clean
                else
                    "Folder saved, but Android did not allow creating the physical folder yet."
            )
        }
    }

    fun scanLocalStorage() {
        if (_state.value.localStorageScanning) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(localStorageScanning = true)
            try {
                val items = MediaScanner(getApplication()).scan(
                    phoneImages = true,
                    phoneVideos = true,
                    phoneAudio = true,
                    phoneDocuments = true,
                    whatsappImages = true,
                    whatsappVideos = true,
                    whatsappAudio = true,
                    whatsappDocuments = true,
                    downloads = true
                )
                _state.value = _state.value.copy(
                    localStorageItems = items,
                    localStorageScanning = false,
                    message = "Local storage scan complete: " + items.size + " files found."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    localStorageScanning = false,
                    message = "Local storage scan failed: " + (e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    fun moveSelectedToFolder(folder: String) {
        val items = _state.value.scannedItems.filter {
            _state.value.selectedKeys.contains(it.selectionKey)
        }
        if (items.isEmpty()) {
            setMessage("Select files before organizing.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.value = _state.value.copy(
                    organizeRunning = true,
                    organizeTotal = items.size,
                    organizeCompleted = 0,
                    organizeMoved = 0,
                    organizeFailed = 0,
                    message = "Moving files into " + folder + "..."
                )
                LocalOrganizer.ensureFolder(getApplication(), folder)
                val result = LocalOrganizer.moveToFolder(
                    getApplication(),
                    items,
                    folder
                ) { completed, moved, failed ->
                    _state.value = _state.value.copy(
                        organizeCompleted = completed,
                        organizeMoved = moved,
                        organizeFailed = failed,
                        message = "Moving " + completed + " of " + items.size + " files into " + folder + "..."
                    )
                }
                val message = if (result.failed == 0) {
                    "${result.moved} files moved to Download/Mobile Backup/$folder."
                } else {
                    "${result.moved} moved, ${result.failed} could not be moved. Android may require permission to modify media from another app."
                }

                if (result.failed == 0) {
                    val movedKeys = items.map { it.selectionKey }.toSet()
                    val updatedItems = _state.value.scannedItems.map { item ->
                        if (movedKeys.contains(item.selectionKey)) {
                            item.copy(relativePath = "Download/Mobile Backup/$folder/")
                        } else item
                    }
                    _state.value = _state.value.copy(
                        message = message,
                        scannedItems = updatedItems,
                        selectedKeys = emptySet(),
                        selectedBytes = 0L,
                        pending = 0,
                        organizeRunning = false,
                        organizeCompleted = items.size,
                        organizeMoved = result.moved,
                        organizeFailed = result.failed
                    )
                } else {
                    _state.value = _state.value.copy(
                        message = message,
                        organizeRunning = false,
                        organizeCompleted = items.size,
                        organizeMoved = result.moved,
                        organizeFailed = result.failed
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    organizeRunning = false,
                    message = "Move failed: " + (e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    private fun enqueueBackup() {
        // Manual backup is user-initiated. Do not make WorkManager wait for
        // its network constraint validator; BackupWorker checks the active
        // network immediately and retries only when there is no connection.
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            // Manual backup is a user-initiated transfer. Ask WorkManager to
            // start it with the lowest practical latency while still falling
            // back safely if expedited quota is unavailable.
            .setExpedited(
                androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
            )
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
