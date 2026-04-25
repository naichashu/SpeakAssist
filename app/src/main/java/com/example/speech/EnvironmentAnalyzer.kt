package com.example.speech

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 环境分析器。封装 AdaptiveVad + SpectralNoiseDetector，
 * 提供「预学习 → 实时分析」的统一接口。
 *
 * 调用顺序：
 * 1. startPreLearning() —— 进入预学习模式
 * 2. processFrame() N 次 —— 前 warmupFrames 帧丢弃（让 AudioRecord 麦克风稳定），
 *    再 preLearningFrames 帧采集 STE，排序后去掉最高 trimRatio 比例的帧（过滤偶发噪声/瞬态），
 *    剩余帧求均值作为初始噪声基底
 * 3. 录音结束后丢弃实例或调 reset() 重新预学习
 *
 * 修剪均值（trimmed mean）原理：把排序后能量最高的 N 帧去掉再求均值。
 * 启动瞬态、偶发噪声（咳嗽 / 敲键盘 / 关门）都集中在高能量端，自然被过滤。
 * 对比简单平均：抗异常值；对比中位数：用更多数据点，方差更低。
 *
 * @param warmupFrames      预热丢弃帧数（每帧 ~40ms @ 16kHz/640samples，2 帧 ≈ 80ms）。
 *                          只丢最严重的启动瞬态；剩余偶发异常由 trimRatio 兜底。
 * @param preLearningFrames 累计采样的帧数（默认 16 ≈ 640ms）
 * @param trimRatio         修剪比例（默认 0.25 即去掉最高 25% 帧）
 */
class EnvironmentAnalyzer(
    private val warmupFrames: Int = 2,
    private val preLearningFrames: Int = 16,
    private val trimRatio: Double = 0.25
) {

    companion object {
        private const val TAG = "EnvironmentAnalyzer"
    }

    private val adaptiveVad = AdaptiveVad()
    private val spectralDetector = SpectralNoiseDetector()

    private var warmupFrameCount = 0
    private val preLearningSteSamples = ArrayList<Double>(preLearningFrames)
    private var preLearningLastSampleCount = 512
    private var isPreLearning = false
    private var initialNoiseFloorRms = 0.0

    private val _noiseLevel = MutableStateFlow(NoiseLevel.LOW)
    val noiseLevel: StateFlow<NoiseLevel> = _noiseLevel.asStateFlow()

    fun startPreLearning() {
        warmupFrameCount = 0
        preLearningSteSamples.clear()
        isPreLearning = true
        adaptiveVad.reset(100.0)
        spectralDetector.reset()
        _noiseLevel.value = NoiseLevel.LOW
        Log.d(TAG, "开始环境预学习（${warmupFrames} 帧预热 + ${preLearningFrames} 帧采样，去高 ${(trimRatio * 100).toInt()}%）")
    }

    /**
     * 处理一帧音频。
     * @return 预学习阶段返回 null；正常阶段返回 SpeechFrameResult。
     */
    fun processFrame(buffer: ByteArray, read: Int): SpeechFrameResult? {
        return if (isPreLearning) {
            processPreLearningFrame(buffer, read)
            null
        } else {
            processNormalFrame(buffer, read)
        }
    }

    private fun processPreLearningFrame(buffer: ByteArray, read: Int) {
        // 前 warmupFrames 帧丢弃 —— AudioRecord 启动最严重的瞬态
        if (warmupFrameCount < warmupFrames) {
            warmupFrameCount++
            return
        }
        val ste = AdaptiveVad.calculateSTE(buffer, read)
        preLearningSteSamples.add(ste)
        preLearningLastSampleCount = read / 2
        if (preLearningSteSamples.size >= preLearningFrames) {
            // 修剪均值：排序后去掉最高 trimRatio 帧
            val sorted = preLearningSteSamples.sorted()
            val trimCount = (preLearningFrames * trimRatio).toInt()
            val keepCount = preLearningFrames - trimCount
            val trimmedSum = sorted.subList(0, keepCount).sum()
            val avgSte = trimmedSum / keepCount
            adaptiveVad.setInitialNoiseFloor(avgSte)
            initialNoiseFloorRms = adaptiveVad.getNoiseFloorRms(preLearningLastSampleCount)
            isPreLearning = false
            Log.d(TAG, "环境预学习完成，噪声基底 RMS=" +
                "${"%.1f".format(initialNoiseFloorRms)}（修剪后保留 $keepCount 帧）")
        }
    }

    private fun processNormalFrame(buffer: ByteArray, read: Int): SpeechFrameResult {
        val ste = AdaptiveVad.calculateSTE(buffer, read)
        val isSpeech = adaptiveVad.isSpeechCandidate(ste)
        adaptiveVad.updateNoiseFloor(ste)
        val noiseReport = spectralDetector.analyze(buffer, read)
        updateNoiseLevel(ste, read, noiseReport)
        return SpeechFrameResult(ste, isSpeech, noiseReport)
    }

    private fun updateNoiseLevel(ste: Double, read: Int, report: NoiseReport) {
        val sampleCount = read / 2
        val rms = AdaptiveVad.steToRms(ste, sampleCount)
        val floorRms = adaptiveVad.getNoiseFloorRms(sampleCount)
        val snr = if (floorRms > 0) rms / floorRms else 1.0

        _noiseLevel.value = when {
            !report.isReliable() -> NoiseLevel.LOW
            report.noiseType == NoiseType.MECHANICAL ||
                report.noiseType == NoiseType.WIND_WHITE -> NoiseLevel.HIGH
            snr > 10 -> NoiseLevel.LOW
            snr > 3 -> NoiseLevel.MEDIUM
            else -> NoiseLevel.HIGH
        }
    }

    fun getAdaptiveVad(): AdaptiveVad = adaptiveVad
    fun getInitialNoiseFloorRms(): Double = initialNoiseFloorRms
    fun isPreLearningInProgress(): Boolean = isPreLearning

    fun reset() = startPreLearning()
}

data class SpeechFrameResult(
    val ste: Double,
    val isSpeechCandidate: Boolean,
    val noiseReport: NoiseReport
)

enum class NoiseLevel {
    LOW,
    MEDIUM,
    HIGH
}
