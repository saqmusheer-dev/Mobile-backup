package com.limradigitals.mobilebackup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipManager {
    fun createZip(context: Context, items: List<MediaItem>): android.net.Uri {
        if (items.isEmpty()) throw IllegalArgumentException("No files selected")

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "MobileBackup_$stamp.zip"
        val temp = File(context.cacheDir, name)

        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            val usedNames = mutableSetOf<String>()
            items.forEach { item ->
                val base = sanitize(item.name).ifBlank { "file" }
                var entryName = base
                var n = 2
                while (!usedNames.add(entryName)) {
                    entryName = base.substringBeforeLast(".") + "_" + n +
                        if (base.contains(".")) "." + base.substringAfterLast(".") else ""
                    n++
                }

                val entry = ZipEntry(entryName)
                entry.time = item.modifiedSeconds * 1000L
                zip.putNextEntry(entry)
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    input.copyTo(zip, 64 * 1024)
                } ?: throw java.io.IOException("Cannot read " + item.name)
                zip.closeEntry()
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, name)
                put(MediaColumns.MIME_TYPE, "application/zip")
                put(MediaColumns.RELATIVE_PATH, "Download/Mobile Backup")
                put(MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw java.io.IOException("Cannot create ZIP in Downloads")

            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                } ?: throw java.io.IOException("Cannot write ZIP")
                values.clear()
                values.put(MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            } finally {
                temp.delete()
            }
        } else {
            temp.toUriCompat(context, name)
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|]"""), "_")

    private fun File.toUriCompat(context: Context, name: String): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, name)
            put(MediaColumns.MIME_TYPE, "application/zip")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Files.getContentUri("external"), values
        ) ?: throw java.io.IOException("Cannot create ZIP")
        context.contentResolver.openOutputStream(uri)?.use { output ->
            inputStream().use { input -> input.copyTo(output, 64 * 1024) }
        }
        delete()
        return uri
    }
}
