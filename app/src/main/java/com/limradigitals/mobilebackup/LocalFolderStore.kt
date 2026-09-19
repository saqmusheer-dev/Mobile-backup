package com.limradigitals.mobilebackup

import android.content.Context

class LocalFolderStore(context: Context) {
    private val prefs = context.getSharedPreferences("local_folders", Context.MODE_PRIVATE)
    private val key = "folders"
    private val defaults = listOf("Family", "Personal", "Business", "Freelance")

    fun list(): List<String> {
        val existing = prefs.getStringSet(key, null)
        if (existing == null) {
            prefs.edit().putStringSet(key, defaults.toSet()).apply()
            return defaults
        }
        return existing.toList().sorted()
    }

    fun add(name: String): Boolean {
        val clean = name.trim().replace(Regex("""[/\\:*?"<>|]"""), "_")
        if (clean.isBlank()) return false
        val folders = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val added = folders.add(clean)
        if (added) prefs.edit().putStringSet(key, folders).apply()
        return added
    }
}
