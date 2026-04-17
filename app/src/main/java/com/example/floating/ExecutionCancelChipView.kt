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

class ExecutionCancelChipView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val listener: Listener
) {

    companion object {
        private const val TAG = "ExecutionCancelChipView"
    }

    interface Listener {
        fun onCancelClicked()
    }

    var rootView: View? = null
        private set
    var currentLayoutParams: WindowManager.LayoutParams? = null
        private set

    private var tvCancel: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isShowing = false
    private var isCancelling = false

    fun create() {
        if (rootView != null) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_SpeakAssist)
        val inflater = LayoutInflater.from(themedContext)
        rootView = inflater.inflate(R.layout.layout_execution_cancel_chip, null)
        tvCancel = rootView?.findViewById(R.id.tvExecutionCancel)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        currentLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * context.resources.displayMetrics.density).toInt()
            y = (56 * context.resources.displayMetrics.density).toInt()
        }

        rootView?.setOnClickListener {
            if (isCancelling) return@setOnClickListener
            listener.onCancelClicked()
        }

        windowManager.addView(rootView, currentLayoutParams)
        isShowing = true
        Log.d(TAG, "执行取消入口已显示")
    }

    fun setCancelling(cancelling: Boolean) {
        isCancelling = cancelling
        handler.post {
            tvCancel?.text = if (cancelling) {
                context.getString(R.string.execution_cancelling)
            } else {
                context.getString(R.string.execution_cancel)
            }
            rootView?.isEnabled = !cancelling
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "移除执行取消入口失败", e)
            }
        }
        rootView = null
        isShowing = false
        isCancelling = false
    }

    fun isShowing(): Boolean = isShowing

    fun markShowing(showing: Boolean) {
        isShowing = showing
    }
}
