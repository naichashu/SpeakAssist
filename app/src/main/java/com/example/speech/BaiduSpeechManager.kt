package com.example.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 百度语音识别管理器
 * 使用 REST API 方式调用百度语音识别服务
 */
class BaiduSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "BaiduSpeechManager"

        // 百度语音 API 地址
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val ASR_URL = "https://vop.baidu.com/server_api"

        // 音频参数
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var apiKey: String = ""
    private var secretKey: String = ""
    private var accessToken: String? = null

    private var callback: Callback? = null
    private var isListening = false
    private var isRecording = false

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    interface Callback {
        fun onReady()
        fun onResult(text: String)
        fun onError(message: String)
        fun onEnd()
        fun onVolumeChanged(volume: Int)
    }

    fun setCredentials(apiKey: String, secretKey: String) {
        this.apiKey = apiKey
        this.secretKey = secretKey
    }

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun isListening(): Boolean = isListening

    /**
     * 开始语音识别
     */
    fun start() {
        Log.d(TAG, "start() called")

        if (isListening) {
            Log.d(TAG, "Already listening")
            return
        }

        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            callback?.onError("未配置 API Key")
            return
        }

        isListening = true
        callback?.onReady()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                startRecordingAndRecognize()
            } catch (e: Exception) {
                Log.e(TAG, "识别失败", e)
                withContext(Dispatchers.Main) {
                    isListening = false
                    callback?.onError("识别失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 停止录音（停止录音但继续识别）
     */
    fun stop() {
        Log.d(TAG, "stop() called")
        isRecording = false
    }

    /**
     * 取消识别
     */
    fun cancel() {
        Log.d(TAG, "cancel()")
        isListening = false
        isRecording = false
        stopRecordingAndCancel()
    }

    /**
     * 销毁
     */
    fun destroy() {
        Log.d(TAG, "destroy()")
        isListening = false
        isRecording = false
        stopRecordingAndCancel()
        callback = null
    }

    /**
     * 停止录音并取消协程
     */
    private fun stopRecordingAndCancel() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败", e)
        }
        recordingJob?.cancel()
        recordingJob = null
    }

    /**
     * 开始录音并识别
     */
    private suspend fun startRecordingAndRecognize() {
        // 获取 access token
        if (accessToken == null) {
            accessToken = getAccessToken()
            if (accessToken == null) {
                withContext(Dispatchers.Main) {
                    isListening = false
                    callback?.onError("获取 Access Token 失败")
                }
                return
            }
        }

        // 初始化录音
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        Log.d(TAG, "录音缓冲区大小: $bufferSize")

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            withContext(Dispatchers.Main) {
                isListening = false
                callback?.onError("无法获取录音缓冲区大小")
            }
            return
        }

        // 创建 AudioRecord（需要 RECORD_AUDIO 权限）
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 4
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少录音权限", e)
            withContext(Dispatchers.Main) {
                isListening = false
                callback?.onError("缺少录音权限")
            }
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            withContext(Dispatchers.Main) {
                isListening = false
                callback?.onError("录音初始化失败")
            }
            return
        }

        // 开始录音
        audioRecord?.startRecording()
        isRecording = true
        Log.d(TAG, "开始录音...")

        // 录音数据
        val audioData = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)

        // 录音循环（最长 60 秒）
        val startTime = System.currentTimeMillis()
        val maxDuration = 60_000L

        // 静音检测参数（VAD）
        val silenceThreshold = 800       // 音量低于此值视为静音（RMS 值）
        val silenceDuration = 1500L       // 静音持续 1.5 秒后自动停止
        val minSpeechDuration = 300L      // 至少录到 0.3 秒有效语音才触发静音停止
        var hasSpeech = false             // 是否检测到过有效语音
        var silenceStartTime = 0L         // 静音开始时间

        while (isRecording && System.currentTimeMillis() - startTime < maxDuration) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
                audioData.write(buffer, 0, read)

                // 计算音量（RMS）
                var sum = 0L
                for (i in 0 until read step 2) {
                    if (i + 1 < read) {
                        val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                        sum += sample.toLong() * sample.toLong()
                    }
                }
                val rms = kotlin.math.sqrt(sum.toDouble() / (read / 2))
                val volume = (rms / 32768 * 100).toInt().coerceIn(0, 100)

                withContext(Dispatchers.Main) {
                    callback?.onVolumeChanged(volume)
                }

                // 静音检测逻辑
                val now = System.currentTimeMillis()
                if (rms > silenceThreshold) {
                    // 检测到语音
                    hasSpeech = true
                    silenceStartTime = 0L
                } else if (hasSpeech) {
                    // 有过语音后进入静音
                    if (silenceStartTime == 0L) {
                        silenceStartTime = now
                    }
                    val elapsed = now - startTime
                    if (elapsed > minSpeechDuration && now - silenceStartTime >= silenceDuration) {
                        Log.d(TAG, "检测到静音停顿，自动停止录音")
                        isRecording = false
                        break
                    }
                }
            } else if (read < 0) {
                break
            }
        }

        // 停止录音设备
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "释放录音资源失败", e)
        }

        Log.d(TAG, "录音结束，数据大小: ${audioData.size()}")

        // 发送到百度识别
        val audioBytes = audioData.toByteArray()
        val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val result = recognize(audioBase64, audioBytes.size)

        withContext(Dispatchers.Main) {
            isListening = false
            callback?.onEnd()
            if (result != null) {
                callback?.onResult(result)
            } else {
                callback?.onError("未识别到语音")
            }
        }
    }

    /**
     * 获取百度 Access Token
     */
    private fun getAccessToken(): String? {
        return try {
            val url = "$TOKEN_URL?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
            val request = Request.Builder().url(url).post(FormBody.Builder().build()).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    json.getString("access_token").also {
                        Log.d(TAG, "获取 Access Token 成功")
                    }
                } else {
                    Log.e(TAG, "获取 Token 失败: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 Access Token 失败", e)
            null
        }
    }

    /**
     * 调用百度语音识别 API
     */
    private fun recognize(audioBase64: String, audioLength: Int): String? {
        return try {
            val jsonBody = JSONObject().apply {
                put("format", "pcm")
                put("rate", 16000)
                put("channel", 1)
                put("cuid", "speakassist")
                put("token", accessToken)
                put("dev_pid", 1537)
                put("speech", audioBase64)
                put("len", audioLength)
            }

            val jsonString = jsonBody.toString()
            Log.d(TAG, "发送识别请求，音频长度: $audioLength 字节")

            val requestBody = jsonString.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(ASR_URL)
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    Log.d(TAG, "识别响应: $json")

                    val errNo = json.optInt("err_no", -1)
                    if (errNo == 0) {
                        val resultArray = json.optJSONArray("result")
                        if (resultArray != null && resultArray.length() > 0) {
                            resultArray.getString(0)
                        } else null
                    } else {
                        val errMsg = json.optString("err_msg", "未知错误")
                        Log.e(TAG, "识别错误: $errNo - $errMsg")
                        null
                    }
                } else {
                    Log.e(TAG, "识别请求失败: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            null
        }
    }
}
