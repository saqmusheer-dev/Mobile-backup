package com.limradigitals.mobilebackup

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.InputStreamContent
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.io.IOException
import java.security.MessageDigest

enum class UploadResult {
    UPLOADED,
    ALREADY_BACKED_UP
}

data class DriveFolder(val id: String, val name: String)

class DriveBackup(private val context: Context) {
    companion object {
        private const val ROOT = "Mobile Backup"
        private const val EXPORTS = "Exports"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val KEY = "mobileBackupKey"
    }

    private val transport by lazy { GoogleNetHttpTransport.newTrustedTransport() }
    private val json = GsonFactory.getDefaultInstance()
    private val folderCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun account(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun isConnected(): Boolean {
        val a = account() ?: return false
        return GoogleSignIn.hasPermissions(a, Scope(DriveScopes.DRIVE_FILE))
    }

    private val driveService: Drive by lazy { buildDrive() }

    private fun buildDrive(): Drive {
        val a = account() ?: error("Connect Google Drive first")
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = a.account
        }

        return Drive.Builder(transport, json, credential)
            .setApplicationName("Mobile Backup")
            .build()
    }

    private fun drive(): Drive = driveService

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'")

    private fun folder(d: Drive, name: String, parent: String): String {
        folderCache["$parent/$name"]?.let { return it }
        val q = "'" + parent + "' in parents and name = '" + esc(name) +
            "' and mimeType = '" + FOLDER + "' and trashed = false"

        val found = d.files().list()
            .setQ(q)
            .setSpaces("drive")
            .setPageSize(10)
            .setFields("files(id,name)")
            .execute()
            .files

        if (!found.isNullOrEmpty()) return found[0].id.also { folderCache["$parent/$name"] = it }

        return d.files().create(File().apply {
            this.name = name
            mimeType = FOLDER
            parents = listOf(parent)
        }).setFields("id").execute().id.also { folderCache["$parent/$name"] = it }
    }

    private fun categoryFolder(d: Drive, category: String, root: String): String {
        var parent = root
        category.split("/").filter { it.isNotBlank() }.forEach { part ->
            parent = folder(d, part, parent)
        }
        return parent
    }

