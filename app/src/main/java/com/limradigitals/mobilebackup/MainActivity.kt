package com.limradigitals.mobilebackup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.android.gms.common.api.ApiException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var vm: BackupViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshStorageAccess() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private var pendingMoveFolder: String? = null

    private val trashWriteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            vm.finishTrashSelectedFiles()
        } else {
            vm.setMessage("Move to Trash cancelled. No files were changed.")
        }
    }

    private val moveWriteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val folder = pendingMoveFolder
        pendingMoveFolder = null
        if (result.resultCode == RESULT_OK && folder != null) {
            vm.moveSelectedToFolder(folder)
        } else {
            vm.cancelMoveRequest()
            vm.setMessage("Move cancelled. Android permission was not granted.")
        }
    }

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
            MaterialTheme(
                typography = Typography().copy(
                    bodyLarge = Typography().bodyLarge.copy(fontSize = 17.sp),
                    bodyMedium = Typography().bodyMedium.copy(fontSize = 16.sp),
                    bodySmall = Typography().bodySmall.copy(fontSize = 14.sp),
                    labelLarge = Typography().labelLarge.copy(fontSize = 15.sp),
                    labelMedium = Typography().labelMedium.copy(fontSize = 14.sp),
                    labelSmall = Typography().labelSmall.copy(fontSize = 13.sp)
                )
            ) {
                BackupApp(vm, driveLauncher, ::shareZip, ::requestMove, ::requestTrash)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.refreshStorageAccess()
    }

    private fun requestMove(folder: String) {
        val uris = vm.selectedMediaUris()
        vm.beginMoveRequest()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) {
            vm.moveSelectedToFolder(folder)
            return
        }

        try {
            pendingMoveFolder = folder
            val request = MediaStore.createWriteRequest(contentResolver, uris)
            moveWriteLauncher.launch(
                IntentSenderRequest.Builder(request.intentSender).build()
            )
        } catch (_: Exception) {
            pendingMoveFolder = null
            vm.failMoveRequest("Android could not request permission for these files.")
        }
    }

    private fun requestTrash() {
        val uris = vm.selectedUris()
        if (uris.isEmpty()) {
            vm.setMessage("Select files before moving them to Trash.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createTrashRequest(
                    contentResolver,
                    uris,
                    true
                )
                trashWriteLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build()
                )
            } catch (_: Exception) {
                vm.setMessage("Android could not open Trash for these files.")
            }
        } else {
            // Android 10 fallback: use our visible local Trash folder.
            vm.moveSelectedToFolder("Trash")
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
    shareZip: (Uri) -> Unit,
    requestMove: (String) -> Unit,
    requestTrash: () -> Unit
) {
    val state by vm.state.collectAsState()
    var screen by remember { mutableStateOf("Backup") }
    var galleryFolderRequest by remember { mutableStateOf<String?>(null) }

    val title = when (screen) {
        "Gallery" -> "Photo Gallery"
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
                    "Gallery" to "▦",
                    "Organize" to "▣",
                    "Accounts" to "●",
                    "Settings" to "⚙"
                ).forEach { (name, icon) ->
                    NavigationBarItem(
                        selected = screen == name,
                        onClick = { screen = name },
                        icon = { Text(icon, fontSize = 28.sp) },
                        label = { Text(name, fontSize = 13.sp) }
                    )
                }
            }
        }
    ) { pad ->
        when (screen) {
            "Gallery" -> GalleryScreen(state, vm, { screen = "Organize" }, galleryFolderRequest, Modifier.padding(pad))
            "Organize" -> OrganizeScreen(state, vm, requestMove, { folder -> galleryFolderRequest = folder; screen = "Gallery" }, Modifier.padding(pad))
            "Accounts" -> AccountsScreen(state, vm, driveLauncher, Modifier.padding(pad))
            "Settings" -> SettingsScreen(state, vm, Modifier.padding(pad))
            else -> BackupScreenContent(state, vm, shareZip, requestTrash, Modifier.padding(pad))
        }
    }

    if (state.backupRunning) {
        BackupProgressDialog(state, vm)
    }
}

