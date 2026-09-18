package com.limradigitals.mobilebackup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedSeconds: Long,
    val category: String
)

class MediaScanner(private val context: Context) {
    fun scan(images: Boolean = true, videos: Boolean = true, audio: Boolean = true, downloads: Boolean = true): List<MediaItem> {
        val resolver = context.contentResolver
        val result = LinkedHashMap<String, MediaItem>()
        if (images) query(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Images", result)
        if (videos) query(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Videos", result)
        if (audio) query(resolver, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", result)
        if (downloads) query(resolver, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Downloads", result)
        return result.values.toList()
    }

    fun countBackupCandidates(): Int = scan().size

    private fun query(resolver: ContentResolver, collection: Uri, category: String, result: MutableMap<String, MediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString())
                val key = uri.toString()
                if (!result.containsKey(key)) {
                    result[key] = MediaItem(
                        uri, cursor.getString(name) ?: "unnamed",
                        cursor.getString(mime) ?: "application/octet-stream",
                        cursor.getLong(size), cursor.getLong(modified), category
                    )
                }
            }
        }
    }
}
