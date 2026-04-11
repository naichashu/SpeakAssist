package com.example.floating

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.example.data.SettingsPrefs
import com.example.speech.BaiduSpeechConfig
import com.example.speech.BaiduSpeechManager
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

    private var isCircleEnabled = false
    private var isTaskRunning = false
    private var overlaysSuspended = false
    private var executionStateJob: Job? = null

    fun init() {
        val credentials = BaiduSpeechConfig.credentials()
        speechManager.setCredentials(credentials.apiKey, credentials.secretKey)
        speechManager.setCallback(object : BaiduSpeechManager.Callback {
            override fun onReady() {
                handler.post {
                    circleView?.showListening()
                }
            }

            override fun onResult(text: String) {
                handler.post {
                    circleView?.showRecognitionResult(text)
                }
                handler.postDelayed({
                    circleView?.collapseToIdle()
                    onVoiceResult(text)
                }, RESULT_DISPLAY_DURATION)
            }

            override fun onError(message: String) {
                handler.post {
                    circleView?.showRecognitionError(message)
                }
                handler.postDelayed({
                    circleView?.collapseToIdle()
                }, ERROR_DISPLAY_DURATION)
            }

            override fun onEnd() = Unit

            override fun onVolumeChanged(volume: Int) = Unit
        })

        observeSettings()
        observeExecutionState()
        Log.d(TAG, "FloatingWindowManager 已初始化")
    }

    fun destroy() {
        hideAllOverlays()
        speechManager.destroy()
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
        }
    }

    fun suspendOverlays() {
        handler.post {
            overlaysSuspended = true
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
                    if (!speechManager.isListening()) {
                        speechManager.start()
                    }
                }
            })
            circleView?.create()
            Log.d(TAG, "圆形悬浮窗已显示")
        }
    }

    fun hideCircle() {
        handler.post {
            speechManager.cancel()
            circleView?.destroy()
            circleView = null
            Log.d(TAG, "圆形悬浮窗已隐藏")
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

    suspend fun hideForScreenshot() {
        withContext(Dispatchers.Main.immediate) {
            executionCardView?.rootView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.w(TAG, "截屏前隐藏卡片失败", e)
                }
            }
        }
    }

    suspend fun restoreAfterScreenshot() {
        withContext(Dispatchers.Main.immediate) {
            if (overlaysSuspended) return@withContext
            val card = executionCardView ?: return@withContext
            if (!card.isShowing()) return@withContext
            card.rootView?.let { view ->
                card.currentLayoutParams?.let { params ->
                    try {
                        windowManager.addView(view, params)
                    } catch (e: Exception) {
                        Log.w(TAG, "截屏后恢复卡片失败", e)
                    }
                }
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
                if (enabled && !isTaskRunning) {
                    showCircle()
                } else if (!enabled) {
                    hideCircle()
                }
            }
        }
    }

    private fun observeExecutionState() {
        executionStateJob = scope.launch {
            ChatViewModel.executionState.collectLatest { state ->
                when {
                    state.isRunning && !isTaskRunning -> {
                        isTaskRunning = true
                        hideCircle()
                        showExecutionCard(state.taskTitle)
                    }
                    state.isRunning && state.currentStep > 0 -> {
                        executionCardView?.updateStep(state.currentStep, state.currentAction)
                    }
                    state.isCompleted && isTaskRunning -> {
                        isTaskRunning = false
                        val title = when {
                            state.isCancelled -> "任务已取消"
                            state.isSuccess -> "执行完成"
                            else -> "执行失败"
                        }
                        executionCardView?.showCompletion(state.isSuccess, title, state.resultMessage)
                        handler.postDelayed({
                            executionCardView = null
                            if (isCircleEnabled) {
                                showCircle()
                            }
                            ChatViewModel.resetState()
                        }, 5500)
                    }
                }
            }
        }
    }
}
