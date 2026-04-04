package com.example.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.speakassist.R
import com.example.speech.BaiduSpeechConfig
import com.example.speech.BaiduSpeechManager
import kotlin.math.abs

/**
 * 圆形悬浮按钮
 *
 * 功能：
 * - 可拖拽，松手后自动吸附到最近的屏幕边缘并半隐藏
 * - 点击展开：圆角矩形，logo + 文字，启动语音识别
 * - 识别成功：显示文字 → 自动发送 → 收回
 * - 识别失败：显示"识别失败" → 3秒后自动收回
 * - 展开态点击 logo：取消识别，立即收回
 */
class CircleFloatingView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onVoiceResult: (String) -> Unit
) {

    companion object {
        private const val TAG = "CircleFloatingView"
        private const val CIRCLE_SIZE = 48 // dp
        private const val EXPANDED_WIDTH = 180 // dp
        private const val ANIMATION_DURATION = 200L
        private const val ERROR_DISPLAY_DURATION = 3000L
        private const val RESULT_DISPLAY_DURATION = 800L
        private const val CLICK_THRESHOLD_DP = 8 // 移动超过 8dp 视为拖拽
    }

    enum class State {
        IDLE, EXPANDING, LISTENING, RESULT, ERROR, COLLAPSING, HIDDEN
    }

    var rootView: View? = null
        private set
    var layoutParams: WindowManager.LayoutParams? = null
        private set

    private var circleContainer: FrameLayout? = null
    private var expandedContainer: LinearLayout? = null
    private var tvStatus: TextView? = null
    private var ivExpandedLogo: ImageView? = null

    private var currentState = State.HIDDEN
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    // 屏幕尺寸
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels
    private val circleSizePx = (CIRCLE_SIZE * density).toInt()
    private val halfCirclePx = circleSizePx / 2
    private val clickThresholdPx = (CLICK_THRESHOLD_DP * density).toInt()

    // 拖拽状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var isDragging = false

    // 当前吸附在右侧边缘
    private var isOnRightEdge = true

    // 语音识别
    private var speechManager: BaiduSpeechManager? = null

    fun create() {
        if (rootView != null) return

        val inflater = LayoutInflater.from(context)
        rootView = inflater.inflate(R.layout.layout_floating_circle, null)

        circleContainer = rootView?.findViewById(R.id.circleContainer)
        expandedContainer = rootView?.findViewById(R.id.expandedContainer)
        tvStatus = rootView?.findViewById(R.id.tvStatus)
        tvStatus?.isSelected = true
        ivExpandedLogo = rootView?.findViewById(R.id.ivExpandedLogo)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - halfCirclePx  // 右侧边缘半隐藏
            y = screenHeight / 2 - halfCirclePx  // 垂直居中
        }

        setupTouchListeners()
        setupSpeechManager()

        windowManager.addView(rootView, layoutParams)
        currentState = State.IDLE
        isOnRightEdge = true
        Log.d(TAG, "圆形悬浮窗已创建")
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        speechManager?.cancel()
        speechManager?.destroy()
        speechManager = null
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "移除悬浮窗失败", e)
            }
        }
        rootView = null
        currentState = State.HIDDEN
    }

    fun isShowing(): Boolean = rootView != null && currentState != State.HIDDEN

    // ==================== 触摸事件 ====================

    private fun setupTouchListeners() {
        // 圆形按钮：拖拽 + 点击
        circleContainer?.setOnTouchListener { _, event ->
            if (currentState != State.IDLE) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = layoutParams?.x ?: 0
                    downParamY = layoutParams?.y ?: 0
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (abs(dx) > clickThresholdPx || abs(dy) > clickThresholdPx)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams?.x = downParamX + dx.toInt()
                        layoutParams?.y = (downParamY + dy.toInt()).coerceIn(0, screenHeight - circleSizePx)
                        updateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToEdge()
                    } else {
                        expand()
                    }
                    true
                }
                else -> false
            }
        }

        // 展开态 logo 点击 → 取消并收回
        ivExpandedLogo?.setOnClickListener {
            if (currentState == State.LISTENING || currentState == State.RESULT || currentState == State.ERROR) {
                speechManager?.cancel()
                handler.removeCallbacksAndMessages(null)
                collapse()
            }
        }
    }

    // ==================== 吸边动画 ====================

    private fun snapToEdge() {
        val currentX = layoutParams?.x ?: 0
        val centerX = currentX + halfCirclePx
        isOnRightEdge = centerX > screenWidth / 2

        val targetX = if (isOnRightEdge) screenWidth - halfCirclePx else -halfCirclePx

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = ANIMATION_DURATION
        animator.addUpdateListener {
            layoutParams?.x = it.animatedValue as Int
            updateLayout()
        }
        animator.start()
    }

    // ==================== 展开/收回 ====================

    private fun expand() {
        if (currentState != State.IDLE) return
        currentState = State.EXPANDING

        circleContainer?.visibility = View.GONE
        expandedContainer?.visibility = View.VISIBLE
        tvStatus?.text = "正在听......"

        // 计算展开后的目标位置（完全可见）
        val expandedWidthPx = (EXPANDED_WIDTH * density).toInt()
        val currentX = layoutParams?.x ?: 0
        val targetX = if (isOnRightEdge) {
            screenWidth - expandedWidthPx
        } else {
            0
        }

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = ANIMATION_DURATION
        animator.addUpdateListener {
            layoutParams?.x = it.animatedValue as Int
            updateLayout()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                currentState = State.LISTENING
                speechManager?.start()
            }
        })
        animator.start()
    }

    private fun collapse() {
        if (currentState == State.COLLAPSING || currentState == State.IDLE || currentState == State.HIDDEN) return
        currentState = State.COLLAPSING
        handler.removeCallbacksAndMessages(null)

        // 收回到边缘半隐藏
        val currentX = layoutParams?.x ?: 0
        val targetX = if (isOnRightEdge) screenWidth - halfCirclePx else -halfCirclePx

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = ANIMATION_DURATION
        animator.addUpdateListener {
            layoutParams?.x = it.animatedValue as Int
            updateLayout()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                expandedContainer?.visibility = View.GONE
                circleContainer?.visibility = View.VISIBLE
                currentState = State.IDLE
            }
        })
        animator.start()
    }

    // ==================== 语音识别 ====================

    private fun setupSpeechManager() {
        speechManager = BaiduSpeechManager(context)
        val credentials = BaiduSpeechConfig.credentials()
        speechManager?.setCredentials(credentials.apiKey, credentials.secretKey)

        speechManager?.setCallback(object : BaiduSpeechManager.Callback {
            override fun onReady() {
                handler.post {
                    tvStatus?.text = "正在听......"
                }
            }

            override fun onResult(text: String) {
                handler.post {
                    if (currentState != State.LISTENING) return@post
                    currentState = State.RESULT
                    tvStatus?.text = text
                    handler.postDelayed({
                        onVoiceResult(text)
                        collapse()
                    }, RESULT_DISPLAY_DURATION)
                }
            }

            override fun onError(message: String) {
                handler.post {
                    if (currentState != State.LISTENING) return@post
                    currentState = State.ERROR
                    tvStatus?.text = "识别失败"
                    handler.postDelayed({
                        if (currentState == State.ERROR) {
                            collapse()
                        }
                    }, ERROR_DISPLAY_DURATION)
                }
            }

            override fun onEnd() {}
            override fun onVolumeChanged(volume: Int) {}
        })
    }

    // ==================== 工具 ====================

    private fun updateLayout() {
        rootView?.let { view ->
            try {
                windowManager.updateViewLayout(view, layoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "更新布局失败", e)
            }
        }
    }
}
