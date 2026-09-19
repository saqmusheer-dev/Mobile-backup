package com.limradigitals.mobilebackup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

data class MoveResult(val moved: Int, val failed: Int)

object LocalOrganizer {
    fun moveToFolder(context: Context, items: List<MediaItem>, folderName: String): MoveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Local folders require Android 10 or newer.")
        }

        val cleanFolder = folderName.trim()
            .replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim()
        if (cleanFolder.isBlank()) throw IOException("Choose a folder name.")

        var moved = 0
        var failed = 0

        items.forEach { item ->
            try {
                val values = ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "Download/Mobile Backup/$cleanFolder"
                    )
                }
                val updated = context.contentResolver.update(item.uri, values, null, null)
                if (updated > 0) moved++ else failed++
            } catch (_: SecurityException) {
                failed++
            } catch (_: Exception) {
                failed++
            }
        }

        return MoveResult(moved, failed)
    }
}
