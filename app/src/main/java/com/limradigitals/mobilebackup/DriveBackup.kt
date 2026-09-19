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

class DriveBackup(private val context: Context) {
    companion object {
        private const val ROOT = "Mobile Backup"
        private const val EXPORTS = "Exports"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val KEY = "mobileBackupKey"
    }

    private val transport by lazy { GoogleNetHttpTransport.newTrustedTransport() }
    private val json = GsonFactory.getDefaultInstance()
    private val folderCache = mutableMapOf<String, String>()

    fun account(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun isConnected(): Boolean {
        val a = account() ?: return false
        return GoogleSignIn.hasPermissions(a, Scope(DriveScopes.DRIVE_FILE))
    }

    private fun drive(): Drive {
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

    fun upload(item: MediaItem, knownBackedUpKeys: Set<String>? = null): UploadResult {
        val d = drive()
        val key = sourceKey(item)

        if (knownBackedUpKeys?.contains(key) == true) return UploadResult.ALREADY_BACKED_UP
        if (knownBackedUpKeys == null && exists(d, key)) return UploadResult.ALREADY_BACKED_UP

        val root = folder(d, ROOT, "root")
        val parent = categoryFolder(d, item.category, root)

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
            create.mediaHttpUploader.isDirectUploadEnabled = item.size <= 5L * 1024L * 1024L
            if (!create.mediaHttpUploader.isDirectUploadEnabled) {
                create.mediaHttpUploader.chunkSize = 8 * 1024 * 1024
            }

            val result = create.execute()
            if (result.id == null || result.size?.toLong() != item.size) {
                throw IOException("Verification failed for " + item.name)
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
