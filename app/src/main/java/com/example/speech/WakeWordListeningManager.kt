package com.example.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 唤醒词监听管理器
 *
 * 核心功能：
 * 1. 持续从麦克风采集音频
 * 2. 送入 Vosk 进行实时识别
 * 3. 检测是否说了唤醒词（如"小噜小噜"）
 * 4. 检测到唤醒词后，等待用户说出命令
 * 5. 命令说完后（静音检测），识别并回调结果
 *
 * 使用方式：
 * 1. init() 初始化并加载模型
 * 2. setListener() 设置回调
 * 3. startListening() 开始监听
 * 4. stopListening() 停止监听
 * 5. destroy() 释放资源
 */
class WakeWordListeningManager(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordListeningMgr"

        // 音频参数（必须与 Vosk 模型采样率一致：16kHz）
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 唤醒词（支持多个候选，增加鲁棒性）
        private val WAKE_WORDS = listOf(
            "小噜小噜",
            "小陆小陆",
            "小露小露",
            "晓露晓露",
            "小鹿小鹿",
            "小鹿 小鹿",
            "小路上",
            "小路上想不想",
            "小度小度",
            "小度 小度",
            "小猪"
        )

        // 静音检测参数
        private const val SILENCE_THRESHOLD = 500
        private const val SILENCE_DURATION_MS = 1500L
        private const val MIN_SPEECH_DURATION_MS = 300L
        private const val MAX_LISTENING_MS = 15000L

        // 命令识别阶段最大时长
        private const val MAX_COMMAND_MS = 10000L
    }

    enum class State {
        IDLE,
        WAKE_DETECTED,
        LISTENING_COMMAND,
        COMPLETED,
        ERROR
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private var voskManager: VoskRecognizerManager? = null
    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    var listener: Listener? = null

    private var isInitialized = false
    private var speechStartTime = 0L
    private var silenceStartTime = 0L
    private var hasSpeechDetected = false

    interface Listener {
        fun onWakeWordDetected()
        fun onCommandRecognized(text: String)
        fun onError(message: String)
        fun onStateChanged(state: State)
    }

    fun init(): Boolean {
        if (isInitialized) return true

        voskManager = VoskRecognizerManager(context)
        val success = voskManager?.loadModel() == true

        if (success) {
            isInitialized = true
            Log.i(TAG, "WakeWordListeningManager 初始化成功")
        } else {
            Log.e(TAG, "WakeWordListeningManager 初始化失败")
        }

        return success
    }

    fun isReady(): Boolean = isInitialized && voskManager?.isModelLoaded() == true

    fun startListening() {
        if (!isReady()) {
            Log.e(TAG, "未初始化或模型加载失败，无法开始监听")
            listener?.onError("语音引擎未就绪")
            return
        }

        if (_state.value != State.IDLE) {
            Log.w(TAG, "当前状态不是 IDLE，无法开始监听: ${_state.value}")
            return
        }

        if (listeningJob?.isActive == true) {
            Log.w(TAG, "已经在监听中")
            return
        }

        if (!checkPermission()) {
            Log.e(TAG, "缺少录音权限，无法开始监听")
            listener?.onError("缺少录音权限")
            return
        }

        Log.i(TAG, "开始监听唤醒词")
        listeningJob = scope.launch(Dispatchers.IO) {
            startWakeWordLoop()
        }
    }

    fun stopListening() {
        Log.i(TAG, "停止监听...")
        listeningJob?.cancel()
        listeningJob = null
        stopRecording()
        resetState()
        updateState(State.IDLE)
    }

    fun destroy() {
        stopListening()
        voskManager?.destroy()
        voskManager = null
        scope.cancel()
        isInitialized = false
        Log.d(TAG, "WakeWordListeningManager 已销毁")
    }

    private fun checkPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resetState() {
        speechStartTime = 0L
        silenceStartTime = 0L
        hasSpeechDetected = false
    }

    private fun updateState(newState: State) {
        if (_state.value != newState) {
            _state.value = newState
            Log.d(TAG, "状态变化: $newState")
            handler.post {
                listener?.onStateChanged(newState)
            }
        }
    }

    private suspend fun startWakeWordLoop() {
        val recognizer = voskManager?.createRecognizer() ?: run {
            handler.post { listener?.onError("无法创建语音识别器") }
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            handler.post { listener?.onError("无法获取录音缓冲区") }
            return
        }

        Log.d(TAG, "录音缓冲区大小: $bufferSize")
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 4
            )
        } catch (e: SecurityException) {
            handler.post { listener?.onError("缺少录音权限") }
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败: ${audioRecord?.state}")
            handler.post { listener?.onError("录音初始化失败") }
            return
        }

        try {
            audioRecord?.startRecording()
            Log.d(TAG, "录音已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            handler.post { listener?.onError("启动录音失败") }
            return
        }

        val buffer = ByteArray(bufferSize)
        var listeningStartTime = System.currentTimeMillis()

        Log.d(TAG, "进入唤醒词检测循环")
        updateState(State.IDLE)

        try {
            while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read <= 0) {
                    delay(10)
                    continue
                }

                recognizer.acceptWaveForm(buffer, read)
                val partialResult = voskManager?.getPartialResult().orEmpty()
                val rms = calculateRms(buffer, read)

                if (_state.value == State.IDLE) {
                    if (rms > SILENCE_THRESHOLD) {
                        speechStartTime = System.currentTimeMillis()
                        hasSpeechDetected = true
                        listeningStartTime = System.currentTimeMillis()
                    }

                    if (partialResult.isNotBlank()) {
                        Log.d(TAG, "唤醒检测 partial: $partialResult")
                        if (containsWakeWord(partialResult)) {
                            Log.i(TAG, "唤醒词检测到: $partialResult")
                            recognizer.reset()
                            hasSpeechDetected = false
                            silenceStartTime = 0L
                            listeningStartTime = System.currentTimeMillis()

                            handler.post { listener?.onWakeWordDetected() }
                            updateState(State.WAKE_DETECTED)
                        }
                    }

                    if (hasSpeechDetected && System.currentTimeMillis() - listeningStartTime > MAX_LISTENING_MS) {
                        Log.d(TAG, "唤醒词检测超时，重置识别器")
                        recognizer.reset()
                        hasSpeechDetected = false
                        listeningStartTime = System.currentTimeMillis()
                    }
                } else if (_state.value == State.WAKE_DETECTED) {
                    val elapsed = System.currentTimeMillis() - listeningStartTime

                    if (rms > SILENCE_THRESHOLD) {
                        hasSpeechDetected = true
                        silenceStartTime = 0L
                        speechStartTime = System.currentTimeMillis()
                        if (partialResult.isNotBlank()) {
                            Log.d(TAG, "命令识别 partial: $partialResult")
                        }
                    } else if (hasSpeechDetected) {
                        if (silenceStartTime == 0L) {
                            silenceStartTime = System.currentTimeMillis()
                        }

                        val silenceDuration = System.currentTimeMillis() - silenceStartTime
                        val speechDuration = speechStartTime - listeningStartTime

                        if (speechDuration >= MIN_SPEECH_DURATION_MS && silenceDuration >= SILENCE_DURATION_MS) {
                            Log.d(TAG, "检测到静音，命令输入结束")
                            val finalResult = voskManager?.getFinalResult().orEmpty()
                            Log.d(TAG, "命令识别 final: $finalResult")
                            recognizer.reset()

                            if (finalResult.isNotBlank()) {
                                val command = removeWakeWord(finalResult)
                                if (shouldDispatchCommand(finalResult, command)) {
                                    handler.post {
                                        listener?.onCommandRecognized(command)
                                    }
                                } else {
                                    Log.d(TAG, "识别结果仅包含唤醒词，忽略命令派发: raw=$finalResult, command=$command")
                                }
                            } else {
                                Log.d(TAG, "未识别到命令内容")
                            }

                            hasSpeechDetected = false
                            silenceStartTime = 0L
                            listeningStartTime = System.currentTimeMillis()
                            updateState(State.IDLE)
                        }
                    }

                    if (elapsed > MAX_COMMAND_MS) {
                        Log.d(TAG, "命令收音超时，重置")
                        recognizer.reset()
                        hasSpeechDetected = false
                        silenceStartTime = 0L
                        listeningStartTime = System.currentTimeMillis()
                        updateState(State.IDLE)
                    }
                }

                delay(10)
            }
        } catch (e: Exception) {
            Log.e(TAG, "唤醒词检测循环异常", e)
            handler.post { listener?.onError("唤醒词监听异常: ${e.message ?: "未知错误"}") }
        }

        stopRecording()
        updateState(State.IDLE)
    }

    private fun stopRecording() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "停止录音失败", e)
        }
        audioRecord = null
        Log.d(TAG, "录音已停止")
    }

    private fun calculateRms(buffer: ByteArray, read: Int): Double {
        if (read <= 0) return 0.0

        var sum = 0L
        var i = 0
        while (i + 1 < read) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val signedSample = if (sample > 32767) sample - 65536 else sample
            sum += signedSample.toLong() * signedSample.toLong()
            i += 2
        }
        val count = read / 2
        return if (count > 0) kotlin.math.sqrt(sum.toDouble() / count) else 0.0
    }

    private fun containsWakeWord(text: String): Boolean {
        val lowerText = text.lowercase(Locale.getDefault())

        for (wakeWord in WAKE_WORDS) {
            if (lowerText.contains(wakeWord.lowercase(Locale.getDefault()))) {
                return true
            }
        }

        val cleanText = text.replace(Regex("[\\s\\p{Punct}]"), "")
        for (wakeWord in WAKE_WORDS) {
            val cleanWake = wakeWord.replace(Regex("[\\s\\p{Punct}]"), "")
            if (cleanText.contains(cleanWake, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    private fun removeWakeWord(text: String): String {
        var result = text
        for (wakeWord in WAKE_WORDS) {
            result = result.replace(wakeWord, "", ignoreCase = true)
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun shouldDispatchCommand(rawText: String, command: String): Boolean {
        if (command.isBlank()) {
            return false
        }

        val normalizedRaw = normalizeForWakeWordMatch(rawText)
        val normalizedCommand = normalizeForWakeWordMatch(command)
        if (normalizedCommand.isEmpty()) {
            return false
        }

        return normalizedRaw != normalizedCommand || !isWakeWordOnly(normalizedRaw)
    }

    private fun isWakeWordOnly(normalizedText: String): Boolean {
        if (normalizedText.isBlank()) {
            return false
        }
        return WAKE_WORDS.any { normalizeForWakeWordMatch(it) == normalizedText }
    }

    private fun normalizeForWakeWordMatch(text: String): String {
        return text
            .lowercase(Locale.getDefault())
            .replace(Regex("[\\s\\p{Punct}]"), "")
            .trim()
    }
}
