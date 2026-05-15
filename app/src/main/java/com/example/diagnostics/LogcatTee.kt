package com.example.diagnostics

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object LogcatTee {
    private const val TAG = "LogcatTee"
    private const val LOG_FILE = "current.log"
    private const val LOG_FILE_1 = "current.log.1"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L
    private const val FLUSH_INTERVAL_MS = 1000L
    private const val FLUSH_LINES = 100

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var file: File? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var linesSinceFlush = 0
    private var lastFlushTime = 0L
    private var running = false

    @Volatile
    private var currentLogDir: File? = null

    fun start(context: android.content.Context) {
        if (running) return
        running = true

        val baseDir = File(context.filesDir, "logs")
        baseDir.mkdirs()
        currentLogDir = baseDir

        capturePreviousSession(baseDir)
        rotateIfNeeded(baseDir)

        file = File(baseDir, "current.log")
        try {
            val fos = FileOutputStream(file!!, true)
            writer = OutputStreamWriter(fos, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            return
        }

        val pid = android.os.Process.myPid()
        try {
            val pb = Runtime.getRuntime().exec(arrayOf("logcat", "--pid=$pid", "-v", "threadtime"))
            process = pb
            val reader = BufferedReader(InputStreamReader(pb.inputStream, StandardCharsets.UTF_8))
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            job = scope?.launch {
                try {
                    var line: String?
                    while (isActive && running) {
                        line = reader.readLine()
                        if (line == null) break
                        writeLine(line)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "reader error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "exec logcat failed", e)
            stop()
        }
    }

    private fun writeLine(line: String) {
        writer ?: return
        try {
            writer!!.write(line)
            writer!!.write("\n")
            linesSinceFlush++
            val now = System.currentTimeMillis()
            if (linesSinceFlush >= FLUSH_LINES || now - lastFlushTime >= FLUSH_INTERVAL_MS) {
                writer!!.flush()
                linesSinceFlush = 0
                lastFlushTime = now
                checkSizeAndRotate()
            }
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        }
    }

    fun flush() {
        try {
            writer?.flush()
            linesSinceFlush = 0
            lastFlushTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "flush failed", e)
        }
    }

    private fun checkSizeAndRotate() {
        val f = file ?: return
        if (f.length() >= MAX_FILE_BYTES) {
            rotateIfNeeded(f.parentFile ?: return)
        }
    }

    private fun capturePreviousSession(baseDir: File) {
        val current = File(baseDir, "current.log")
        if (current.exists() && current.length() > 0) {
            val previous = File(baseDir, LOG_FILE_1)
            previous.delete()
            if (current.renameTo(previous)) {
                Log.d(TAG, "captured previous session: ${previous.length()} bytes")
            }
        }
    }

    private fun rotateIfNeeded(baseDir: File) {
        val current = File(baseDir, "current.log")
        if (current.exists() && current.length() >= MAX_FILE_BYTES) {
            val previous = File(baseDir, LOG_FILE_1)
            previous.delete()
            current.renameTo(previous)
            try {
                val fos = FileOutputStream(current, false)
                writer?.close()
                writer = OutputStreamWriter(fos, StandardCharsets.UTF_8)
                file = current
                linesSinceFlush = 0
            } catch (e: Exception) {
                Log.e(TAG, "rotate failed", e)
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        scope?.cancel()
        try { writer?.flush(); writer?.close() } catch (e: Exception) {}
        writer = null
        process?.let {
            try { it.destroy() } catch (e: Exception) {}
        }
        process = null
        file = null
    }

    fun getLogFile(): File? {
        val prev = File(currentLogDir, LOG_FILE_1)
        return if (prev.exists()) prev else null
    }

    fun getCurrentLogFile(): File? {
        val current = File(currentLogDir, "current.log")
        return if (current.exists()) current else null
    }

    val isRunning: Boolean get() = running
}