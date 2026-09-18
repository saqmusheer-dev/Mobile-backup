package com.limradigitals.mobilebackup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var vm: BackupViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val driveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn
                .getSignedInAccountFromIntent(result.data)
            task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            vm.driveConnected()
        } catch (e: Exception) {
            vm.setMessage("Google Drive sign-in failed. Check the Google Cloud setup.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = BackupViewModel(application)
        requestStoragePermissions()
        setContent { MaterialTheme { BackupScreen(vm) } }
    }

    private fun requestStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupScreen(vm: BackupViewModel) {
    val state by vm.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Mobile Backup") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(18.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Phone → Google Drive", style = MaterialTheme.typography.headlineSmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                enabled = state.filesFound > 0 && state.driveConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Backup")
            }

            OutlinedButton(
                onClick = { vm.connectDrive { driveLauncher.launch(it) } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.driveConnected) "Google Drive Connected" else "Connect Google Drive")
            }

            HorizontalDivider()
            Text("Backup settings", style = MaterialTheme.typography.titleMedium)
            SettingRow("Wi-Fi only", state.wifiOnly) { vm.setWifiOnly(it) }
            SettingRow("Automatic backup", state.autoBackup) { vm.setAutoBackup(it) }
            SettingRow("Delete after verified upload", state.deleteAfterVerified) {
                vm.setDeleteAfterVerified(it)
            }

            HorizontalDivider()
            Text("Categories", style = MaterialTheme.typography.titleMedium)
            SettingRow("Images", state.images) { vm.setCategory("images", it) }
            SettingRow("Videos", state.videos) { vm.setCategory("videos", it) }
            SettingRow("Audio / MP3", state.audio) { vm.setCategory("audio", it) }
            SettingRow("Downloads", state.downloads) { vm.setCategory("downloads", it) }

            Text(
                "Deletion remains disabled in the backup worker until Android's required media-delete confirmation is handled.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