    private fun sourceKey(item: MediaItem): String {
        val raw = item.uri.toString() + "|" + item.size + "|" + item.modifiedSeconds
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun backupKey(item: MediaItem): String = sourceKey(item)

    fun listFolders(parentId: String = "root"): List<DriveFolder> {
        if (!isConnected()) return emptyList()
        val d = drive()
        val folders = mutableListOf<DriveFolder>()
        var token: String? = null
        do {
            val page = d.files().list()
                .setQ("'" + parentId + "' in parents and mimeType = '" + FOLDER + "' and trashed = false")
                .setSpaces("drive")
                .setPageSize(1000)
                .setOrderBy("name_natural")
                .setFields("nextPageToken,files(id,name)")
                .setPageToken(token)
                .execute()
            page.files.orEmpty().forEach { file ->
                if (!file.id.isNullOrBlank() && !file.name.isNullOrBlank()) {
                    folders.add(DriveFolder(file.id, file.name))
                }
            }
            token = page.nextPageToken
        } while (!token.isNullOrBlank())
        return folders
    }

    fun createDriveFolder(name: String, parentId: String = "root"): DriveFolder {
        val clean = name.trim()
        if (clean.isBlank()) throw IOException("Enter a folder name.")
        val d = drive()
        val q = "'" + parentId + "' in parents and name = '" + esc(clean) +
            "' and mimeType = '" + FOLDER + "' and trashed = false"
        val existing = d.files().list()
            .setQ(q)
            .setSpaces("drive")
            .setPageSize(1)
            .setFields("files(id,name)")
            .execute()
            .files
            .orEmpty()
            .firstOrNull()
        if (existing?.id != null) return DriveFolder(existing.id, existing.name ?: clean)
        val created = d.files().create(File().apply {
            this.name = clean
            mimeType = FOLDER
            parents = listOf(parentId)
        }).setFields("id,name").execute()
        return DriveFolder(created.id, created.name ?: clean)
    }

    fun findExistingKeys(keys: Set<String>): Set<String> {
        if (!isConnected() || keys.isEmpty()) return emptySet()
        val d = drive()
        val result = mutableSetOf<String>()
        keys.toList().chunked(40).forEach { batch ->
            val terms = batch.joinToString(" or ") { key ->
                "appProperties has { key='" + KEY + "' and value='" + esc(key) + "' }"
            }
            var token: String? = null
            do {
                val page = d.files().list()
                    .setQ("(" + terms + ") and trashed = false")
                    .setSpaces("drive")
                    .setPageSize(1000)
                    .setFields("nextPageToken,files(appProperties)")
                    .setPageToken(token)
                    .execute()
                page.files.orEmpty().forEach { file ->
                    file.appProperties?.get(KEY)?.let { result.add(it) }
                }
                token = page.nextPageToken
            } while (!token.isNullOrBlank())
        }
        return result
    }

    fun findBackedUpKeys(): Set<String> {
        if (!isConnected()) return emptySet()
        val d = drive()
        val keys = mutableSetOf<String>()
        var token: String? = null

        do {
            val page = d.files().list()
                .setQ("appProperties has { key='" + KEY + "' } and trashed = false")
                .setSpaces("drive")
                .setPageSize(1000)
                .setFields("nextPageToken,files(appProperties)")
                .setPageToken(token)
                .execute()

            page.files.orEmpty().forEach { file ->
                file.appProperties?.get(KEY)?.let { keys.add(it) }
            }
            token = page.nextPageToken
        } while (!token.isNullOrBlank())

        return keys
    }

    private fun exists(d: Drive, key: String): Boolean {
        val q = "appProperties has { key='" + KEY + "' and value='" + key + "' } and trashed = false"
        return !d.files().list()
            .setQ(q)
            .setSpaces("drive")
            .setPageSize(1)
            .setFields("files(id)")
            .execute()
            .files
            .isNullOrEmpty()
    }

    /**
     * Resolves the final Drive parent once, before a batch starts uploading.
     * This is deliberately separate from upload() so concurrent uploads never
     * race to discover/create the same category folders.
     */
    fun resolveUploadParent(item: MediaItem, destinationParentId: String? = null): String {
        val explicit = destinationParentId?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit

        val root = folder(drive(), ROOT, "root")
        return categoryFolder(drive(), item.category, root)
    }

    fun upload(
        item: MediaItem,
        knownBackedUpKeys: Set<String>? = null,
        onProgress: ((Double) -> Unit)? = null,
        destinationParentId: String? = null,
        resolvedParentId: String? = null
    ): UploadResult {
        val d = drive()
        val key = sourceKey(item)

        if (knownBackedUpKeys?.contains(key) == true) return UploadResult.ALREADY_BACKED_UP
        if (knownBackedUpKeys == null && exists(d, key)) return UploadResult.ALREADY_BACKED_UP

        // Use the pre-resolved parent whenever the worker has prepared the batch.
        // An explicitly selected Drive folder remains the exact upload destination.
        val parent = resolvedParentId ?: resolveUploadParent(item, destinationParentId)

        val stream = context.contentResolver.openInputStream(item.uri)
            ?: throw IOException("Cannot read " + item.name)

        stream.use {
            val media = InputStreamContent(item.mimeType, it).setLength(item.size)
            val metadata = File().apply {
                name = item.name
                parents = listOf(parent)
                appProperties = mapOf(KEY to key)
            }

            val create = d.files().create(metadata, media).setFields("id,name,size")
            if (onProgress != null) {
                create.mediaHttpUploader.setProgressListener(
                    object : MediaHttpUploaderProgressListener {
                        override fun progressChanged(
                            uploader: com.google.api.client.googleapis.media.MediaHttpUploader
                        ) {
                            onProgress(uploader.progress)
                        }
                    }
                )
            }
            // Use resumable upload for every backup file. On mobile networks,
            // the final response of a direct upload can fail after most bytes
            // have already been sent. Resumable upload is designed to recover
            // from interruptions and is the safer transport for backup.
            create.mediaHttpUploader.isDirectUploadEnabled = false
            create.mediaHttpUploader.chunkSize = 8 * 1024 * 1024

            val result = create.execute()
            // Drive's successful execute() response means the upload request
            // completed. The media size field can be omitted/null in some
            // Google Drive API responses, so do not treat a missing size as
            // an upload failure. Requiring it here caused completed uploads
            // to be retried and could create duplicate Drive files.
            if (result.id.isNullOrBlank()) {
                throw IOException("Drive did not return a file ID for " + item.name)
            }
        }

        return UploadResult.UPLOADED
    }

    fun uploadUri(uri: Uri, name: String, mimeType: String): String {
        val d = drive()
        val root = folder(d, ROOT, "root")
        val parent = folder(d, EXPORTS, root)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot read export")

        stream.use {
            val metadata = File().apply {
                this.name = name
                parents = listOf(parent)
            }
            val media = InputStreamContent(mimeType, it)
            val create = d.files().create(metadata, media).setFields("id,name,size")
            create.mediaHttpUploader.isDirectUploadEnabled = false
            create.mediaHttpUploader.chunkSize = 4 * 1024 * 1024
            return create.execute().id
                ?: throw IOException("Drive did not return an export file ID")
        }
    }
}
