package com.limradigitals.mobilebackup

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore

class MediaScanner(private val context: Context) {
    fun countBackupCandidates(): Int {
        val resolver = context.contentResolver
        var total = 0
        total += count(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        total += count(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        total += count(resolver, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        total += count(resolver, MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        return total
    }

    private fun count(resolver: ContentResolver, uri: android.net.Uri): Int {
        return resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null
        )?.use { it.count } ?: 0
    }
}
