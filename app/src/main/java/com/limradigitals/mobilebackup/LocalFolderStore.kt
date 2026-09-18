package com.limradigitals.mobilebackup

import android.content.Context

class LocalFolderStore(context: Context) {
    private val prefs = context.getSharedPreferences("local_folders", Context.MODE_PRIVATE)
    private val key = "folders"

    fun list(): List<String> =
        prefs.getStringSet(key, emptySet()).orEmpty().toList().sorted()

    fun add(name: String): Boolean {
        val clean = name.trim().replace(Regex("""[/\\:*?"<>|]"""), "_")
        if (clean.isBlank()) return false
        val folders = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val added = folders.add(clean)
        if (added) prefs.edit().putStringSet(key, folders).apply()
        return added
    }
}
