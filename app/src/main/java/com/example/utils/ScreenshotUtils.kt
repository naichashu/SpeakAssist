package com.example.utils

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import android.util.Base64


// 对应 Python 的 Screenshot 数据类，封装截图结果
data class Screenshot(
    val base64Data: String,
    val width: Int,
    val height: Int,
    val isSensitive: Boolean = false
)

/**
 * 截图工具类
 */
object ScreenshotUtils {
    /**
     * 截取当前 Activity 的界面（自身 App 界面）
     * @param activity 当前活动页
     * @return 封装后的 Screenshot 对象
     */
    fun captureSelfScreen(activity: Activity): Screenshot {
        try {
            // 步骤 1：获取当前 Activity 的根 View（整个界面的视图）
            val rootView: View = activity.window.decorView.rootView

            // 步骤 2：创建与根 View 尺寸一致的 Bitmap
            val bitmap = Bitmap.createBitmap(
                rootView.width,
                rootView.height,
                Bitmap.Config.ARGB_8888 // 图片格式，对应 Python 的 PNG
            )

            // 步骤 3：将 View 绘制到 Bitmap 中（完成截屏）
            val canvas = Canvas(bitmap)
            rootView.draw(canvas)

            // 步骤 4：裁剪状态栏（可选，避免截图包含系统状态栏，更纯净）
            val rect = Rect()
            activity.window.decorView.getWindowVisibleDisplayFrame(rect)
            val statusBarHeight = rect.top
            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                statusBarHeight,
                bitmap.width,
                bitmap.height - statusBarHeight
            )

            // 步骤 5：将 Bitmap 转换为 Base64 编码
            val base64Data = bitmapToBase64(croppedBitmap)

            // 步骤 6：封装返回结果
            return Screenshot(
                base64Data = base64Data,
                width = croppedBitmap.width,
                height = croppedBitmap.height,
                isSensitive = false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            // 截图失败，返回黑色兜底截图（对应 Python 的 _create_fallback_screenshot）
            return createFallbackScreenshot(isSensitive = false)
        }
    }

    /**
     * Bitmap 转 Base64 编码
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 压缩为 PNG 格式（无损耗，对应 Python 的 format="PNG"）
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        // Base64 编码（NO_WRAP 对应 Python 的无换行符，避免格式问题）
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * 创建黑色兜底截图
     */
    fun createFallbackScreenshot(isSensitive: Boolean): Screenshot {
        val defaultWidth = 1080
        val defaultHeight = 2400

        // 创建黑色 Bitmap
        val blackBitmap = Bitmap.createBitmap(
            defaultWidth,
            defaultHeight,
            Bitmap.Config.ARGB_8888
        )
        val base64Data = bitmapToBase64(blackBitmap)

        return Screenshot(
            base64Data = base64Data,
            width = defaultWidth,
            height = defaultHeight,
            isSensitive = isSensitive
        )
    }
}