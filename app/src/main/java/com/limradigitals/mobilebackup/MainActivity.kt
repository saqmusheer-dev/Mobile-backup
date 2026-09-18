package com.limradigitals.mobilebackup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissions()
        setContent {
            MaterialTheme {
                val vm: BackupViewModel = viewModel()
                BackupScreen(vm)
            }
        }
    }

    private fun requestStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

@Composable
private fun BackupScreen(vm: BackupViewModel) {
    val state by vm.state.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Mobile Backup") }) }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Phone → Google Drive", style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup status", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    Text("Files found: " + state.filesFound)
                    Text("Pending: " + state.pending)
                }
            }
            Button(onClick = vm::scan, modifier = Modifier.fillMaxWidth()) {
                Text("Scan Phone")
            }
            Button(
                onClick = vm::startBackup,
                enabled = state.filesFound > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Backup to Google Drive")
            }
            OutlinedButton(onClick = vm::requestDriveSignIn, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.driveConnected) "Google Drive Connected" else "Connect Google Drive")
            }
            HorizontalDivider()
            Text(
                "Safe mode: local files are never deleted by this test build.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
