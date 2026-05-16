package com.example.diagnostics

import android.util.Log

/**
 * Drop-in replacement for [android.util.Log] that also persists every entry to the on-disk
 * diagnostic log via [LogcatTee.write].
 *
 * The method signatures and return values mirror `android.util.Log` exactly, so a project-wide
 * `Log.X(` → `AppLog.X(` find-replace is sufficient. Each call:
 *   1. Forwards to `android.util.Log.X(...)` so `adb logcat` still works on devices that allow it
 *      (and so the IDE's logcat panel keeps functioning during local debugging).
 *   2. Hands the same entry to [LogcatTee.write] for in-process file persistence — this is the
 *      path that survives OEM log suppression (e.g. vivo's `log.tag=M` global filter).
 *
 * If diagnostics is off, [LogcatTee.write] returns immediately because [LogcatTee] hasn't been
 * `start()`ed. So this wrapper is safe to call regardless of the diagnostic setting.
 */
object AppLog {
    fun v(tag: String, msg: String): Int {
        LogcatTee.write(Log.VERBOSE, tag, msg, null)
        return Log.v(tag, msg)
    }

    fun v(tag: String, msg: String, tr: Throwable?): Int {
        LogcatTee.write(Log.VERBOSE, tag, msg, tr)
        return Log.v(tag, msg, tr)
    }

    fun d(tag: String, msg: String): Int {
        LogcatTee.write(Log.DEBUG, tag, msg, null)
        return Log.d(tag, msg)
    }

    fun d(tag: String, msg: String, tr: Throwable?): Int {
        LogcatTee.write(Log.DEBUG, tag, msg, tr)
        return Log.d(tag, msg, tr)
    }

    fun i(tag: String, msg: String): Int {
        LogcatTee.write(Log.INFO, tag, msg, null)
        return Log.i(tag, msg)
    }

    fun i(tag: String, msg: String, tr: Throwable?): Int {
        LogcatTee.write(Log.INFO, tag, msg, tr)
        return Log.i(tag, msg, tr)
    }

    fun w(tag: String, msg: String): Int {
        LogcatTee.write(Log.WARN, tag, msg, null)
        return Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable?): Int {
        LogcatTee.write(Log.WARN, tag, msg, tr)
        return Log.w(tag, msg, tr)
    }

    fun w(tag: String, tr: Throwable): Int {
        LogcatTee.write(Log.WARN, tag, tr.message ?: "", tr)
        return Log.w(tag, tr)
    }

    fun e(tag: String, msg: String): Int {
        LogcatTee.write(Log.ERROR, tag, msg, null)
        return Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?): Int {
        LogcatTee.write(Log.ERROR, tag, msg, tr)
        return Log.e(tag, msg, tr)
    }
}