@Composable
private fun GalleryScreen(
    state: BackupUiState,
    vm: BackupViewModel,
    onOrganize: () -> Unit,
    requestedFolder: String?,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedFolder by remember { mutableStateOf<String?>(requestedFolder) }
    var viewMode by remember { mutableStateOf(if (requestedFolder != null) "Local Storage" else "Scanned") }

    LaunchedEffect(requestedFolder) {
        if (requestedFolder != null) {
            viewMode = "Local Storage"
            selectedFolder = requestedFolder
            if (state.localStorageItems.isEmpty()) vm.scanLocalStorage()
        }
    }

    LaunchedEffect(viewMode) {
        if (viewMode == "Local Storage" && state.localStorageItems.isEmpty()) {
            vm.scanLocalStorage()
        }
    }

    val sourceItems = if (viewMode == "Local Storage") state.localStorageItems else state.scannedItems
    val images = remember(sourceItems) {
        sourceItems.filter {
            it.mimeType.startsWith("image/") || it.category.endsWith("/Images")
        }
    }

    val folders = remember(images) {
        images.groupBy { galleryFolderName(it) }
            .mapValues { it.value.size }
            .toList()
            .sortedBy { it.first.lowercase(Locale.getDefault()) }
    }

    val visibleImages = if (selectedFolder == null) {
        images
    } else {
        images.filter { galleryFolderName(it) == selectedFolder }
    }

    val selectedImages = state.selectedKeys.count { key ->
        images.any { it.selectionKey == key }
    }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Photo Gallery", style = MaterialTheme.typography.titleLarge)
                        Text("${images.size} images • $selectedImages selected")
                    }
                    Button(onClick = onOrganize, enabled = selectedImages > 0) {
                        Text("Organize")
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Scanned", "Local Storage").forEach { option ->
                        FilterChip(
                            selected = viewMode == option,
                            onClick = {
                                viewMode = option
                                selectedFolder = null
                            },
                            label = { Text(option) }
                        )
                    }
                }

                if (viewMode == "Local Storage" && state.localStorageScanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Scanning local storage…", style = MaterialTheme.typography.bodySmall)
                }

                Text(
                    if (viewMode == "Local Storage") "Local folders" else "Folders",
                    style = MaterialTheme.typography.titleMedium
                )

                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        GalleryFolderCard(
                            name = "All Images",
                            count = images.size,
                            selected = selectedFolder == null,
                            onClick = { selectedFolder = null }
                        )
                    }
                    items(folders) { (folder, count) ->
                        GalleryFolderCard(
                            name = folder,
                            count = count,
                            selected = selectedFolder == folder,
                            onClick = { selectedFolder = folder }
                        )
                    }
                }
            }
        }

        if (visibleImages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No images found in this folder.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(visibleImages, key = { it.selectionKey }) { item ->
                    GalleryTile(
                        item,
                        state.selectedKeys.contains(item.selectionKey),
                        state.backedUpKeys.contains(item.selectionKey),
                        onSelect = { vm.toggleFile(item) },
                        onOpen = {
                            viewerIndex = visibleImages.indexOfFirst {
                                it.selectionKey == item.selectionKey
                            }.takeIf { it >= 0 }
                        }
                    )
                }
            }
        }
    }

    viewerIndex?.let { startIndex ->
        ImageViewerDialog(
            images = visibleImages,
            startIndex = startIndex,
            onDismiss = { viewerIndex = null }
        )
    }
}

