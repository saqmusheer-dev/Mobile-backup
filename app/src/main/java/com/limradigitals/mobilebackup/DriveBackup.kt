package com.limradigitals.mobilebackup

import android.content.Context
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
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val KEY = "mobileBackupKey"
    }

    private val transport by lazy { GoogleNetHttpTransport.newTrustedTransport() }
    private val json = GsonFactory.getDefaultInstance()

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
        val q = "'" + parent + "' in parents and name = '" + esc(name) +
            "' and mimeType = '" + FOLDER + "' and trashed = false"

        val found = d.files().list()
            .setQ(q)
            .setSpaces("drive")
            .setPageSize(10)
            .setFields("files(id,name)")
            .execute()
            .files

        if (!found.isNullOrEmpty()) return found[0].id

        return d.files().create(File().apply {
            this.name = name
            mimeType = FOLDER
            parents = listOf(parent)
        }).setFields("id").execute().id
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

    fun upload(item: MediaItem): UploadResult {
        val d = drive()
        val key = sourceKey(item)

        if (exists(d, key)) return UploadResult.ALREADY_BACKED_UP

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
            create.mediaHttpUploader.isDirectUploadEnabled = false
            create.mediaHttpUploader.chunkSize = 4 * 1024 * 1024

            val result = create.execute()
            if (result.id == null || result.size?.toLong() != item.size) {
                throw IOException("Verification failed for " + item.name)
            }
        }

        return UploadResult.UPLOADED
    }
}
