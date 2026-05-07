package com.example.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * 7 根柱状音波动画。
 * - [setVolume] 由 BaiduSpeechManager.onVolumeChanged 驱动（0..100）
 * - [start] / [stop] 控制内部动画器
 *
 * 设计：
 * - 一个无限循环的 [ValueAnimator] 推进相位 0..1
 * - 每根柱子用相位错开 sin(phase + i*0.6)，外加 [volumeFactor] 决定整体幅度
 * - volume 输入做指数滤波（旧 0.6 + 新 0.4），避免单帧爆冲导致柱子抖动
 *
 * 不变量：
 * - [onDetachedFromWindow] 必须 cancel 动画器，避免 Activity 销毁后泄漏
 *
 * 用法：
 * ```kotlin
 * waveformView.start()
 * // onVolumeChanged 回调里：
 * waveformView.setVolume(volume)
 * // 录音结束：
 * waveformView.stop()
 * ```
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 7
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var animationProgress = 0f

    /** 0..1，下限 0.2 让静音时柱子也有微动，避免一条平线像死掉了 */
    private var volumeFactor = MIN_VOLUME_FACTOR

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationProgress = it.animatedValue as Float
            invalidate()
        }
    }

    /**
     * 由 BaiduSpeechManager.onVolumeChanged(0..100) 驱动。
     * 用指数滤波平滑过渡，新值 0.4 / 旧值 0.6，避免单帧爆冲。
     */
    fun setVolume(volume: Int) {
        val target = (volume.coerceIn(0, 100) / 100f).coerceAtLeast(MIN_VOLUME_FACTOR)
        volumeFactor = volumeFactor * 0.6f + target * 0.4f
    }

    /** 启动动画。已在跑则忽略。 */
    fun start() {
        if (!animator.isRunning) {
            animator.start()
        }
    }

    /** 停止动画并复位。 */
    fun stop() {
        animator.cancel()
        volumeFactor = MIN_VOLUME_FACTOR
        invalidate()
    }

    /**
     * Activity 销毁或 View 从窗口移除时自动 cancel 动画器。
     * 不变量：[ValueAnimator] 必须在 detach 时取消，否则会引用 View 导致泄漏。
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        // 7 根柱子 + 6 个间隙：一共 13 等份，柱子和间隙等宽
        val totalSlots = barCount * 2 - 1
        val barWidth = width.toFloat() / totalSlots
        val centerY = height / 2f
        val maxHalf = height * 0.45f
        val minHalf = height * 0.12f

        for (i in 0 until barCount) {
            // 每根柱子相位错开 0.6 弧度，整体看上去像左到右的波动
            val phase = (animationProgress * 2 * Math.PI + i * 0.6).toFloat()
            val osc = ((sin(phase.toDouble()) + 1) / 2).toFloat()  // 0..1
            val half = minHalf + (maxHalf - minHalf) * volumeFactor * osc
            val left = i * barWidth * 2
            val right = left + barWidth
            canvas.drawRoundRect(
                left, centerY - half, right, centerY + half,
                barWidth / 2, barWidth / 2, paint
            )
        }
    }

    companion object {
        private const val MIN_VOLUME_FACTOR = 0.2f
    }
}