@Composable
private fun GalleryFolderCard(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(112.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📁", fontSize = 30.sp)
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge
            )
            Text("$count images", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GalleryTile(
    item: MediaItem,
    selected: Boolean,
    backedUp: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.uri) {
        value = kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(item.uri, Size(500, 500), null)
            } catch (_: Exception) {
                null
            }
        }
    }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }

            Surface(
                    modifier = Modifier.padding(6.dp).align(Alignment.TopEnd).size(32.dp).clickable(onClick = onSelect),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (selected) "✓" else "+", color = Color.White)
                    }
                }

            if (backedUp) {
                Surface(
                    modifier = Modifier.padding(6.dp).align(Alignment.BottomStart),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2E9D50)
                ) {
                    Text(
                        "✓ Backed up",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPreviewButton(
    item: MediaItem,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                "▶",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun VideoPreviewDialog(
    item: MediaItem,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color.Black
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    factory = { context ->
                        android.widget.VideoView(context).apply {
                            val controller = android.widget.MediaController(context)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setVideoURI(item.uri)
                            setOnPreparedListener { player ->
                                player.isLooping = false
                                start()
                            }
                        }
                    },
                    update = { view ->
                        if (view.tag != item.uri.toString()) {
                            view.tag = item.uri.toString()
                            view.setVideoURI(item.uri)
                        }
                    },
                    onRelease = { view -> view.stopPlayback() }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageViewerDialog(
    images: List<MediaItem>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return

    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startIndex.coerceIn(0, images.lastIndex),
        pageCount = { images.size }
    )

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { page ->
                    val item = images[page]
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.uri) {
                        value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.loadThumbnail(item.uri, Size(1800, 1800), null)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        } else {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(18.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) { Text("‹ Back", color = Color.White) }
                        Text(
                            "${pagerState.currentPage + 1} / ${images.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        images[pagerState.currentPage].name,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
@Composable
private fun BackupScreenContent(
    state: BackupUiState,
    vm: BackupViewModel,
    shareZip: (Uri) -> Unit,
    requestTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDrivePicker by remember { mutableStateOf(false) }
    var showTrashConfirm by remember { mutableStateOf(false) }

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
            item {
                OutlinedButton(
                    onClick = { showTrashConfirm = true },
                    enabled = state.selectedKeys.isNotEmpty() && !state.backupRunning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Move Selected to Trash") }
            }
        }

        if (state.backupTotal > 0) {
            item { BackupProgressCard(state) }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Google Drive destination", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.driveDestinationName,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Selected files will be uploaded directly into this Drive folder.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { showDrivePicker = true },
                        enabled = state.driveConnected,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select / Create Drive Folder")
                    }
                }
            }
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

    if (showDrivePicker) {
        DriveFolderPickerDialog(state, vm) { showDrivePicker = false }
    }

    if (showTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showTrashConfirm = false },
            title = { Text("Move selected files to Trash?") },
            text = {
                Text(
                    "The selected " +
                        state.selectedKeys.size +
                        " file" +
                        if (state.selectedKeys.size == 1) " will" else "s will" +
                        " be moved to Trash instead of being permanently deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTrashConfirm = false
                        requestTrash()
                    }
                ) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { showTrashConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DriveFolderPickerDialog(
    state: BackupUiState,
    vm: BackupViewModel,
    onDismiss: () -> Unit
) {
    var currentId by remember { mutableStateOf("root") }
    var currentName by remember { mutableStateOf("My Drive") }
    var stack by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var folders by remember { mutableStateOf<List<DriveFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentId) {
        loading = true
        folders = try {
            vm.listDriveFolders(currentId)
        } catch (_: Exception) {
            emptyList()
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google Drive folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stack.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val parent = stack.last()
                            stack = stack.dropLast(1)
                            currentId = parent.first
                            currentName = parent.second
                        }
                    ) { Text("← Back") }
                }

                Text(currentName, style = MaterialTheme.typography.titleMedium)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            vm.setDriveDestination(null, "Mobile Backup (default)")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Default") }

                    Button(
                        onClick = {
                            vm.setDriveDestination(currentId, currentName)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Use this folder") }
                }

                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Create folder here") }

                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (folders.isEmpty()) {
                    Text("No folders here.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            OutlinedButton(
                                onClick = {
                                    stack = stack + (currentId to currentName)
                                    currentId = folder.id
                                    currentName = folder.name
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📁  " + folder.name, modifier = Modifier.weight(1f))
                                Text("Open")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create Drive folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newFolderName.trim().isNotEmpty(),
                    onClick = {
                        val name = newFolderName.trim()
                        showCreate = false
                        newFolderName = ""
                        scope.launch {
                            try {
                                val created = vm.createDriveFolder(name, currentId)
                                vm.setDriveDestination(created.id, created.name)
                                onDismiss()
                            } catch (e: Exception) {
                                vm.setMessage(
                                    "Could not create Drive folder: " +
                                        (e.message ?: e.javaClass.simpleName)
                                )
                            }
                        }
                    }
                ) { Text("Create & use") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel") }
            }
        )
    }
}

private class AudioPreviewController {
    private var player: MediaPlayer? = null
    var playingKey by mutableStateOf<String?>(null)
        private set
    var loadingKey by mutableStateOf<String?>(null)
        private set

    fun toggle(context: android.content.Context, item: MediaItem) {
        val key = item.selectionKey

        if (playingKey == key) {
            val current = player
            if (current?.isPlaying == true) {
                current.pause()
                playingKey = null
            } else {
                current?.start()
                playingKey = key
            }
            return
        }

        release()
        loadingKey = key

        try {
            val next = MediaPlayer()
            player = next
            next.setOnPreparedListener {
                if (loadingKey == key && player === next) {
                    loadingKey = null
                    playingKey = key
                    next.start()
                } else {
                    next.release()
                }
            }
            next.setOnCompletionListener {
                if (player === next) release()
            }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) release()
                true
            }
            next.setDataSource(context, item.uri)
            next.prepareAsync()
        } catch (_: Exception) {
            release()
        }
    }

    fun release() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        playingKey = null
        loadingKey = null
    }
}

@Composable
private fun AudioPreviewButton(
    item: MediaItem,
    controller: AudioPreviewController,
    context: android.content.Context
) {
    val playing = controller.playingKey == item.selectionKey
    val loading = controller.loadingKey == item.selectionKey

    IconButton(
        onClick = { controller.toggle(context, item) },
        enabled = !loading
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                when {
                    loading -> "…"
                    playing -> "❚❚"
                    else -> "▶"
                },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun FileSelectionCard(state: BackupUiState, vm: BackupViewModel) {
    var filter by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf("Newest") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioController = remember { AudioPreviewController() }
    var videoPreviewItem by remember { mutableStateOf<MediaItem?>(null) }

    DisposableEffect(Unit) {
        onDispose { audioController.release() }
    }

    val filtered = remember(state.scannedItems, filter, sort) {
        when (filter) {
            "Phone" -> state.scannedItems.filter { it.category.startsWith("Phone/") }
            "WhatsApp" -> state.scannedItems.filter { it.category.startsWith("WhatsApp/") }
            "Downloads" -> state.scannedItems.filter { it.category == "Downloads" }
            else -> state.scannedItems
        }.let { list ->
            when (sort) {
                "Oldest" -> list.sortedBy { it.modifiedSeconds }
                "Name" -> list.sortedBy { it.name.lowercase(Locale.getDefault()) }
                else -> list.sortedByDescending { it.modifiedSeconds }
            }
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

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("Newest", "Oldest", "Name").forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { sort = option },
                        label = { Text(option) }
                    )
                }
            }

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
                                item.category + " • " + formatBytes(item.size) + " • " + formatDate(item.modifiedSeconds),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (item.mimeType.startsWith("audio/") || item.category.endsWith("/Audio")) {
                            AudioPreviewButton(item, audioController, context)
                        }
                        if (item.mimeType.startsWith("video/") || item.category.endsWith("/Videos")) {
                            VideoPreviewButton(item) { videoPreviewItem = item }
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

    videoPreviewItem?.let { item ->
        VideoPreviewDialog(
            item = item,
            onDismiss = { videoPreviewItem = null }
        )
    }
}

@Composable
private fun BackupProgressCard(state: BackupUiState) {
    val byteTotal = state.backupBytesTotal
    val progress = if (byteTotal > 0L) {
        (state.backupBytesCompleted.toDouble() / byteTotal.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else {
        (state.backupCompleted.toFloat() / state.backupTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

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
                (progress * 100).toInt().toString() + "% • " +
                    state.backupCompleted + " of " + state.backupTotal + " files • " +
                    formatBytes(state.backupBytesCompleted) + " of " +
                    formatBytes(state.backupBytesTotal)
            )
            Text(
                state.backupUploaded.toString() + " uploaded • " +
                    state.backupAlready + " already backed up • " +
                    state.backupFailed + " failed",
                style = MaterialTheme.typography.bodySmall
            )
            if (!state.backupRunning && state.backupFailed > 0 && state.message.isNotBlank()) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.backupRunning && state.backupCurrentName.isNotBlank()) {
                Text(
                    state.backupCurrentName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            val successfulTransfer = !state.backupRunning &&
                state.backupTotal > 0 &&
                state.backupFailed == 0 &&
                (state.backupUploaded + state.backupAlready) == state.backupTotal

            if (successfulTransfer) {
                Button(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = Color(0xFF2E9D50),
                        disabledContentColor = Color.White
                    )
                ) {
                    Text("✓ Successfully transferred")
                }
            }
        }
    }
}

@Composable
private fun BackupProgressDialog(state: BackupUiState, vm: BackupViewModel) {
    val byteTotal = state.backupBytesTotal
    val progress = if (byteTotal > 0L) {
        (state.backupBytesCompleted.toDouble() / byteTotal.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else {
        (state.backupCompleted.toFloat() / state.backupTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

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
                Text((progress * 100).toInt().toString() + "% of data")
                Text(
                    state.backupCompleted.toString() + " / " +
                        state.backupTotal + " files processed • " +
                        formatBytes(state.backupBytesCompleted) + " / " +
                        formatBytes(state.backupBytesTotal)
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
    requestMove: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
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
                        "Create visible folders under Downloads/Mobile Backup and move your selected files there."
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

        if (state.organizeRunning || state.organizeCompleted > 0 || state.message.contains("moved", ignoreCase = true)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (state.organizeRunning) "Moving files" else "Move complete",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (state.organizeTotal > 0) {
                            val progress = (state.organizeCompleted.toFloat() /
                                state.organizeTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                state.organizeCompleted.toString() + " / " +
                                    state.organizeTotal + " processed • " +
                                    state.organizeMoved + " moved • " +
                                    state.organizeFailed + " failed"
                            )
                        }
                        Text(state.message)
                    }
                }
            }
        }

        item {
            Button(
                onClick = { showFolderPicker = true },
                enabled = state.selectedKeys.isNotEmpty() &&
                    state.localFolders.isNotEmpty() &&
                    !state.organizeRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.organizeRunning) "Moving…" else "Move Selected Files")
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                ListItem(
                    leadingContent = { Text("🗂️", fontSize = 32.sp) },
                    headlineContent = {
                        Text("Mobile Backup", style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = { Text("Download/Mobile Backup") },
                    trailingContent = {
                        Text(
                            state.localFolders.size.toString() + " folders",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        }

        if (state.localFolders.isEmpty()) {
            item {
                Text("No folders yet. Create Personal, Business, Receipts, etc.")
            }
        } else {
            items(state.localFolders) { folder ->
                Card(
                    onClick = { onOpenFolder(folder) },
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp)
                ) {
                    ListItem(
                        leadingContent = { Text("📁", fontSize = 30.sp) },
                        headlineContent = { Text(folder, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text("Mobile Backup/$folder") },
                        trailingContent = { Text("›", fontSize = 28.sp) }
                    )
                }
            }
        }

        item {
            Text(
                "Files are moved directly into the selected folder. Android may ask for permission when moving media created by another app.",
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
                            enabled = !state.organizeRunning,
                            onClick = {
                                requestMove(folder)
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

private fun galleryFolderName(item: MediaItem): String {
    val path = item.relativePath.trim('/').trim()
    if (path.isBlank()) return item.category.substringAfterLast('/')

    val parts = path.split('/').filter { it.isNotBlank() }
    return parts.lastOrNull() ?: item.category.substringAfterLast('/')
}

private fun formatDate(seconds: Long): String {
    if (seconds <= 0) return "Unknown date"
    return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        .format(Date(seconds * 1000L))
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
