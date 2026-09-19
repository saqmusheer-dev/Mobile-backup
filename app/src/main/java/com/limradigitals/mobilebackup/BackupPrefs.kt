package com.limradigitals.mobilebackup

import android.content.Context
import androidx.work.NetworkType

class BackupPrefs(context: Context) {
    private val p = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)

    var wifiOnly: Boolean get() = p.getBoolean("wifiOnly", true); set(v) = p.edit().putBoolean("wifiOnly", v).apply()
    var autoBackup: Boolean get() = p.getBoolean("autoBackup", false); set(v) = p.edit().putBoolean("autoBackup", v).apply()
    var deleteAfterVerified: Boolean get() = p.getBoolean("deleteAfterVerified", false); set(v) = p.edit().putBoolean("deleteAfterVerified", v).apply()

    var phoneImages: Boolean get() = p.getBoolean("phoneImages", true); set(v) = p.edit().putBoolean("phoneImages", v).apply()
    var phoneVideos: Boolean get() = p.getBoolean("phoneVideos", true); set(v) = p.edit().putBoolean("phoneVideos", v).apply()
    var phoneAudio: Boolean get() = p.getBoolean("phoneAudio", true); set(v) = p.edit().putBoolean("phoneAudio", v).apply()
    var phoneDocuments: Boolean get() = p.getBoolean("phoneDocuments", true); set(v) = p.edit().putBoolean("phoneDocuments", v).apply()

    var whatsappImages: Boolean get() = p.getBoolean("whatsappImages", true); set(v) = p.edit().putBoolean("whatsappImages", v).apply()
    var whatsappVideos: Boolean get() = p.getBoolean("whatsappVideos", true); set(v) = p.edit().putBoolean("whatsappVideos", v).apply()
    var whatsappAudio: Boolean get() = p.getBoolean("whatsappAudio", true); set(v) = p.edit().putBoolean("whatsappAudio", v).apply()
    var whatsappDocuments: Boolean get() = p.getBoolean("whatsappDocuments", true); set(v) = p.edit().putBoolean("whatsappDocuments", v).apply()

    var downloads: Boolean get() = p.getBoolean("downloads", true); set(v) = p.edit().putBoolean("downloads", v).apply()

    // Backward-compatible aliases for the first test build.
    var images: Boolean get() = phoneImages; set(v) { phoneImages = v }
    var videos: Boolean get() = phoneVideos; set(v) { phoneVideos = v }
    var audio: Boolean get() = phoneAudio; set(v) { phoneAudio = v }

    var driveDestinationId: String?
        get() = p.getString("driveDestinationId", null)
        set(v) = p.edit().putString("driveDestinationId", v).apply()

    var driveDestinationName: String
        get() = p.getString("driveDestinationName", "Mobile Backup (default)") ?: "Mobile Backup (default)"
        set(v) = p.edit().putString("driveDestinationName", v).apply()

    fun networkType(): NetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
}
