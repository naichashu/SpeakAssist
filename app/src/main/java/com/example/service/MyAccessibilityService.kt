package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"
        private var autoAccessibilityService: MyAccessibilityService? = null

        // 提供全局获取实例的入口
        fun getInstance(): MyAccessibilityService? = autoAccessibilityService

        // 快速判断服务是否已激活（实例是否存在）
        fun isServiceEnabled(): Boolean = autoAccessibilityService != null
    }

    /**
     * 创建服务时调用
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        autoAccessibilityService = this
    }

    /**
     * 监听事件
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        TODO("Not yet implemented")
    }


    /**
     * 监听中断
     */
    override fun onInterrupt() {
        TODO("Not yet implemented")
    }

    /**
     * 销毁服务时调用
     */
    override fun onDestroy() {
        super.onDestroy()
        autoAccessibilityService = null
    }

    /**
     * 内部实现：根据 (x, y) 坐标执行点击操作
     * @param x 屏幕绝对X坐标
     * @param y 屏幕绝对Y坐标
     * @return 是否成功点击完成
     */
    fun clickByNode(x: Float, y: Float): Boolean {
        // 构建手势路径
        val path = Path()
        path.moveTo(x, y)

        // 构建笔画描述：参数不变，path已正确使用Float坐标
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)

        // 构建完整手势
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        Log.d(TAG, "执行点击：坐标($x, $y)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val result = dispatchGesture(gesture, null, null)

            Log.d(TAG, "点击手势提交${if (result) "成功" else "失败"}")
            return result
        } else {
            Log.e(TAG, "系统版本不支持dispatchGesture")
            return false
        }
    }
}