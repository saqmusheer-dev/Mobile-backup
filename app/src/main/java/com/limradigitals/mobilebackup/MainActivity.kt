package com.limradigitals.mobilebackup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {
    private lateinit var vm: BackupViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshStorageAccess() }

    private val driveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn
                .getSignedInAccountFromIntent(result.data)
            task.getResult(ApiException::class.java)
            vm.driveConnected(task.result.email)
        } catch (e: ApiException) {
            val message = when (e.statusCode) {
                10 -> "Google setup error (code 10). Add the Android OAuth client with the correct package name and SHA-1 in Google Cloud."
                12501 -> "Google sign-in was cancelled."
                7 -> "Google sign-in network error. Check your internet connection."
                else -> "Google sign-in failed (code " + e.statusCode + ")."
            }
            vm.setMessage(message)
        } catch (e: Exception) {
            vm.setMessage("Google Drive sign-in failed: " + (e.message ?: "unknown error"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = BackupViewModel(application)
        requestStoragePermissions()
        setContent { MaterialTheme { BackupScreen(vm, driveLauncher) } }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.refreshStorageAccess()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupScreen(
    vm: BackupViewModel,
    driveLauncher: ActivityResultLauncher<android.content.Intent>
) {
    val state by vm.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Mobile Backup") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(horizontal = 18.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text("Phone → Google Drive", style = MaterialTheme.typography.headlineSmall)

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Backup status", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    Text("Files found: " + state.filesFound)
                    Text("Pending: " + state.pending)
                    if (state.driveConnected && !state.driveAccountEmail.isNullOrBlank()) {
                        Text("Connected as: " + state.driveAccountEmail)
                    }
                }
            }

            Button(onClick = vm::scan, modifier = Modifier.fillMaxWidth()) {
                Text("Scan Selected Categories")
            }

            if (state.scannedItems.isNotEmpty()) {
                FileSelectionCard(state, vm)
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
                Text(
                    if (state.driveConnected) {
                        if (!state.driveAccountEmail.isNullOrBlank())
                            "Google Drive Connected • " + state.driveAccountEmail
                        else "Google Drive Connected"
                    } else "Connect Google Drive"
                )
            }

            HorizontalDivider()
            Text("Backup settings", style = MaterialTheme.typography.titleMedium)
            SettingRow("Wi-Fi only", state.wifiOnly) { vm.setWifiOnly(it) }
            SettingRow("Automatic backup", state.autoBackup) { vm.setAutoBackup(it) }
            SettingRow("Delete after verified upload", state.deleteAfterVerified) {
                vm.setDeleteAfterVerified(it)
            }

            HorizontalDivider()
            Text("Phone", style = MaterialTheme.typography.titleMedium)
            SettingRow("Images", state.phoneImages) { vm.setCategory("phoneImages", it) }
            SettingRow("Videos", state.phoneVideos) { vm.setCategory("phoneVideos", it) }
            SettingRow("Audio", state.phoneAudio) { vm.setCategory("phoneAudio", it) }
            SettingRow("Documents", state.phoneDocuments) { vm.setCategory("phoneDocuments", it) }
            SettingRow("Downloads", state.downloads) { vm.setCategory("downloads", it) }

            HorizontalDivider()
            Text("WhatsApp", style = MaterialTheme.typography.titleMedium)
            Text(
                "WhatsApp media is backed up separately for a clean Drive folder structure.",
                style = MaterialTheme.typography.bodySmall
            )
            SettingRow("Images", state.whatsappImages) { vm.setCategory("whatsappImages", it) }
            SettingRow("Videos", state.whatsappVideos) { vm.setCategory("whatsappVideos", it) }
            SettingRow("Audio / Voice Notes", state.whatsappAudio) {
                vm.setCategory("whatsappAudio", it)
            }
            SettingRow("Documents", state.whatsappDocuments) {
                vm.setCategory("whatsappDocuments", it)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (state.phoneDocuments || state.whatsappDocuments)
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Documents access", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.allFilesAccess)
                                "Full storage access is enabled."
                            else
                                "Android requires full storage access to scan documents and WhatsApp Documents."
                        )
                        if (!state.allFilesAccess) {
                            OutlinedButton(
                                onClick = vm::openAllFilesAccess,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Allow Storage Access")
                            }
                        }
                    }
                }
            }

            Text(
                "Deletion remains disabled until Android's required media-delete confirmation is implemented.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
@Composable
private fun FileSelectionCard(state: BackupUiState, vm: BackupViewModel) {
    var filter by remember { mutableStateOf("All") }

    val filtered = remember(state.scannedItems, filter) {
        when (filter) {
            "Phone" -> state.scannedItems.filter { it.category.startsWith("Phone/") }
            "WhatsApp" -> state.scannedItems.filter { it.category.startsWith("WhatsApp/") }
            "Downloads" -> state.scannedItems.filter { it.category == "Downloads" }
            else -> state.scannedItems
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Files to upload", style = MaterialTheme.typography.titleMedium)
            Text(
                state.selectedKeys.size.toString() + " selected • " +
                    formatBytes(state.selectedBytes) + " of " + formatBytes(state.totalBytes)
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = vm::selectAllFiles,
                    modifier = Modifier.weight(1f)
                ) { Text("Select All") }
                OutlinedButton(
                    onClick = vm::clearAllFiles,
                    modifier = Modifier.weight(1f)
                ) { Text("Clear All") }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("All", "Phone", "WhatsApp", "Downloads").forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option) }
                    )
                }
            }

            Text(
                "Showing " + filtered.size + " files",
                style = MaterialTheme.typography.bodySmall
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered, key = { it.selectionKey }) { item ->
                    val selected = state.selectedKeys.contains(item.selectionKey)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { vm.toggleFile(item) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                item.category + " • " + formatBytes(item.size),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
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
