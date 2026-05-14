package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.floating.FloatingWindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 无障碍服务
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"
        private const val SCREENSHOT_TIMEOUT_MS = 5000L
        private var autoAccessibilityService: MyAccessibilityService? = null

        // 提供全局获取实例的入口
        fun getInstance(): MyAccessibilityService? = autoAccessibilityService

        // 快速判断服务是否已激活（实例是否存在）
        fun isServiceEnabled(): Boolean = autoAccessibilityService != null

        fun suspendFloatingOverlays() {
            autoAccessibilityService?.floatingWindowManager?.suspendOverlays()
        }

        fun resumeFloatingOverlays() {
            autoAccessibilityService?.floatingWindowManager?.resumeOverlays()
        }
    }

    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

    /**
     * 直接查询前台 App 包名，不依赖 TYPE_WINDOW_STATE_CHANGED 事件。
     * 原理：AccessibilityService 的 rootInActiveWindow 返回前台 App 的窗口树，
     * 直接读取其 package name 即可。用于解决 Launch 后事件未到达导致 currentApp 为 null 的竞态问题。
     * 当 root 为 null 时 fallback 到 StateFlow（可能在某些边界情况有意义）。
     */
    fun getForegroundPackageName(): String? {
        val root = rootInActiveWindow
        return try {
            root?.packageName?.toString() ?: _currentApp.value
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    root?.javaClass?.getMethod("close")?.invoke(root)
                } catch (e: Exception) {
                    @Suppress("DEPRECATION")
                    root?.recycle()
                }
            } else {
                @Suppress("DEPRECATION")
                root?.recycle()
            }
        }
    }

    var floatingWindowManager: FloatingWindowManager? = null
        private set

    /**
     * 创建服务时调用
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        autoAccessibilityService = this

        // 初始化悬浮窗管理器
        floatingWindowManager = FloatingWindowManager(this)
        floatingWindowManager?.init()
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
        Log.w(TAG, "无障碍服务被中断")
    }

    /**
     * 销毁服务时调用
     */
    override fun onDestroy() {
        super.onDestroy()
        floatingWindowManager?.destroy()
        floatingWindowManager = null
        autoAccessibilityService = null
    }

    /**
     * 内部实现：根据 (x, y) 坐标执行点击操作。
     * 注意：返回 true 仅表示 dispatchGesture 的 onCompleted 回调成功（手势已派发进
     * 输入管线），不等价于目标 App 已经响应点击。OEM 反误触 / 第三方 App 二次过滤
     * 仍可能在下层吞掉事件。详见 docs/适配调整/。
     */
    suspend fun clickByNode(x: Float, y: Float): Boolean {
        val profile = GestureTimingProfile.current

        val path = Path()
        path.moveTo(x, y)

        val stroke = GestureDescription.StrokeDescription(path, 0, profile.tapDurationMs)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        Log.d(TAG, "执行点击：坐标($x, $y) duration=${profile.tapDurationMs}ms profile=${profile.name}")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "系统版本不支持dispatchGesture")
            return false
        }

        val preWindowId = currentWindowIdSnapshot()
        val result = dispatchGestureAwaiting(gesture)
        Log.d(TAG, "点击手势${if (result) "已派发" else "派发失败"}")

        if (result) {
            val postWindowId = currentWindowIdSnapshot()
            if (preWindowId != null && preWindowId == postWindowId) {
                Log.w(
                    TAG,
                    "suspected_invalid_tap: windowId unchanged ($preWindowId) " +
                            "at($x,$y) profile=${profile.name} tap=${profile.tapDurationMs}ms",
                )
            }
            return true
        }

        // dispatchGesture 派发失败：仅 strict 档（华为/荣耀等已知故障机型）启用
        // ACTION_CLICK 兜底。default/balanced 档此分支直接返回 false，行为完全等价
        // 于改动前。
        if (profile.enableActionClickFallback) {
            val fallbackResult = tryClickByNode(x, y)
            Log.i(
                TAG,
                "ACTION_CLICK 兜底${if (fallbackResult) "成功" else "失败"}：坐标($x, $y) " +
                        "profile=${profile.name}",
            )
            return fallbackResult
        }
        return false
    }

    /**
     * 判断当前前台页面是否在 WebView / H5 容器内。
     * 仅在 ActionExecutor 检测到点击失败后调用，结果作为错误消息的附加提示
     * 给模型，引导其不要在 H5 页面里反复重试 tap。
     *
     * 实现：root 起 DFS，深度 ≤4 层、累计访问节点 ≤200 个。WebView 通常包在
     * FrameLayout / DecorView 1-3 层内，深层级出现 WebView 几率低、不值得继续遍。
     * 节点内存：root 在本方法 finally 释放；遍历中其他节点出栈即关。
     */
    fun foregroundContainerHint(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            if (containsWebView(root, maxDepth = 4)) "WebView" else null
        } finally {
            closeNode(root)
        }
    }

    private fun containsWebView(
        root: AccessibilityNodeInfo,
        maxDepth: Int,
        maxNodes: Int = 200,
    ): Boolean {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        var found = false
        var visited = 0
        try {
            while (stack.isNotEmpty() && visited < maxNodes && !found) {
                val (node, depth) = stack.removeLast()
                visited++
                if (node.className?.contains("WebView", ignoreCase = true) == true) {
                    found = true
                } else if (depth < maxDepth) {
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { stack.add(it to depth + 1) }
                    }
                }
                if (node !== root) closeNode(node)
            }
        } finally {
            while (stack.isNotEmpty()) {
                val (node, _) = stack.removeLast()
                if (node !== root) closeNode(node)
            }
        }
        return found
    }

    /**
     * 取一次当前活动窗口的 windowId 快照，用于点击前后比对（失效点击采样）。
     * 注意：windowId 不变只是「可能无效」的弱信号——同窗口内点击 toggle / 列表项
     * 也不会改 windowId。后续可加多信号融合（focused node / contentChange / 像素差）。
     */
    private fun currentWindowIdSnapshot(): Int? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.windowId
        } finally {
            closeNode(root)
        }
    }

    /**
     * 在 (x, y) 坐标处查找最深的可点击节点，并对它执行 ACTION_CLICK。
     *
     * 仅由 clickByNode 在 dispatchGesture 失败时调用，且仅当当前
     * `GestureTimingProfile.enableActionClickFallback` 打开（即 strict 档：华为/荣耀）
     * 才会触发。default/balanced 档不会进入此分支，对正常机型零影响。
     *
     * 算法：栈式 DFS 遍历窗口树，找包含 (x, y) 坐标且 `isClickable + visibleToUser`
     * 的节点中**面积最小**的（即最深的可点击控件）。例如点击列表项中的按钮，会优先
     * 匹配按钮而不是外层的列表项。
     *
     * 节点内存管理：root 由本方法 finally 释放；匹配节点由本方法在 performAction
     * 后立即关闭；栈中其他节点出栈后立即关闭。
     */
    private fun tryClickByNode(x: Float, y: Float): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findClickableNodeAt(root, x.toInt(), y.toInt())
        return try {
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } finally {
            if (target != null && target !== root) closeNode(target)
            closeNode(root)
        }
    }

    /**
     * 栈式 DFS 找 (x, y) 处面积最小的可点击节点。
     * root 不释放（caller 负责）；返回值由 caller 释放；遍历过程中的其他节点立即释放。
     */
    private fun findClickableNodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        val bounds = Rect()
        var bestMatch: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE

        try {
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                current.getBoundsInScreen(bounds)

                if (!bounds.contains(x, y)) {
                    if (current !== root) closeNode(current)
                    continue
                }

                for (i in 0 until current.childCount) {
                    current.getChild(i)?.let { stack.add(it) }
                }

                val area = bounds.width() * bounds.height()
                if (current.isClickable && current.isVisibleToUser && area in 1 until bestArea) {
                    bestMatch?.let { if (it !== root) closeNode(it) }
                    bestMatch = current
                    bestArea = area
                } else if (current !== root && current !== bestMatch) {
                    closeNode(current)
                }
            }
        } finally {
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (node !== root && node !== bestMatch) closeNode(node)
            }
        }
        return bestMatch
    }

    /**
     * 关闭 AccessibilityNodeInfo。API 33+ 使用反射调用 close()（NodeInfo 实现了
     * Closeable，但跨 SDK 编译需反射），旧版本降级到 recycle()。
     */
    private fun closeNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                AccessibilityNodeInfo::class.java.getMethod("close").invoke(node)
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        } else {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    /**
     * 长按操作
     * @param x 屏幕绝对X坐标
     * @param y 屏幕绝对Y坐标
     * @return 是否成功执行长按
     */
    suspend fun longPressByNode(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "系统版本不支持dispatchGesture")
            return false
        }

        // 构建手势路径
        val path = Path()
        path.moveTo(x, y)

        // 长按持续时间为500毫秒
        val longPressDuration = 500L
        val stroke = GestureDescription.StrokeDescription(path, 0, longPressDuration)

        // 构建完整手势
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        Log.d(TAG, "执行长按：坐标($x, $y)，持续时间${longPressDuration}ms")

        val result = dispatchGestureAwaiting(gesture)
        Log.d(TAG, "长按手势${if (result) "已派发" else "派发失败"}")
        return result
    }

    /**
     * 双击操作
     * @param x 屏幕绝对X坐标
     * @param y 屏幕绝对Y坐标
     * @return 是否成功执行双击
     */
    suspend fun doubleTapByNode(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "系统版本不支持dispatchGesture")
            return false
        }

        val profile = GestureTimingProfile.current

        // 双击需要两个快速连续的点击
        val path = Path()
        path.moveTo(x, y)

        val firstClick = GestureDescription.StrokeDescription(path, 0, profile.doubleTapFirstDurationMs)
        val secondClick = GestureDescription.StrokeDescription(
            path,
            profile.doubleTapStartGapMs,
            profile.doubleTapSecondDurationMs,
        )

        val gesture = GestureDescription.Builder()
            .addStroke(firstClick)
            .addStroke(secondClick)
            .build()

        Log.d(
            TAG,
            "执行双击：坐标($x, $y) first=${profile.doubleTapFirstDurationMs}ms " +
                    "gap=${profile.doubleTapStartGapMs}ms second=${profile.doubleTapSecondDurationMs}ms " +
                    "profile=${profile.name}",
        )

        val result = dispatchGestureAwaiting(gesture)
        Log.d(TAG, "双击手势${if (result) "已派发" else "派发失败"}")
        return result
    }

    /**
     * 滑动操作
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @return 是否成功执行滑动
     */
    suspend fun swipeByNode(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "系统版本不支持dispatchGesture")
            return false
        }

        // 构建滑动路径：从起点到终点
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        // 滑动持续时间：300毫秒（快速滑动）
        val swipeDuration = 300L
        val stroke = GestureDescription.StrokeDescription(path, 0, swipeDuration)

        // 构建完整手势
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        Log.d(TAG, "执行滑动：从($startX, $startY)到($endX, $endY)，持续${swipeDuration}ms")

        val result = dispatchGestureAwaiting(gesture)
        Log.d(TAG, "滑动手势${if (result) "已派发" else "派发失败"}")
        return result
    }

    /**
     * 把 dispatchGesture 包成可挂起、等待完成回调的调用，并在手势期间把取消芯片
     * 从 WindowManager 临时摘下，避免 AccessibilityService 模拟的触摸被右上角芯片
     * 吞掉（详见 FloatingWindowManager.detachCancelChipForGesture 的注释）。
     */
    private suspend fun dispatchGestureAwaiting(gesture: GestureDescription): Boolean {
        floatingWindowManager?.detachCancelChipForGesture()
        delay(50) // 等待芯片从窗口层完全移除后再发手势，避免被截获
        return try {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val submitted = dispatchGesture(gesture, callback, null)
                if (!submitted && cont.isActive) cont.resume(false)
            }
        } finally {
            delay(50) // 手势结束后稍作等待再装回芯片，确保系统已完全处理完手势
            floatingWindowManager?.reattachCancelChipAfterGesture()
        }
    }

    /**
     * 在 overlay 隐藏期间执行 block，保证 hide 一定有配对的 restore。
     * 用 try-finally 让任何异常 / 提前 return 都不会破坏 overlay 状态对称。
     *
     * 历史教训（详见 docs/项目优化/悬浮窗执行时偶发消失Bug分析与修复方案_2026-05-07.md
     * 的 Bug B 章节）：旧实现把 hide 和 restore 拆在两个函数里，调用方在
     * "不需要截图" 分支会跳过 restore，导致悬浮窗在 step 0 期间消失 1-3 秒。
     * 新实现强制配对，调用方拿到的总是"已恢复 overlay"的状态。
     */
    suspend fun <T> withOverlayHidden(block: suspend () -> T): T {
        floatingWindowManager?.hideForScreenshot()
        return try {
            delay(150) // 等 overlay 完全从窗口树中移除，避免 root/screenshot 抓到悬浮窗
            block()
        } finally {
            floatingWindowManager?.restoreAfterScreenshot()
        }
    }

    /**
     * 隐藏 overlay → 获取前台包名 → 若非自身则截图 → 恢复 overlay。
     * 单一入口，调用方不需要再操心 hide / restore 配对。
     */
    suspend fun captureForegroundContext(myPackageName: String): Pair<String?, Bitmap?> =
        withOverlayHidden {
            val pkg = readForegroundPackageName()
            val bitmap = if (pkg == myPackageName) {
                null
            } else {
                withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Bitmap?> { cont ->
                        getScreenshot { cont.resume(it) }
                    }
                }
            }
            pkg to bitmap
        }

    /** 读 root 包名并正确 close 节点，fallback 到 _currentApp StateFlow。 */
    private fun readForegroundPackageName(): String? {
        val root = rootInActiveWindow
        return try {
            root?.packageName?.toString() ?: _currentApp.value
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    root?.javaClass?.getMethod("close")?.invoke(root)
                } catch (e: Exception) {
                    @Suppress("DEPRECATION")
                    root?.recycle()
                }
            } else {
                @Suppress("DEPRECATION")
                root?.recycle()
            }
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
                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                                val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                callback(bitmap)
                            } finally {
                                hardwareBuffer.close()
                            }
                        }

                    })
            }
        } else callback(null)
    }
}
