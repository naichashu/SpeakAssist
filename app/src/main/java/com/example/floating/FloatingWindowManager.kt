package com.example.floating

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.example.data.SettingsPrefs
import com.example.speech.BaiduSpeechConfig
import com.example.speech.BaiduSpeechManager
import com.example.speech.WakeWordListeningManager
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮窗总管理器
 *
 * 管理圆形悬浮按钮和执行卡片的生命周期。
 * 挂载在 MyAccessibilityService 上，通过 StateFlow 与 ChatViewModel 通信。
 */
class FloatingWindowManager(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "FloatingWindowManager"
        private const val RESULT_DISPLAY_DURATION = 800L
        private const val ERROR_DISPLAY_DURATION = 1500L
    }

    private val windowManager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val speechManager = BaiduSpeechManager(service)

    private var circleView: CircleFloatingView? = null
    private var executionCardView: ExecutionCardView? = null
    private var executionCancelChipView: ExecutionCancelChipView? = null

    private var isCircleEnabled = false
    private var isTaskRunning = false
    private var overlaysSuspended = false
    private var isVoiceWakeEnabled = false
    private var executionStateJob: Job? = null
    private var postTaskCleanup: Runnable? = null
    private var wakeWordManager: WakeWordListeningManager? = null
    private var noiseLevelJob: Job? = null

    fun init() {
        speechManager.setCallback(object : BaiduSpeechManager.Callback {
            override fun onReady() {
                handler.post {
                    circleView?.showListening()
                }
                startNoiseLevelObserver()
            }

            override fun onResult(text: String) {
                stopNoiseLevelObserver()
                if (text.isBlank()) {
                    // 防御：Baidu 理论上已经把空结果转成 onError，这里兜底避免空文本派发任务
                    Log.w(TAG, "onResult 收到空文本，按错误处理")
                    handler.post {
                        circleView?.showRecognitionError("未识别到语音")
                    }
                    handler.postDelayed({
                        circleView?.collapseToIdle()
                        syncWakeWordListening()
                    }, ERROR_DISPLAY_DURATION)
                    return
                }
                handler.post {
                    circleView?.showRecognitionResult(text)
                }
                handler.postDelayed({
                    circleView?.collapseToIdle()
                    onVoiceResult(text)
                }, RESULT_DISPLAY_DURATION)
            }

            override fun onError(message: String) {
                stopNoiseLevelObserver()
                handler.post {
                    circleView?.showRecognitionError(message)
                }
                handler.postDelayed({
                    circleView?.collapseToIdle()
                    syncWakeWordListening()
                }, ERROR_DISPLAY_DURATION)
            }

            override fun onEnd() {
                stopNoiseLevelObserver()
            }

            override fun onVolumeChanged(volume: Int) {
                handler.post {
                    circleView?.pulseLogo(volume)
                }
            }
        })

        initWakeWordManager()
        observeSettings()
        observeExecutionState()
        observeBaiduCredentials()
        Log.d(TAG, "FloatingWindowManager 已初始化")
    }

    /**
     * 观察百度凭据变化，用户在 API 配置页保存后实时下发给 speechManager。
     */
    private fun observeBaiduCredentials() {
        scope.launch {
            BaiduSpeechConfig.credentialsFlow(service.applicationContext).collect { credentials ->
                speechManager.setCredentials(credentials.apiKey, credentials.secretKey)
                Log.d(TAG, "百度凭据已更新，valid=${credentials.isValid}")
            }
        }
    }

    private fun initWakeWordManager() {
        wakeWordManager = WakeWordListeningManager(service)
        if (wakeWordManager?.init() == true) {
            wakeWordManager?.listener = object : WakeWordListeningManager.Listener {
                override fun onWakeWordDetected() {
                    handler.post {
                        Log.d(TAG, "唤醒词检测到，切换到 Baidu 做命令识别")
                        // Vosk 检到唤醒词后已 break 循环；这里再显式 stop 一次，确保麦克风被释放后再给 Baidu
                        stopWakeWordListening()
                        circleView?.showListening()
                        speechManager.start()
                    }
                }

                override fun onError(message: String) {
                    handler.post {
                        Log.e(TAG, "唤醒词监听错误: $message")
                        circleView?.showRecognitionError(message)
                    }
                    handler.postDelayed({
                        circleView?.collapseToIdle()
                    }, ERROR_DISPLAY_DURATION)
                }

                override fun onStateChanged(state: WakeWordListeningManager.State) {
                    Log.d(TAG, "唤醒词监听状态: $state")
                }
            }
            Log.d(TAG, "唤醒词监听管理器初始化成功")
        } else {
            Log.w(TAG, "唤醒词监听管理器初始化失败")
        }
    }

    fun destroy() {
        hideAllOverlays()
        speechManager.destroy()
        wakeWordManager?.destroy()
        wakeWordManager = null
        scope.cancel()
        Log.d(TAG, "FloatingWindowManager 已销毁")
    }

    fun resumeOverlays() {
        handler.post {
            if (!overlaysSuspended) return@post
            overlaysSuspended = false
            if (isCircleEnabled && !isTaskRunning) {
                showCircle()
            }
            startWakeWordListening()
        }
    }

    fun suspendOverlays() {
        handler.post {
            overlaysSuspended = true
            stopWakeWordListening()
            hideAllOverlays()
        }
    }

    private fun hideAllOverlays() {
        handler.removeCallbacksAndMessages(null)
        speechManager.cancel()
        circleView?.destroy()
        circleView = null
        executionCardView?.destroy()
        executionCardView = null
        executionCancelChipView?.destroy()
        executionCancelChipView = null
        isTaskRunning = false
    }

    fun showCircle() {
        if (isTaskRunning || overlaysSuspended) return
        handler.post {
            if (circleView?.isShowing() == true) {
                return@post
            }
            circleView?.destroy()
            circleView = CircleFloatingView(service, windowManager, object : CircleFloatingView.Listener {
                override fun onCircleClicked() {
                    if (speechManager.isListening()) {
                        cancelSpeechRecognition()
                    } else {
                        stopWakeWordListening()
                        speechManager.start()
                    }
                }

                override fun onExpandedCancelClicked() {
                    cancelSpeechRecognition()
                }
            })
            circleView?.create()
            startWakeWordListening()
            Log.d(TAG, "圆形悬浮窗已显示")
        }
    }

    fun hideCircle() {
        handler.post {
            speechManager.cancel()
            circleView?.destroy()
            circleView = null
            stopWakeWordListening()
            Log.d(TAG, "圆形悬浮窗已隐藏")
        }
    }

    private fun cancelSpeechRecognition() {
        speechManager.cancel()
        // 唤醒词触发后的"听命令"阶段走的是 Vosk，speechManager 未启用；
        // 此时必须强制把 wakeWordManager 重置到 IDLE，
        // 否则它继续留在 WAKE_DETECTED 里，下一次说的唤醒词会被当命令直接派发。
        if (wakeWordManager?.state?.value != WakeWordListeningManager.State.IDLE) {
            stopWakeWordListening()
        }
        circleView?.collapseToIdle()
        syncWakeWordListening()
    }

    private fun startWakeWordListening() {
        if (!isVoiceWakeEnabled) {
            Log.d(TAG, "语音唤醒开关关闭，跳过启动监听")
            return
        }
        if (overlaysSuspended || isTaskRunning || circleView == null || speechManager.isListening()) {
            Log.d(TAG, "当前场景不允许启动唤醒监听")
            return
        }
        if (wakeWordManager?.state?.value == WakeWordListeningManager.State.IDLE) {
            wakeWordManager?.startListening()
            Log.d(TAG, "唤醒词监听已启动")
        }
    }

    private fun stopWakeWordListening() {
        wakeWordManager?.stopListening()
        Log.d(TAG, "唤醒词监听已停止")
    }

    private fun syncWakeWordListening() {
        if (isVoiceWakeEnabled && isCircleEnabled && !isTaskRunning && !overlaysSuspended && circleView != null && !speechManager.isListening()) {
            startWakeWordListening()
        } else {
            stopWakeWordListening()
        }
    }

    private fun showExecutionCard(title: String) {
        if (overlaysSuspended) return
        handler.post {
            executionCardView?.destroy()
            executionCardView = ExecutionCardView(service, windowManager)
            executionCardView?.create(title)
        }
    }

    private fun showExecutionCancelChip() {
        if (overlaysSuspended) return
        handler.post {
            executionCancelChipView?.destroy()
            executionCancelChipView = ExecutionCancelChipView(service, windowManager, object : ExecutionCancelChipView.Listener {
                override fun onCancelClicked() {
                    ChatViewModel.requestCancel()
                    executionCancelChipView?.setCancelling(true)
                }
            })
            executionCancelChipView?.create()
        }
    }

    private fun hideExecutionCancelChip() {
        handler.post {
            executionCancelChipView?.destroy()
            executionCancelChipView = null
        }
    }

    suspend fun hideForScreenshot() {
        withContext(Dispatchers.Main.immediate) {
            executionCardView?.rootView?.let { view ->
                try {
                    windowManager.removeView(view)
                    executionCardView?.markShowing(false)
                } catch (e: Exception) {
                    Log.w(TAG, "截屏前隐藏卡片失败", e)
                }
            }
            executionCancelChipView?.rootView?.let { view ->
                try {
                    windowManager.removeView(view)
                    executionCancelChipView?.markShowing(false)
                } catch (e: Exception) {
                    Log.w(TAG, "截屏前隐藏取消入口失败", e)
                }
            }
        }
    }

    suspend fun restoreAfterScreenshot() {
        withContext(Dispatchers.Main.immediate) {
            if (overlaysSuspended || !isTaskRunning) return@withContext
            val card = executionCardView
            val chip = executionCancelChipView
            card?.takeIf { !it.isShowing() }?.rootView?.let { view ->
                card.currentLayoutParams?.let { params ->
                    try {
                        windowManager.addView(view, params)
                        card.markShowing(true)
                    } catch (e: Exception) {
                        Log.w(TAG, "截屏后恢复卡片失败", e)
                    }
                }
            }
            chip?.takeIf { !it.isShowing() }?.rootView?.let { view ->
                chip.currentLayoutParams?.let { params ->
                    try {
                        windowManager.addView(view, params)
                        chip.markShowing(true)
                    } catch (e: Exception) {
                        Log.w(TAG, "截屏后恢复取消入口失败", e)
                    }
                }
            }
        }
    }

    /**
     * 手势前临时摘下取消芯片。
     *
     * 芯片默认落在屏幕右上角（TOP|END, y=56dp），而百度/微信/小红书等应用的"搜索/确定"
     * 按钮也在同一坐标带；AccessibilityService.dispatchGesture 模拟的是真实触摸、走
     * WindowManager 的窗口栈，因此模型让 AI 点搜索时会被这个 NOT_TOUCH_MODAL 但可
     * 触的芯片吃掉，落到取消上。
     *
     * 只动芯片不动执行卡片：卡片本身是 FLAG_NOT_TOUCHABLE，不吃点击。
     */
    suspend fun detachCancelChipForGesture() {
        withContext(Dispatchers.Main.immediate) {
            val chip = executionCancelChipView ?: return@withContext
            if (!chip.isShowing()) return@withContext
            chip.rootView?.let { view ->
                try {
                    windowManager.removeView(view)
                    chip.markShowing(false)
                } catch (e: Exception) {
                    Log.w(TAG, "手势前摘除取消入口失败", e)
                }
            }
        }
    }

    suspend fun reattachCancelChipAfterGesture() {
        withContext(Dispatchers.Main.immediate) {
            if (overlaysSuspended || !isTaskRunning) return@withContext
            val chip = executionCancelChipView ?: return@withContext
            if (chip.isShowing()) return@withContext
            val view = chip.rootView ?: return@withContext
            val params = chip.currentLayoutParams ?: return@withContext
            try {
                windowManager.addView(view, params)
                chip.markShowing(true)
            } catch (e: Exception) {
                Log.w(TAG, "手势后恢复取消入口失败", e)
            }
        }
    }

    private fun onVoiceResult(text: String) {
        Log.d(TAG, "语音识别结果：$text")
        scope.launch {
            try {
                val viewModel = ChatViewModel(service.application)
                val result = viewModel.executeTaskLoop(text, "autoglm-phone")
                Log.d(TAG, "任务执行完成: ${result.success} - ${result.message}")
            } catch (e: Exception) {
                Log.e(TAG, "任务执行失败", e)
                ChatViewModel.resetState()
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            SettingsPrefs.floatingWindowEnabled(service.applicationContext).collect { enabled ->
                isCircleEnabled = enabled
                if (!enabled && isVoiceWakeEnabled) {
                    SettingsPrefs.setVoiceWakeEnabled(service.applicationContext, false)
                }
                if (enabled && !isTaskRunning) {
                    showCircle()
                } else if (!enabled) {
                    hideCircle()
                }
            }
        }
        scope.launch {
            SettingsPrefs.voiceWakeEnabled(service.applicationContext).collectLatest { enabled ->
                if (enabled && !isCircleEnabled) {
                    Log.d(TAG, "悬浮窗未开启，回收语音唤醒开关状态")
                    SettingsPrefs.setVoiceWakeEnabled(service.applicationContext, false)
                    return@collectLatest
                }
                val wasActive = wakeWordManager?.state?.value != WakeWordListeningManager.State.IDLE
                isVoiceWakeEnabled = enabled
                Log.d(TAG, "语音唤醒开关状态: $enabled")
                syncWakeWordListening()
                // 关闭开关时，如果此时唤醒词管理器处于非 IDLE（例如 WAKE_DETECTED 等命令），
                // 需要把圆圈视觉一起收回，避免卡在"正在听需求..."状态
                if (!enabled && wasActive) {
                    circleView?.collapseToIdle()
                }
            }
        }
    }

    private fun observeExecutionState() {
        executionStateJob = scope.launch {
            ChatViewModel.executionState.collectLatest { state ->
                when {
                    state.isRunning && !isTaskRunning -> {
                        postTaskCleanup?.let { handler.removeCallbacks(it) }
                        postTaskCleanup = null
                        isTaskRunning = true
                        hideCircle()
                        showExecutionCard(state.taskTitle)
                        showExecutionCancelChip()
                    }
                    state.isRunning && state.currentStep > 0 -> {
                        executionCardView?.updateStep(state.currentStep, state.currentAction)
                    }
                    state.isCompleted && isTaskRunning -> {
                        isTaskRunning = false
                        hideExecutionCancelChip()
                        val title = when {
                            state.isCancelled -> "任务已取消"
                            state.isSuccess -> "执行完成"
                            else -> "执行失败"
                        }
                        executionCardView?.showCompletion(state.isSuccess, title, state.resultMessage)
                        val cleanup = Runnable {
                            executionCardView = null
                            if (isCircleEnabled) {
                                showCircle()
                            }
                            syncWakeWordListening()
                            ChatViewModel.resetState()
                            postTaskCleanup = null
                        }
                        postTaskCleanup = cleanup
                        handler.postDelayed(cleanup, 5500)
                    }
                }
            }
        }
    }

    /** 录音开始后启动噪声级别订阅，让悬浮窗右上小圆点反映环境噪声。 */
    private fun startNoiseLevelObserver() {
        noiseLevelJob?.cancel()
        noiseLevelJob = scope.launch {
            var lastAppliedLevel: com.example.speech.NoiseLevel? = null
            var stableSince = 0L
            speechManager.noiseLevel.collect { level ->
                if (level == lastAppliedLevel) {
                    stableSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - stableSince >= 1500L) {
                    stableSince = System.currentTimeMillis()
                    lastAppliedLevel = level
                    handler.post { circleView?.setNoiseLevel(level) }
                }
            }
        }
    }

    private fun stopNoiseLevelObserver() {
        noiseLevelJob?.cancel()
        noiseLevelJob = null
    }
}
