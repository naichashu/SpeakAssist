package com.example.diagnostics

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the on-disk diagnostic log file for SpeakAssist.
 *
 * **Architecture note**: this object no longer spawns `logcat --pid=$pid` to read system logs.
 * Some OEM ROMs (notably vivo, observed on V2203A / Android 14) globally suppress 3rd-party app
 * logs from the kernel logcat buffer via `log.tag=M` + UID whitelisting, so reading from logcat
 * is unreliable across devices. Instead, [AppLog] calls hand log entries directly to [write], which
 * formats and persists them inside the app's own process. This bypasses any OS-level log filtering
 * because we never round-trip through the system logger.
 *
 * Public surface:
 *  - [start]: initialize storage (creates logs dir, captures previous session into `current.log.1`,
 *    cleans stale upload snapshots, opens the writer).
 *  - [write]: append a single log entry. Called by [AppLog] from any thread.
 *  - [flush] / [stop]: lifecycle.
 *  - [prepareUploadSnapshot]: snapshot the live log for upload without disturbing the writer FD.
 */
object LogcatTee {
    private const val TAG = "LogcatTee"
    private const val LOG_FILE = "current.log"
    private const val LOG_FILE_1 = "current.log.1"
    private const val SNAPSHOT_PREFIX = "current.log.upload."
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L
    private const val FLUSH_INTERVAL_MS = 1000L
    private const val FLUSH_LINES = 100

    private var writer: OutputStreamWriter? = null
    private var file: File? = null
    private var linesSinceFlush = 0
    private var lastFlushTime = 0L
    private var running = false
    private val writerLock = Any()
    private var consecutiveWriteFailures = 0

    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var currentLogDir: File? = null

    fun start(context: android.content.Context) {
        if (running) return
        running = true

        val baseDir = File(context.filesDir, "logs")
        baseDir.mkdirs()
        currentLogDir = baseDir

        cleanupStaleSnapshots(baseDir)
        capturePreviousSession(baseDir)
        rotateIfNeeded(baseDir)

        file = File(baseDir, LOG_FILE)
        try {
            val fos = FileOutputStream(file!!, true)
            writer = OutputStreamWriter(fos, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            running = false
            return
        }

        // Emit a session-start marker so a tail of the log always shows where the current
        // process began (helps when correlating with crash reports / upload reasons).
        write(Log.INFO, TAG, "=== session start pid=${android.os.Process.myPid()} ===", null)
    }

    /**
     * Append a single log entry to the on-disk log. Thread-safe.
     *
     * Format: `MM-dd HH:mm:ss.SSS PID TID L/TAG: message`, mirroring `logcat -v threadtime`
     * so existing tooling parses it without changes. Stack trace (if any) is appended after.
     */
    fun write(priority: Int, tag: String, message: String, throwable: Throwable?) {
        synchronized(writerLock) {
            val w = writer ?: return
            try {
                val ts = timestampFormat.get()!!.format(Date())
                val level = priorityLetter(priority)
                val pid = android.os.Process.myPid()
                val tid = android.os.Process.myTid()
                w.write(ts)
                w.write(" ")
                w.write(pid.toString())
                w.write(" ")
                w.write(tid.toString())
                w.write(" ")
                w.write(level)
                w.write("/")
                w.write(tag)
                w.write(": ")
                w.write(message)
                w.write("\n")
                if (throwable != null) {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    w.write(sw.toString())
                }
                linesSinceFlush++
                val now = System.currentTimeMillis()
                if (linesSinceFlush >= FLUSH_LINES || now - lastFlushTime >= FLUSH_INTERVAL_MS) {
                    w.flush()
                    linesSinceFlush = 0
                    lastFlushTime = now
                    checkSizeAndRotateLocked()
                }
                if (consecutiveWriteFailures > 0) {
                    Log.i(TAG, "writer recovered after $consecutiveWriteFailures failures")
                    consecutiveWriteFailures = 0
                }
            } catch (e: Exception) {
                consecutiveWriteFailures++
                if (consecutiveWriteFailures == 1 || consecutiveWriteFailures % 100 == 0) {
                    Log.e(TAG, "write failed (count=$consecutiveWriteFailures)", e)
                }
                if (consecutiveWriteFailures == 5) {
                    reopenWriterLocked()
                }
            }
        }
    }

    fun flush() {
        synchronized(writerLock) {
            try {
                writer?.flush()
                linesSinceFlush = 0
                lastFlushTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "flush failed", e)
            }
        }
    }

    private fun checkSizeAndRotateLocked() {
        val f = file ?: return
        if (f.length() >= MAX_FILE_BYTES) {
            rotateIfNeeded(f.parentFile ?: return)
        }
    }

    private fun reopenWriterLocked() {
        val f = file ?: return
        try {
            try { writer?.close() } catch (_: Exception) {}
            val fos = FileOutputStream(f, true)
            writer = OutputStreamWriter(fos, StandardCharsets.UTF_8)
            consecutiveWriteFailures = 0
            Log.i(TAG, "writer reopened on ${f.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "reopen failed", e)
        }
    }

    private fun capturePreviousSession(baseDir: File) {
        val current = File(baseDir, LOG_FILE)
        if (current.exists() && current.length() > 0) {
            val previous = File(baseDir, LOG_FILE_1)
            previous.delete()
            current.renameTo(previous)
        }
    }

    private fun rotateIfNeeded(baseDir: File) {
        val current = File(baseDir, LOG_FILE)
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
        synchronized(writerLock) {
            try { writer?.flush(); writer?.close() } catch (e: Exception) {}
            writer = null
        }
        file = null
    }

    /**
     * Prepares a list of log files safe to enqueue for upload.
     *
     * Returns up to two files:
     *  - `current.log.1` if it exists with content (not actively written, safe to delete after upload).
     *  - A timestamped snapshot copy of the live `current.log` (the live file itself is never returned,
     *    so `DiagnosticsUploader.processQueue()`'s post-success `delete()` cannot orphan the writer's FD).
     *
     * The caller is responsible for enqueueing every returned file. Returns empty list if no logs exist.
     */
    fun prepareUploadSnapshot(): List<File> {
        val dir = currentLogDir ?: return emptyList()
        val out = mutableListOf<File>()
        val prev = File(dir, LOG_FILE_1)
        if (prev.exists() && prev.length() > 0) {
            out += prev
        }
        val current = File(dir, LOG_FILE)
        synchronized(writerLock) {
            try {
                writer?.flush()
                linesSinceFlush = 0
                lastFlushTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "snapshot pre-flush failed", e)
            }
            if (current.exists() && current.length() > 0) {
                val snap = File(dir, "$SNAPSHOT_PREFIX${System.currentTimeMillis()}")
                try {
                    current.copyTo(snap, overwrite = true)
                    out += snap
                } catch (e: Exception) {
                    Log.e(TAG, "snapshot failed", e)
                    if (snap.exists()) snap.delete()
                }
            }
        }
        return out
    }

    private fun cleanupStaleSnapshots(baseDir: File) {
        try {
            baseDir.listFiles { f -> f.name.startsWith(SNAPSHOT_PREFIX) }?.forEach {
                if (!it.delete()) Log.w(TAG, "failed to delete stale snapshot: ${it.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "cleanupStaleSnapshots failed", e)
        }
    }

    private fun priorityLetter(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

    val isRunning: Boolean get() = running
}
