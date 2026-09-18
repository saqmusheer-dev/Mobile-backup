package com.limradigitals.mobilebackup

import android.content.Context
import java.io.File

class SelectionStore(context: Context) {
    private val file = File(context.filesDir, "selected_files.txt")

    fun saveSelected(keys: Set<String>) {
        if (keys.isEmpty()) {
            file.delete()
            return
        }
        file.writeText(keys.joinToString("\n"))
    }

    fun loadSelected(): Set<String> {
        if (!file.exists()) return emptySet()
        return file.readLines().filter { it.isNotBlank() }.toSet()
    }
}
