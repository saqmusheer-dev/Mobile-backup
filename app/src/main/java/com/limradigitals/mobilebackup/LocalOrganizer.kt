package com.limradigitals.mobilebackup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

data class MoveResult(val moved: Int, val failed: Int)

object LocalOrganizer {
    private fun cleanFolderName(folderName: String): String =
        folderName.trim()
            .replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim()

    fun ensureFolder(context: Context, folderName: String): Boolean {
        val cleanFolder = cleanFolderName(folderName)
        if (cleanFolder.isBlank()) throw IOException("Choose a folder name.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            return false
        }
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(root, "Mobile Backup/$cleanFolder")
        return folder.exists() || folder.mkdirs()
    }

    fun moveToFolder(
        context: Context,
        items: List<MediaItem>,
        folderName: String,
        onProgress: (completed: Int, moved: Int, failed: Int) -> Unit = { _, _, _ -> }
    ): MoveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Local folders require Android 10 or newer.")
        }

        val cleanFolder = cleanFolderName(folderName)
        if (cleanFolder.isBlank()) throw IOException("Choose a folder name.")

        var moved = 0
        var failed = 0

        items.forEach { item ->
            try {
                val values = ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "Download/Mobile Backup/$cleanFolder/"
                    )
                }
                val updated = context.contentResolver.update(item.uri, values, null, null)
                if (updated > 0) {
                    moved++
                } else {
                    failed++
                }
            } catch (_: SecurityException) {
                failed++
            } catch (_: Exception) {
                failed++
            }
            onProgress(moved + failed, moved, failed)
        }

        return MoveResult(moved, failed)
    }
}
