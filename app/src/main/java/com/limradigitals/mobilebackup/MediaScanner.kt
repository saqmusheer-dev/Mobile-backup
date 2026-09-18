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
) {
    val selectionKey: String
        get() = uri.toString() + "|" + size + "|" + modifiedSeconds
}

class MediaScanner(private val context: Context) {
    companion object {
        private const val WHATSAPP = "Android/media/com.whatsapp/WhatsApp/Media/"
        private const val WHATSAPP_BUSINESS = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/"
    }

    fun scan(
        phoneImages: Boolean = true,
        phoneVideos: Boolean = true,
        phoneAudio: Boolean = true,
        phoneDocuments: Boolean = true,
        whatsappImages: Boolean = true,
        whatsappVideos: Boolean = true,
        whatsappAudio: Boolean = true,
        whatsappDocuments: Boolean = true,
        downloads: Boolean = true
    ): List<MediaItem> {
        val resolver = context.contentResolver
        val result = LinkedHashMap<String, MediaItem>()

        if (phoneImages) queryMedia(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Phone/Images", result) { !isWhatsAppPath(it) }
        if (phoneVideos) queryMedia(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Phone/Videos", result) { !isWhatsAppPath(it) }
        if (phoneAudio) queryMedia(resolver, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Phone/Audio", result) { !isWhatsAppPath(it) }

        if (whatsappImages) queryMedia(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "WhatsApp/Images", result) { isWhatsAppImagePath(it) }
        if (whatsappVideos) queryMedia(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "WhatsApp/Videos", result) { isWhatsAppVideoPath(it) }
        if (whatsappAudio) queryMedia(resolver, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "WhatsApp/Audio", result) { isWhatsAppAudioPath(it) }

        if (phoneDocuments || whatsappDocuments) {
            queryDocuments(resolver, result, phoneDocuments, whatsappDocuments)
        }

        if (downloads) queryDownloads(resolver, result)

        return result.values.toList()
    }

    fun countBackupCandidates(): Int = scan().size

    private fun queryMedia(
        resolver: ContentResolver,
        collection: Uri,
        category: String,
        result: MutableMap<String, MediaItem>,
        include: (String) -> Boolean
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(path) ?: ""
                if (!include(relativePath)) continue
                val uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString())
                addItem(result, MediaItem(
                    uri,
                    cursor.getString(name) ?: "unnamed",
                    cursor.getString(mime) ?: "application/octet-stream",
                    cursor.getLong(size),
                    cursor.getLong(modified),
                    category
                ))
            }
        }
    }

    private fun queryDocuments(
        resolver: ContentResolver,
        result: MutableMap<String, MediaItem>,
        phoneDocuments: Boolean,
        whatsappDocuments: Boolean
    ) {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(path) ?: ""
                val mimeType = cursor.getString(mime) ?: "application/octet-stream"
                if (mimeType.startsWith("image/") || mimeType.startsWith("video/") || mimeType.startsWith("audio/")) continue

                val isWhatsAppDoc = isWhatsAppDocumentPath(relativePath)
                val isPhoneDoc = !isWhatsAppPath(relativePath) &&
                    (relativePath.startsWith("Documents/") || relativePath.startsWith("Download/"))

                val category = when {
                    whatsappDocuments && isWhatsAppDoc -> "WhatsApp/Documents"
                    phoneDocuments && isPhoneDoc -> "Phone/Documents"
                    else -> null
                } ?: continue

                val uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString())
                addItem(result, MediaItem(
                    uri,
                    cursor.getString(name) ?: "unnamed",
                    mimeType,
                    cursor.getLong(size),
                    cursor.getLong(modified),
                    category
                ))
            }
        }
    }

    private fun queryDownloads(resolver: ContentResolver, result: MutableMap<String, MediaItem>) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(path) ?: ""
                if (isWhatsAppPath(relativePath)) continue
                val uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString())
                addItem(result, MediaItem(
                    uri,
                    cursor.getString(name) ?: "unnamed",
                    cursor.getString(mime) ?: "application/octet-stream",
                    cursor.getLong(size),
                    cursor.getLong(modified),
                    "Downloads"
                ))
            }
        }
    }

    private fun addItem(result: MutableMap<String, MediaItem>, item: MediaItem) {
        if (!result.containsKey(item.uri.toString())) result[item.uri.toString()] = item
    }

    private fun isWhatsAppPath(path: String): Boolean =
        path.startsWith(WHATSAPP) || path.startsWith(WHATSAPP_BUSINESS)

    private fun isWhatsAppImagePath(path: String): Boolean =
        path.contains("/WhatsApp Images/") || path.contains("/WhatsApp Business Images/")

    private fun isWhatsAppVideoPath(path: String): Boolean =
        path.contains("/WhatsApp Video/") || path.contains("/WhatsApp Business Video/")

    private fun isWhatsAppAudioPath(path: String): Boolean =
        path.contains("/WhatsApp Audio/") ||
        path.contains("/WhatsApp Voice Notes/") ||
        path.contains("/WhatsApp Business Audio/") ||
        path.contains("/WhatsApp Business Voice Notes/")

    private fun isWhatsAppDocumentPath(path: String): Boolean =
        path.contains("/WhatsApp Documents/") ||
        path.contains("/WhatsApp Business Documents/")
}
