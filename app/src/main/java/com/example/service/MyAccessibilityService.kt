package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

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
        event?.let {
            if (it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                _currentApp.value = it.packageName?.toString()
            }
        }
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

    suspend fun getScreenshotSuspend() : Bitmap? = suspendCancellableCoroutine { cont ->
        getScreenshot {
            cont.resume(it)
        }
    }

    /**
     * 获取屏幕截图
     * @param callback：截图结果回调
     */
    fun getScreenshot(callback: (Bitmap?) -> Unit) {
        Log.d(TAG, "开始截图")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mainExecutor.execute {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onFailure(errorCode: Int) {
                            callback(null)
                        }

                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                            hardwareBuffer.close()
                            val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            callback(bitmap)
                        }

                    })
            }
        } else callback(null)
    }
}