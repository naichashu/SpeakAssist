package com.example.speakassist

import android.app.Application
import android.util.Log
import com.example.data.SettingsPrefs
import com.example.diagnostics.DiagnosticsUploader
import com.example.diagnostics.LogcatTee
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

class SpeakAssistApplication : Application() {
    private val TAG = "SpeakAssistApp"

    private var diagnosticsEnabled = false

    override fun onCreate() {
        super.onCreate()

        diagnosticsEnabled = runBlocking {
            SettingsPrefs.diagnosticsUploadEnabled(this@SpeakAssistApplication).first()
        }

        if (diagnosticsEnabled) {
            Log.i(TAG, "Diagnostics enabled, starting LogcatTee + Uploader")
            LogcatTee.start(this)
            DiagnosticsUploader.init(this)

            val markerFile = File(filesDir, "logs/.crash_marker")
            if (markerFile.exists()) {
                handleCrashFollowup()
            } else {
                val prevLog = LogcatTee.getLogFile()
                if (prevLog != null && prevLog.exists()) {
                    DiagnosticsUploader.enqueue(this, prevLog, "cold_start")
                }
            }
        } else {
            Log.i(TAG, "Diagnostics disabled")
        }

        registerGlobalExceptionHandler()
    }

    private fun handleCrashFollowup() {
        val markerFile = File(filesDir, "logs/.crash_marker")
        markerFile.delete()
        Log.i(TAG, "Crash marker found, uploading crash logs")

        val logDir = File(filesDir, "logs")
        val prev = File(logDir, "current.log.1")
        val current = File(logDir, "current.log")
        if (prev.exists()) {
            DiagnosticsUploader.enqueue(this, prev, "crash_followup")
        }
        if (current.exists() && current.length() > 0) {
            val copy = File(logDir, "current.log.crash")
            try {
                current.copyTo(copy, overwrite = true)
                DiagnosticsUploader.enqueue(this, copy, "crash_followup")
            } catch (e: Exception) {
                Log.e(TAG, "crash copy failed", e)
            }
        }
    }

    private fun registerGlobalExceptionHandler() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT", throwable)
            if (diagnosticsEnabled) {
                LogcatTee.flush()
                val markerFile = File(filesDir, "logs/.crash_marker")
                markerFile.parentFile?.mkdirs()
                markerFile.writeText(System.currentTimeMillis().toString())
                LogcatTee.stop()
            }
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        LogcatTee.stop()
        DiagnosticsUploader.shutdown()
    }
}