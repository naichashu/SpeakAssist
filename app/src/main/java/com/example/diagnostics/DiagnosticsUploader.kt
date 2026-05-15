package com.example.diagnostics

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.MessageDigest

object DiagnosticsUploader {
    private const val TAG = "DiagnosticsUploader"
    private const val PREFS_NAME = "diag_upload_queue"
    private const val KEY_QUEUE = "upload_queue"
    private const val KEY_DEDUP = "dedup_seen"
    private const val MAX_QUEUE = 10
    private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var scope: CoroutineScope? = null
    private var pendingJob: Job? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    fun shutdown() {
        scope?.cancel()
        scope = null
        initialized = false
    }

    fun enqueue(context: Context, logFile: File, reason: String) {
        if (!initialized) init(context)

        val queue = loadQueue(context)
        val dedup = loadDedupMap(context)
        val identity = DeviceIdentity.get(context)

        val hash = computeHash(logFile)
        val now = System.currentTimeMillis()
        if (hash != null && dedup.containsKey(hash)) {
            val lastSeen = dedup[hash] ?: 0L
            if (now - lastSeen < DEDUP_WINDOW_MS) {
                Log.i(TAG, "skip duplicate: ${logFile.name} hash=$hash")
                return
            }
        }

        queue.removeAll { it.file == logFile.absolutePath }
        queue.add(QueueItem(logFile.absolutePath, reason, now))
        while (queue.size > MAX_QUEUE) queue.removeAt(0)
        saveQueue(context, queue)

        if (hash != null) {
            dedup[hash] = now
            pruneDedup(dedup, now)
            saveDedupMap(context, dedup)
        }

        scheduleUpload(context, identity)
    }

    private fun scheduleUpload(context: Context, identity: DeviceIdentity.Identity) {
        pendingJob?.cancel()
        pendingJob = scope?.launch {
            delay(2000)
            processQueue(context, identity)
        }
    }

    private suspend fun processQueue(context: Context, identity: DeviceIdentity.Identity) {
        val queue = loadQueue(context).toMutableList()
        val it = queue.iterator()
        while (it.hasNext()) {
            val item = it.next()
            val file = File(item.file)
            if (!file.exists()) {
                it.remove()
                continue
            }
            if (file.length() > MAX_FILE_BYTES) {
                Log.w(TAG, "skip oversized file: ${file.name} (${file.length()} bytes)")
                it.remove()
                continue
            }
            val success = upload(context, identity, file, item.reason)
            if (success) {
                file.delete()
                it.remove()
            } else {
                break
            }
        }
        saveQueue(context, queue)
    }

    private suspend fun upload(
        context: Context,
        identity: DeviceIdentity.Identity,
        file: File,
        reason: String,
    ): Boolean {
        val url = "${DiagnosticsConfig.SERVER_BASE_URL}/api/speakassist/diagnostics/upload"
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("text/plain".toMediaType()))
                .addFormDataPart("device_id", identity.deviceId)
                .addFormDataPart("device_label", identity.deviceLabel)
                .addFormDataPart("app_version", "${com.example.speakassist.BuildConfig.VERSION_NAME} (${com.example.speakassist.BuildConfig.VERSION_CODE})")
                .addFormDataPart("reason", reason)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            Log.i(TAG, "upload result: ${response.code} body=$body")
            return response.code == 200
        } catch (e: Exception) {
            Log.e(TAG, "upload failed: ${file.name}", e)
            return false
        }
    }

    private fun computeHash(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA1")
            val fis = java.io.FileInputStream(file)
            val buf = ByteArray(4096)
            val read = fis.read(buf, 0, 4096)
            fis.close()
            if (read <= 0) return null
            digest.update(buf, 0, read)
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun pruneDedup(dedup: MutableMap<String, Long>, now: Long) {
        val cutoff = now - DEDUP_WINDOW_MS * 2
        dedup.entries.removeIf { it.value < cutoff }
    }

    private fun loadQueue(context: Context): MutableList<QueueItem> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null) ?: return mutableListOf()
        return try { gson.fromJson(json, Array<QueueItem>::class.java).toMutableList() }
        catch (e: Exception) { mutableListOf() }
    }

    private fun saveQueue(context: Context, queue: List<QueueItem>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUEUE, gson.toJson(queue)).apply()
    }

    private fun loadDedupMap(context: Context): MutableMap<String, Long> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEDUP, null) ?: return mutableMapOf()
        return try {
            val map = mutableMapOf<String, Long>()
            @Suppress("UNCHECKED_CAST")
            val entries = gson.fromJson(json, Map::class.java) as? Map<String, Any>
            entries?.forEach { (k, v) -> map[k] = (v as? Number)?.toLong() ?: 0L }
            map
        } catch (e: Exception) { mutableMapOf() }
    }

    private fun saveDedupMap(context: Context, dedup: Map<String, Long>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEDUP, gson.toJson(dedup)).apply()
    }

    data class QueueItem(
        val file: String,
        val reason: String,
        val queuedAt: Long,
    )
}