package com.example.floating

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.example.data.SettingsPrefs
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
    }

    private val windowManager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var circleView: CircleFloatingView? = null
    private var executionCardView: ExecutionCardView? = null

    private var isCircleEnabled = false // DataStore 的开关状态
    private var isTaskRunning = false   // 当前是否有任务在执行
    private var overlaysSuspended = false
    private var executionStateJob: Job? = null

    /**
     * 初始化：观察 DataStore 设置 + 观察任务执行状态
     */
    fun init() {
        observeSettings()
        observeExecutionState()
        Log.d(TAG, "FloatingWindowManager 已初始化")
    }

    /**
     * 销毁所有悬浮窗并释放资源
     */
    fun destroy() {
        hideAllOverlays()
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
        circleView?.destroy()
        circleView = null
        executionCardView?.destroy()
        executionCardView = null
        isTaskRunning = false
    }

    // ==================== 圆形悬浮窗 ====================

    fun showCircle() {
        if (isTaskRunning || overlaysSuspended) return
        handler.post {
            if (circleView?.isShowing() == true) return@post
            circleView?.destroy()
            circleView = CircleFloatingView(service, windowManager) { text ->
                onVoiceResult(text)
            }
            circleView?.create()
            Log.d(TAG, "圆形悬浮窗已显示")
        }
    }

    fun hideCircle() {
        handler.post {
            circleView?.destroy()
            circleView = null
            Log.d(TAG, "圆形悬浮窗已隐藏")
        }
    }

    // ==================== 执行卡片 ====================

    private fun showExecutionCard(title: String) {
        if (overlaysSuspended) return
        handler.post {
            executionCardView?.destroy()
            executionCardView = ExecutionCardView(service, windowManager)
            executionCardView?.create(title)
        }
    }

    // ==================== 截屏隐藏/恢复 ====================

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

    // ==================== 语音结果处理 ====================

    private fun onVoiceResult(text: String) {
        Log.d(TAG, "语音识别结果：$text")

        // 在协程中创建 ViewModel 并执行任务
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

    // ==================== 观察器 ====================

    /**
     * 观察 DataStore 中的悬浮窗开关设置
     */
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

    /**
     * 观察 ChatViewModel 的任务执行状态
     */
    private fun observeExecutionState() {
        executionStateJob = scope.launch {
            ChatViewModel.executionState.collectLatest { state ->
                when {
                    // 任务开始
                    state.isRunning && !isTaskRunning -> {
                        isTaskRunning = true
                        hideCircle()
                        showExecutionCard(state.taskTitle)
                    }
                    // 步骤更新
                    state.isRunning && state.currentStep > 0 -> {
                        executionCardView?.updateStep(state.currentStep, state.currentAction)
                    }
                    // 任务完成
                    state.isCompleted && isTaskRunning -> {
                        isTaskRunning = false
                        val title = when {
                            state.isCancelled -> "任务已取消"
                            state.isSuccess -> "执行完成"
                            else -> "执行失败"
                        }
                        executionCardView?.showCompletion(state.isSuccess, title, state.resultMessage)
                        // 5秒后恢复圆形按钮（卡片会自己消失）
                        handler.postDelayed({
                            executionCardView = null
                            if (isCircleEnabled) {
                                showCircle()
                            }
                            // 重置状态以准备下次任务
                            ChatViewModel.resetState()
                        }, 5500) // 比卡片的5秒稍长，确保卡片先消失
                    }
                }
            }
        }
    }
}
