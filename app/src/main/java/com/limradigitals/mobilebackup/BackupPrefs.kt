package com.limradigitals.mobilebackup

import android.content.Context
import androidx.work.NetworkType

class BackupPrefs(context: Context) {
    private val p = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
    var wifiOnly: Boolean get() = p.getBoolean("wifiOnly", true); set(v) = p.edit().putBoolean("wifiOnly", v).apply()
    var autoBackup: Boolean get() = p.getBoolean("autoBackup", false); set(v) = p.edit().putBoolean("autoBackup", v).apply()
    var deleteAfterVerified: Boolean get() = p.getBoolean("deleteAfterVerified", false); set(v) = p.edit().putBoolean("deleteAfterVerified", v).apply()
    var images: Boolean get() = p.getBoolean("images", true); set(v) = p.edit().putBoolean("images", v).apply()
    var videos: Boolean get() = p.getBoolean("videos", true); set(v) = p.edit().putBoolean("videos", v).apply()
    var audio: Boolean get() = p.getBoolean("audio", true); set(v) = p.edit().putBoolean("audio", v).apply()
    var downloads: Boolean get() = p.getBoolean("downloads", true); set(v) = p.edit().putBoolean("downloads", v).apply()
    fun networkType(): NetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
}
