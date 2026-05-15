package com.example.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.example.speakassist.BuildConfig
import java.util.UUID

object DeviceIdentity {
    private const val PREFS_NAME = "device_identity"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var cached: Identity? = null

    @JvmStatic
    fun get(context: Context): Identity {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var uuid = prefs.getString(KEY_DEVICE_ID, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, uuid).apply()
        }
        val label = buildLabel()
        return Identity(uuid, label).also { cached = it }
    }

    private fun buildLabel(): String {
        val manufacturer = Build.MANUFACTURER.ifBlank { "Unknown" }
        val model = Build.MODEL.ifBlank { "Unknown" }
        val version = Build.VERSION.RELEASE.ifBlank { "?" }
        val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        return "$manufacturer $model / Android $version / app $appVersion"
    }

    data class Identity(
        val deviceId: String,
        val deviceLabel: String,
    )
}