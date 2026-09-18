package com.limradigitals.mobilebackup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {
    private lateinit var vm: BackupViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshStorageAccess() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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
                10 -> "Google setup error (code 10). Check the Android OAuth package name and SHA-1."
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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                BackupApp(vm, driveLauncher, ::shareZip)
            }
        }
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

    private fun shareZip(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ZIP backup"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupApp(
    vm: BackupViewModel,
    driveLauncher: ActivityResultLauncher<Intent>,
    shareZip: (Uri) -> Unit
) {
    val state by vm.state.collectAsState()
    var screen by remember { mutableStateOf("Backup") }

    val title = when (screen) {
        "Organize" -> "Organize files"
        "Accounts" -> "Google accounts"
        "Settings" -> "Backup settings"
        else -> "Mobile Backup"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    "Backup" to "⌂",
                    "Organize" to "▣",
                    "Accounts" to "●",
                    "Settings" to "⚙"
                ).forEach { (name, icon) ->
                    NavigationBarItem(
                        selected = screen == name,
                        onClick = { screen = name },
                        icon = { Text(icon) },
                        label = { Text(name) }
                    )
                }
            }
        }
    ) { pad ->
        when (screen) {
            "Organize" -> OrganizeScreen(state, vm, Modifier.padding(pad))
            "Accounts" -> AccountsScreen(state, vm, driveLauncher, Modifier.padding(pad))
            "Settings" -> SettingsScreen(state, vm, Modifier.padding(pad))
            else -> BackupScreenContent(state, vm, shareZip, Modifier.padding(pad))
        }
    }

    if (state.backupRunning) {
        BackupProgressDialog(state, vm)
    }
}

