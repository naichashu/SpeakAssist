package com.example.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.speakassist.R

/**
 * 执行卡片悬浮窗
 *
 * 功能：
 * - 显示任务执行状态（标题、步骤、当前动作）
 * - 任务完成后 5 秒自动消失
 */
class ExecutionCardView(
    private val context: Context,
    private val windowManager: WindowManager
) {

    companion object {
        private const val TAG = "ExecutionCardView"
        private const val AUTO_HIDE_DELAY = 5000L
    }

    var rootView: View? = null
        private set
    var currentLayoutParams: WindowManager.LayoutParams? = null
        private set

    private var tvTaskTitle: TextView? = null
    private var tvStepProgress: TextView? = null
    private var tvCurrentAction: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isShowing = false

    fun create(taskTitle: String) {
        if (rootView != null) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_SpeakAssist)
        val inflater = LayoutInflater.from(themedContext)
        rootView = inflater.inflate(R.layout.layout_execution_card, null)

        tvTaskTitle = rootView?.findViewById(R.id.tvTaskTitle)
        tvStepProgress = rootView?.findViewById(R.id.tvStepProgress)
        tvCurrentAction = rootView?.findViewById(R.id.tvCurrentAction)

        tvTaskTitle?.text = "正在执行：$taskTitle"
        tvStepProgress?.text = "准备中..."
        tvCurrentAction?.text = "当前步骤：等待响应..."

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val screenWidth = context.resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth * 0.85).toInt()

        currentLayoutParams = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * context.resources.displayMetrics.density).toInt()
        }

        windowManager.addView(rootView, currentLayoutParams)
        isShowing = true
        Log.d(TAG, "执行卡片已显示")
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "移除执行卡片失败", e)
            }
        }
        rootView = null
        isShowing = false
    }

    fun isShowing(): Boolean = isShowing

    fun markShowing(showing: Boolean) {
        isShowing = showing
    }

    fun updateStep(step: Int, action: String) {
        handler.post {
            tvStepProgress?.text = "已执行第 $step 步"
            tvCurrentAction?.text = "当前步骤：$action"
        }
    }

    fun showCompletion(success: Boolean, title: String, message: String) {
        handler.post {
            tvTaskTitle?.text = title
            tvCurrentAction?.text = message

            handler.postDelayed({
                destroy()
            }, AUTO_HIDE_DELAY)
        }
    }
}
