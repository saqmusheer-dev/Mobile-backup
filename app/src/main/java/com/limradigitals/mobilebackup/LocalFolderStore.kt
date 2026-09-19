package com.limradigitals.mobilebackup

import android.content.Context
import android.os.Environment
import java.io.File

class LocalFolderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("local_folders", Context.MODE_PRIVATE)
    private val key = "folders"
    private val defaults = listOf("Family", "Personal", "Business", "Freelance")

    private fun rootFolder(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Mobile Backup"
        )

    fun list(): List<String> {
        val stored = prefs.getStringSet(key, null)?.toSet().orEmpty()
        val physical = try {
            rootFolder()
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .map { it.name }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val folders = (stored + physical + if (stored.isEmpty() && physical.isEmpty()) defaults else emptySet())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSortedSet(String.CASE_INSENSITIVE_ORDER)

        prefs.edit().putStringSet(key, folders).apply()
        return folders.toList()
    }

    fun add(name: String): Boolean {
        val clean = name.trim().replace(Regex("""[/\\:*?"<>|]"""), "_").trim()
        if (clean.isBlank()) return false

        val folders = list().toMutableSet()
        val added = folders.add(clean)
        if (added) prefs.edit().putStringSet(key, folders).apply()
        return added
    }
}
