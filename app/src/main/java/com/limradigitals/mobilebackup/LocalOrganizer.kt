package com.limradigitals.mobilebackup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

object LocalOrganizer {
    fun copyToFolder(context: Context, items: List<MediaItem>, folderName: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Local folders require Android 10 or newer.")
        }

        val cleanFolder = folderName.trim()
            .replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim()
        if (cleanFolder.isBlank()) throw IOException("Choose a folder name.")

        var copied = 0
        items.forEach { item ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/Mobile Backup/$cleanFolder"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw IOException("Cannot create " + item.name)

            try {
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        input.copyTo(output, 64 * 1024)
                    } ?: throw IOException("Cannot write " + item.name)
                } ?: throw IOException("Cannot read " + item.name)

                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, done, null, null)
                copied++
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        return copied
    }
}
