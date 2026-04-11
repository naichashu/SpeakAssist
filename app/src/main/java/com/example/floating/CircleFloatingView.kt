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
import kotlin.math.abs

/**
 * 圆形悬浮按钮
 */
class CircleFloatingView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val listener: Listener
) {

    companion object {
        private const val TAG = "CircleFloatingView"
        private const val CIRCLE_SIZE = 48 // dp
        private const val EXPANDED_WIDTH = 180 // dp
        private const val ANIMATION_DURATION = 200L
        private const val CLICK_THRESHOLD_DP = 8 // 移动超过 8dp 视为拖拽
    }

    interface Listener {
        fun onCircleClicked()
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

    private var currentState = State.HIDDEN
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels
    private val circleSizePx = (CIRCLE_SIZE * density).toInt()
    private val halfCirclePx = circleSizePx / 2
    private val clickThresholdPx = (CLICK_THRESHOLD_DP * density).toInt()

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var isDragging = false
    private var isOnRightEdge = true

    fun create() {
        if (rootView != null) return

        val inflater = LayoutInflater.from(context)
        rootView = inflater.inflate(R.layout.layout_floating_circle, null)

        circleContainer = rootView?.findViewById(R.id.circleContainer)
        expandedContainer = rootView?.findViewById(R.id.expandedContainer)
        tvStatus = rootView?.findViewById(R.id.tvStatus)
        tvStatus?.isSelected = true

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
            x = screenWidth - halfCirclePx
            y = screenHeight / 2 - halfCirclePx
        }

        setupTouchListeners()

        windowManager.addView(rootView, layoutParams)
        showIdle()
        isOnRightEdge = true
        Log.d(TAG, "圆形悬浮窗已创建")
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
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

    fun showIdle() {
        handler.removeCallbacksAndMessages(null)
        expandedContainer?.visibility = View.GONE
        circleContainer?.visibility = View.VISIBLE
        currentState = State.IDLE
    }

    fun showListening() {
        expandForState(State.LISTENING, "正在听需求...")
    }

    fun showRecognitionResult(text: String) {
        ensureExpandedPosition()
        expandedContainer?.visibility = View.VISIBLE
        circleContainer?.visibility = View.GONE
        tvStatus?.text = text
        currentState = State.RESULT
    }

    fun showRecognitionError(message: String) {
        ensureExpandedPosition()
        expandedContainer?.visibility = View.VISIBLE
        circleContainer?.visibility = View.GONE
        tvStatus?.text = if (message.isBlank()) "识别失败" else message
        currentState = State.ERROR
    }

    fun collapseToIdle() {
        if (currentState == State.COLLAPSING || currentState == State.IDLE || currentState == State.HIDDEN) return
        currentState = State.COLLAPSING
        handler.removeCallbacksAndMessages(null)

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

    private fun setupTouchListeners() {
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
                        listener.onCircleClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

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

    private fun expandForState(targetState: State, statusText: String) {
        handler.removeCallbacksAndMessages(null)
        if (currentState == State.HIDDEN) return
        if (currentState == targetState) {
            tvStatus?.text = statusText
            return
        }

        if (currentState == State.IDLE) {
            currentState = State.EXPANDING
            circleContainer?.visibility = View.GONE
            expandedContainer?.visibility = View.VISIBLE
            tvStatus?.text = statusText

            val expandedWidthPx = (EXPANDED_WIDTH * density).toInt()
            val currentX = layoutParams?.x ?: 0
            val targetX = if (isOnRightEdge) screenWidth - expandedWidthPx else 0

            val animator = ValueAnimator.ofInt(currentX, targetX)
            animator.duration = ANIMATION_DURATION
            animator.addUpdateListener {
                layoutParams?.x = it.animatedValue as Int
                updateLayout()
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentState = targetState
                    tvStatus?.text = statusText
                }
            })
            animator.start()
            return
        }

        ensureExpandedPosition()
        expandedContainer?.visibility = View.VISIBLE
        circleContainer?.visibility = View.GONE
        currentState = targetState
        tvStatus?.text = statusText
    }

    private fun ensureExpandedPosition() {
        val expandedWidthPx = (EXPANDED_WIDTH * density).toInt()
        layoutParams?.x = if (isOnRightEdge) screenWidth - expandedWidthPx else 0
        updateLayout()
    }

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
