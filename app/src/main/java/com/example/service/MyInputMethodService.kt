package com.example.service

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * 输入法服务
 */
class MyInputMethodService : InputMethodService() {
    companion object {
        private const val TAG = "MyInputMethodService"
        private var instance: MyInputMethodService? = null
        fun getInstance(): MyInputMethodService? = instance
        fun isEnabled(context: Context): Boolean {
            val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val myImeName = "com.example.service.MyInputMethodService"
            return imm.enabledInputMethodList.any { it.serviceName == myImeName }
        }

        /**
         * 输入文本
         * @param text：要输入的文本
         * @return 是否输入成功
         */
        fun inputText(text: String): Boolean {
            return getInstance()?.typeText(text) ?: false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "输入法已创建")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "输入法已销毁")
    }

    /**
     * 启动输入法时，会调用此方法
     */
    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        Log.d(TAG, "启动输入法")
    }

    // 1. 实现输入法界面（哪怕简单显示，也符合系统规范）
    override fun onCreateInputView(): View {
        // 构建一个简单的根布局
        val root = FrameLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 添加一个简单的状态提示文本
        val statusTv = TextView(this).apply {
            text = "SpeakAssist输入法已就绪"
            textSize = 12f
            setTextColor("#333333".toColorInt())
            setPadding(20, 15, 20, 15)
        }
        root.addView(
            statusTv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            })

        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }


    /**
     * 核心方法：向当前有焦点的输入框输入文本
     * @param text：要输入的文本
     * @return 是否输入成功
     */
    fun typeText(text: String): Boolean {
        if (text.isBlank()) return false

        // 获取当前输入连接（必须有焦点的输入框才会有）
        val inputConn = currentInputConnection
        if (inputConn == null) {
            Log.d(TAG, "输入失败：无可用的输入连接（未激活输入框）")
            return false
        }

        try {
            // 输入文本
            val isReallySuccess = inputConn.commitText(text, text.length)
            Log.d(TAG, "文本输入成功：$text")
            return isReallySuccess
        } catch (e: Exception) {
            Log.d(TAG, "输入文本失败：${e.message}", e)
            return false
        }
    }
}