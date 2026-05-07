package com.example.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
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
import java.util.concurrent.atomic.AtomicInteger

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

        /**
         * 按住说话模式的录音上限。用户松手前最多录这么久。
         * 设 60s 是为了兼顾"长指令" + "防止用户忘记松手"。
         */
        private const val HOLD_TO_TALK_MAX_DURATION_MS = 60_000L
    }

    private var apiKey: String = ""
    private var secretKey: String = ""
    private var accessToken: String? = null

    private var callback: Callback? = null
    private var isListening = false
    private var isRecording = false
    private val sessionCounter = AtomicInteger(0)

    /**
     * 当前会话是否允许 VAD 静音自动结束。
     * - true（默认，悬浮窗 / 唤醒词链路使用）：检测到静音停顿自动 stop 录音
     * - false（主界面按住说话使用）：录音直到用户主动 stop()/cancel() 或达到 maxRecordingDurationMs
     *
     * 由 start(autoStopOnSilence) 在每次启动时设置。
     */
    private var autoStopOnSilence: Boolean = true

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    /** 类级别的 scope，destroy() 时统一取消，避免每次 start() 新建独立 scope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val environmentAnalyzer = EnvironmentAnalyzer()

    /** 当前环境噪声级别，UI 在录音期间订阅。 */
    val noiseLevel: StateFlow<NoiseLevel> get() = environmentAnalyzer.noiseLevel

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

    fun isListening(): Boolean = isListening || recordingJob?.isActive == true

    /**
     * 开始语音识别（默认行为：VAD 静音自动结束）。
     * 悬浮窗、唤醒词等无键盘场景调用此重载。
     */
    fun start() = start(autoStopOnSilence = true)

    /**
     * 开始语音识别。
     *
     * @param autoStopOnSilence 是否在检测到静音时自动结束录音。
     *   - true：默认行为，VAD 静音判定到位即 stop。适合悬浮窗、唤醒词。
     *   - false：录音不会因静音被打断，仅响应外部 stop()/cancel() 或 maxRecordingDurationMs 兜底。
     *     适合主界面"按住说话"，由用户松手触发结束。
     */
    fun start(autoStopOnSilence: Boolean) {
        Log.d(TAG, "start(autoStopOnSilence=$autoStopOnSilence) called")

        if (isListening()) {
            Log.d(TAG, "Already listening")
            return
        }

        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            callback?.onError("请先在侧栏「API 配置」填入百度 Key")
            return
        }

        if (!hasRecordAudioPermission()) {
            callback?.onError("缺少录音权限")
            return
        }

        this.autoStopOnSilence = autoStopOnSilence
        val sessionId = sessionCounter.incrementAndGet()
        recordingJob = scope.launch {
            try {
                startRecordingAndRecognize(sessionId)
            } catch (e: CancellationException) {
                Log.d(TAG, "识别已取消")
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
        sessionCounter.incrementAndGet()
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
        scope.cancel()
        callback = null
    }

    /**
     * 停止录音并取消协程
     */
    private fun stopRecordingAndCancel() {
        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败（设备状态异常）", e)
        }
        try {
            record?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放录音资源失败", e)
        }
        isListening = false
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
    }

    /**
     * 开始录音并识别
     */
    private suspend fun startRecordingAndRecognize(sessionId: Int) {
        if (!isSessionActive(sessionId)) {
            return
        }

        if (!hasRecordAudioPermission()) {
            withContext(Dispatchers.Main) {
                callback?.onError("缺少录音权限")
            }
            return
        }

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

        if (!isSessionActive(sessionId)) {
            return
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

        if (!isSessionActive(sessionId)) {
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

        if (!isSessionActive(sessionId)) {
            stopRecordingAndCancel()
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
        isListening = true
        isRecording = true
        withContext(Dispatchers.Main) {
            callback?.onReady()
        }
        Log.d(TAG, "开始录音...")

        // 录音数据
        val audioData = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)

        // 环境预学习：测量噪声基底，用于动态选择 VAD 参数。最长 1200ms（4 帧预热 + 16 帧累计）。
        environmentAnalyzer.startPreLearning()
        val preLearningStart = System.currentTimeMillis()
        while (isSessionActive(sessionId) && isRecording &&
            environmentAnalyzer.isPreLearningInProgress() &&
            System.currentTimeMillis() - preLearningStart < 1200) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
                audioData.write(buffer, 0, read)
                environmentAnalyzer.processFrame(buffer, read)
            } else if (read < 0) {
                break
            }
        }
        val noiseFloorRms = environmentAnalyzer.getInitialNoiseFloorRms()
        val vadParams = selectVadParams(noiseFloorRms)
        // 按住说话模式下用独立上限，避免 QUIET 档位 30s 太短打断长指令；
        // 自动 VAD 模式仍按 vadParams 走（用户场景一般都不会录这么久）。
        val maxDurationMs = if (autoStopOnSilence) {
            vadParams.maxRecordingDurationMs
        } else {
            HOLD_TO_TALK_MAX_DURATION_MS
        }
        Log.d(TAG, "语音输入预学习完成，基底 RMS=${"%.1f".format(noiseFloorRms)}, " +
            "VAD=${vadParams}, 录音上限=${maxDurationMs}ms, autoStop=${autoStopOnSilence}")

        // 录音循环
        val startTime = System.currentTimeMillis()
        var hasSpeech = false
        var silenceStartTime = 0L

        while (isSessionActive(sessionId) && isRecording &&
            System.currentTimeMillis() - startTime < maxDurationMs) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
                audioData.write(buffer, 0, read)

                // 用 EnvironmentAnalyzer 同时算 STE / 更新基底 / 跑频域分析（驱动 noiseLevel）
                val frameResult = environmentAnalyzer.processFrame(buffer, read)
                val ste = frameResult?.ste ?: AdaptiveVad.calculateSTE(buffer, read)
                val rms = AdaptiveVad.steToRms(ste, read / 2)
                val volume = (rms / 32768 * 100).toInt().coerceIn(0, 100)

                withContext(Dispatchers.Main) {
                    callback?.onVolumeChanged(volume)
                }

                // 静音检测逻辑（动态阈值）
                // 按住说话模式（autoStopOnSilence=false）下不自动停，仅用 EnvironmentAnalyzer 测量噪声驱动 UI；
                // 录音由用户松手触发的 stop()/cancel() 或 maxRecordingDurationMs 兜底结束。
                val now = System.currentTimeMillis()
                if (rms > vadParams.energyThreshold) {
                    hasSpeech = true
                    silenceStartTime = 0L
                } else if (hasSpeech && autoStopOnSilence) {
                    if (silenceStartTime == 0L) {
                        silenceStartTime = now
                    }
                    val elapsed = now - startTime
                    if (elapsed > vadParams.minSpeechDurationMs &&
                        now - silenceStartTime >= vadParams.silenceDurationMs) {
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
        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败", e)
        }
        try {
            record?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放录音资源失败", e)
        }

        Log.d(TAG, "录音结束，数据大小: ${audioData.size()}")

        val audioBytes = audioData.toByteArray()
        if (!isSessionActive(sessionId) || !isListening) {
            Log.d(TAG, "识别已取消，跳过结果回调")
            return
        }

        if (audioBytes.isEmpty()) {
            withContext(Dispatchers.Main) {
                isListening = false
                callback?.onEnd()
                callback?.onError("未识别到语音")
            }
            return
        }

        // 发送到百度识别
        val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val result = recognize(audioBase64, audioBytes.size)

        if (!isSessionActive(sessionId)) {
            Log.d(TAG, "识别结果返回时会话已失效，丢弃结果")
            return
        }

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
                            val text = resultArray.getString(0)
                            // 百度偶尔会返回 err_no=0 + result:[""]（音频里只有噪声/哼声）
                            // 此时要当作识别失败，避免上层把空文本当命令派发
                            if (text.isNotBlank()) text else null
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

    private fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isSessionActive(sessionId: Int): Boolean {
        return sessionCounter.get() == sessionId && recordingJob?.isActive == true
    }

    private fun selectVadParams(noiseFloorRms: Double): VadParams = when {
        noiseFloorRms < 100 -> VadParams.QUIET
        noiseFloorRms < 300 -> VadParams.NORMAL
        else -> VadParams.NOISY
    }
}

/**
 * 场景自适应的 VAD 参数。环境越嘈杂，能量阈值越高、静音判定越宽松，避免噪声打断录音。
 */
private data class VadParams(
    val energyThreshold: Double,
    val minSpeechDurationMs: Long,
    val silenceDurationMs: Long,
    val maxRecordingDurationMs: Long
) {
    companion object {
        val QUIET = VadParams(300.0, 200L, 1200L, 30_000L)
        val NORMAL = VadParams(800.0, 300L, 1500L, 45_000L)
        val NOISY = VadParams(1200.0, 500L, 2500L, 60_000L)
    }
}
