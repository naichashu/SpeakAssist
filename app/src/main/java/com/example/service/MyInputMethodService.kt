package com.example.service

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper

/**
 * 输入法服务
 */
class MyInputMethodService : InputMethodService() {

    // 当前的输入连接（绑定有焦点的输入框）
    private var currentInputConn: InputConnection? = null
    private val handler = Handler(Looper.getMainLooper())

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
     * 绑定输入框时，会调用此方法
     */
    override fun onBindInput() {
        super.onBindInput()
        currentInputConn = currentInputConnection
        Log.d(TAG, "已绑定输入框，输入连接：${currentInputConn != null}")
    }

    /**
     * 启动输入法时，会调用此方法
     */
    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        // 不调用父类方法，避免显示键盘界面
        Log.d(TAG, "跳过显示输入法键盘")
    }

    /**
     * 核心方法：向当前有焦点的输入框输入文本
     * @param text：要输入的文本
     * @return 是否输入成功
     */
    fun typeText(text: String): Boolean {
        if (text.isBlank()) return false

        // 获取当前输入连接（必须有焦点的输入框才会有）
        val inputConn = currentInputConn ?: currentInputConnection
        if (inputConn == null) {
            Log.d(TAG, "输入失败：无可用的输入连接（未激活输入框）")
            return false
        }

        try {
            // 1. 清空输入框已有内容
            inputConn.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)

            // 2. 提交文本到输入框
            handler.post {
                inputConn.commitText(text, text.length)
                Log.d(TAG, "文本输入成功：$text")
            }
            return true
        } catch (e: Exception) {
            Log.d(TAG, "输入文本失败：${e.message}", e)
            return false
        }
    }
}