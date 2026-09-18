package com.limradigitals.mobilebackup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BackupUiState(
    val message: String = "Ready. Grant media permissions, then scan your phone.",
    val filesFound: Int = 0,
    val pending: Int = 0,
    val driveConnected: Boolean = false
)

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(BackupUiState())
    val state = _state.asStateFlow()
    private val scanner = MediaScanner(app)

    fun scan() {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "Scanning phone media...")
            val count = scanner.countBackupCandidates()
            _state.value = _state.value.copy(
                message = "Scan complete.",
                filesFound = count,
                pending = count
            )
        }
    }

    fun requestDriveSignIn() {
        _state.value = _state.value.copy(
            message = "Google Drive connection will be enabled in the next build."
        )
    }

    fun startBackup() {
        _state.value = _state.value.copy(
            message = "Google Drive upload engine is the next step."
        )
    }
}