@Composable
private fun BackupScreenContent(
    state: BackupUiState,
    vm: BackupViewModel,
    shareZip: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Phone → Google Drive", style = MaterialTheme.typography.titleLarge)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Files found: " + state.filesFound)
                        Text("Pending: " + state.pending)
                    }
                    if (state.driveConnected && !state.driveAccountEmail.isNullOrBlank()) {
                        Text(
                            "Connected: " + state.driveAccountEmail,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item {
            Button(onClick = vm::scan, modifier = Modifier.fillMaxWidth()) {
                Text("Scan Selected Categories")
            }
        }

        if (state.scannedItems.isNotEmpty()) {
            item { FileSelectionCard(state, vm) }
        }

        if (state.backupTotal > 0) {
            item { BackupProgressCard(state) }
        }

        item {
            Button(
                onClick = vm::startBackup,
                enabled = state.selectedKeys.isNotEmpty() &&
                    state.driveConnected &&
                    !state.backupRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.backupRunning) "Backup Running…" else "Start Backup")
            }
        }

        item {
            OutlinedButton(
                onClick = vm::createZip,
                enabled = state.selectedKeys.isNotEmpty() && !state.backupRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Create ZIP from Selected Files")
            }
        }

        if (state.lastZipUri != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("ZIP backup ready", style = MaterialTheme.typography.titleMedium)
                        Text("Saved to Downloads/Mobile Backup/")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { shareZip(state.lastZipUri) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Share / Email") }
                            Button(
                                onClick = vm::uploadZipToDrive,
                                enabled = state.driveConnected,
                                modifier = Modifier.weight(1f)
                            ) { Text("Upload to Drive") }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = vm::cancelBackup,
                enabled = state.backupRunning,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel Backup") }
        }

        item {
            Text(
                "Tip: Green ✓ means the file is already verified in the connected Google Drive account.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

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
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Files to backup", style = MaterialTheme.typography.titleMedium)
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
                    val backedUp = state.backedUpKeys.contains(item.selectionKey)

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
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                item.category + " • " + formatBytes(item.size),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (backedUp) {
                            Text(
                                "✓",
                                color = Color(0xFF2E9D50),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    if (backedUp) {
                        Text(
                            "Already backed up",
                            color = Color(0xFF2E9D50),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 52.dp, bottom = 3.dp)
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun BackupProgressCard(state: BackupUiState) {
    val total = state.backupTotal.coerceAtLeast(1)
    val progress = (state.backupCompleted.toFloat() / total).coerceIn(0f, 1f)

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (state.backupRunning) "Backup in progress" else "Last backup",
                style = MaterialTheme.typography.titleMedium
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                state.backupCompleted.toString() + " of " + state.backupTotal +
                    " files • " + (progress * 100).toInt() + "%"
            )
            Text(
                state.backupUploaded.toString() + " uploaded • " +
                    state.backupAlready + " already backed up • " +
                    state.backupFailed + " failed",
                style = MaterialTheme.typography.bodySmall
            )
            if (state.backupRunning && state.backupCurrentName.isNotBlank()) {
                Text(
                    state.backupCurrentName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BackupProgressDialog(state: BackupUiState, vm: BackupViewModel) {
    val total = state.backupTotal.coerceAtLeast(1)
    val progress = (state.backupCompleted.toFloat() / total).coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Uploading in progress") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.message)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text((progress * 100).toInt().toString() + "%")
                Text(
                    state.backupCompleted.toString() + " / " +
                        state.backupTotal + " files processed"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = vm::cancelBackup) { Text("Cancel") }
        }
    )
}

@Composable
private fun OrganizeScreen(
    state: BackupUiState,
    vm: BackupViewModel,
    modifier: Modifier = Modifier
) {
    var showNewFolder by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Smart local organization", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Create visible folders under Downloads/Mobile Backup and copy your selected files there."
                    )
                    Text(
                        state.selectedKeys.size.toString() + " files currently selected",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Button(
                onClick = { showNewFolder = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create Folder") }
        }

        item {
            Button(
                onClick = { showFolderPicker = true },
                enabled = state.selectedKeys.isNotEmpty() && state.localFolders.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Organize Selected Files") }
        }

        if (state.localFolders.isEmpty()) {
            item { Text("No folders yet. Create Personal Documents, Business, Receipts, etc.") }
        } else {
            items(state.localFolders) { folder ->
                Card(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(folder) },
                        supportingContent = { Text("Download/Mobile Backup/$folder") }
                    )
                }
            }
        }

        item {
            Text(
                "This first organizer version copies files safely. Original files are not deleted.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New local folder") },
            text = {
                OutlinedTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it },
                    singleLine = true,
                    label = { Text("Folder name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addLocalFolder(newFolder)
                    newFolder = ""
                    showNewFolder = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("Cancel") }
            }
        )
    }

    if (showFolderPicker) {
        AlertDialog(
            onDismissRequest = { showFolderPicker = false },
            title = { Text("Choose folder") },
            text = {
                Column {
                    state.localFolders.forEach { folder ->
                        TextButton(
                            onClick = {
                                vm.copySelectedToFolder(folder)
                                showFolderPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(folder) }
                    }
                }
            },
            confirmButton = { }
        )
    }
}

@Composable
private fun AccountsScreen(
    state: BackupUiState,
    vm: BackupViewModel,
    driveLauncher: ActivityResultLauncher<Intent>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Google Drive account", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.driveConnected && !state.driveAccountEmail.isNullOrBlank())
                            state.driveAccountEmail!!
                        else
                            "No Google account connected."
                    )
                    Text(
                        "Sign out and connect another account to back up to a different Drive."
                    )
                }
            }
        }

        item {
            if (state.driveConnected) {
                OutlinedButton(
                    onClick = vm::signOutDrive,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign Out / Switch Account") }
            } else {
                Button(
                    onClick = { vm.connectDrive { driveLauncher.launch(it) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign in with Google") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: BackupUiState,
    vm: BackupViewModel,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { Text("Network & backup", style = MaterialTheme.typography.titleLarge) }
        item { SettingRow("Wi-Fi only", state.wifiOnly) { vm.setWifiOnly(it) } }
        item { SettingRow("Automatic backup", state.autoBackup) { vm.setAutoBackup(it) } }
        item {
            SettingRow("Delete after verified upload", state.deleteAfterVerified) {
                vm.setDeleteAfterVerified(it)
            }
        }

        item { HorizontalDivider() }
        item { Text("Phone", style = MaterialTheme.typography.titleMedium) }
        item { SettingRow("Images", state.phoneImages) { vm.setCategory("phoneImages", it) } }
        item { SettingRow("Videos", state.phoneVideos) { vm.setCategory("phoneVideos", it) } }
        item { SettingRow("Audio", state.phoneAudio) { vm.setCategory("phoneAudio", it) } }
        item { SettingRow("Documents", state.phoneDocuments) { vm.setCategory("phoneDocuments", it) } }
        item { SettingRow("Downloads", state.downloads) { vm.setCategory("downloads", it) } }

        item { HorizontalDivider() }
        item { Text("WhatsApp", style = MaterialTheme.typography.titleMedium) }
        item { SettingRow("Images", state.whatsappImages) { vm.setCategory("whatsappImages", it) } }
        item { SettingRow("Videos", state.whatsappVideos) { vm.setCategory("whatsappVideos", it) } }
        item {
            SettingRow("Audio / Voice Notes", state.whatsappAudio) {
                vm.setCategory("whatsappAudio", it)
            }
        }
        item {
            SettingRow("Documents", state.whatsappDocuments) {
                vm.setCategory("whatsappDocuments", it)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (state.phoneDocuments || state.whatsappDocuments)
        ) {
            item {
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
                                "Full storage access is required to scan documents and WhatsApp Documents."
                        )
                        if (!state.allFilesAccess) {
                            OutlinedButton(
                                onClick = vm::openAllFilesAccess,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Allow Storage Access") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
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
