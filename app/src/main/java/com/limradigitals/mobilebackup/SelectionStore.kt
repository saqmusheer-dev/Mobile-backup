package com.limradigitals.mobilebackup

import android.content.Context
import java.io.File

class SelectionStore(context: Context) {
    private val file = File(context.filesDir, "selected_files.txt")
    private val backedUpFile = File(context.filesDir, "backed_up_keys.txt")

    fun saveSelected(keys: Set<String>) {
        if (keys.isEmpty()) {
            file.delete()
            return
        }
        file.writeText(keys.joinToString("\n"))
    }

    fun saveBackedUp(keys: Set<String>) {
        if (keys.isEmpty()) {
            backedUpFile.delete()
            return
        }
        backedUpFile.writeText(keys.joinToString("\n"))
    }

    fun loadBackedUp(): Set<String> {
        if (!backedUpFile.exists()) return emptySet()
        return backedUpFile.readLines().filter { it.isNotBlank() }.toSet()
    }

    fun loadSelected(): Set<String> {
        if (!file.exists()) return emptySet()
        return file.readLines().filter { it.isNotBlank() }.toSet()
    }
}
