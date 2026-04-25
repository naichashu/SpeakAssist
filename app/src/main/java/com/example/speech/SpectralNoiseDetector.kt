package com.example.speech

/**
 * 频域噪声检测器。
 *
 * 用过零率（ZCR）和低频能量比近似频域分析，无 FFT。
 * 历史窗口取均值后再分类，避免单帧抖动。
 *
 * 特征参考：
 * - 人声 ZCR 3-10%，机械噪声 <2%，风声 >30%
 * - 人声能量集中在 300Hz-3.4kHz，机械噪声低频集中
 *
 * @param historySize 滑动窗口帧数，默认 20（~640ms）
 */
class SpectralNoiseDetector(private val historySize: Int = 20) {

    private val zcrHistory = ArrayDeque<Double>()
    private val lfRatioHistory = ArrayDeque<Double>()

    fun calculateZcr(buffer: ByteArray, read: Int): Double {
        var zcr = 0
        var prevSample = 0
        var i = 0
        while (i + 1 < read) {
            val sample = ((buffer[i].toInt() and 0xFF) or
                (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            if (prevSample xor sample < 0) zcr++
            prevSample = sample
            i += 2
        }
        val sampleCount = read / 2
        return if (sampleCount > 0) zcr.toDouble() / sampleCount else 0.0
    }

    /**
     * 低频能量占比。相邻样本差分近似高频分量；
     * 低频能量 = 总能量 - 高频能量。
     */
    fun calculateLowFrequencyRatio(buffer: ByteArray, read: Int): Double {
        var highFreqEnergy = 0L
        var totalEnergy = 0L
        var prevSample = 0
        var hasPrevSample = false
        var i = 0
        while (i + 1 < read) {
            val sample = ((buffer[i].toInt() and 0xFF) or
                (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            totalEnergy += sample.toLong() * sample.toLong()
            if (hasPrevSample) {
                val diff = (sample - prevSample).toLong()
                highFreqEnergy += diff * diff
            }
            prevSample = sample
            hasPrevSample = true
            i += 2
        }
        return if (totalEnergy > 0)
            1.0 - highFreqEnergy.toDouble() / totalEnergy
        else 0.5
    }

    fun analyze(buffer: ByteArray, read: Int): NoiseReport {
        val zcr = calculateZcr(buffer, read)
        val lfRatio = calculateLowFrequencyRatio(buffer, read)
        zcrHistory.addLast(zcr)
        lfRatioHistory.addLast(lfRatio)
        if (zcrHistory.size > historySize) zcrHistory.removeFirst()
        if (lfRatioHistory.size > historySize) lfRatioHistory.removeFirst()

        val avgZcr = zcrHistory.average()
        val avgLfRatio = lfRatioHistory.average()
        return NoiseReport(
            zcr = avgZcr,
            lowFreqRatio = avgLfRatio,
            noiseType = classifyNoise(avgZcr, avgLfRatio),
            sampleCount = zcrHistory.size
        )
    }

    private fun classifyNoise(avgZcr: Double, avgLfRatio: Double): NoiseType = when {
        avgZcr < 0.02 && avgLfRatio > 0.75 -> NoiseType.MECHANICAL
        avgZcr > 0.30 -> NoiseType.WIND_WHITE
        avgZcr in 0.03..0.10 && avgLfRatio > 0.60 -> NoiseType.CROWD
        else -> NoiseType.QUIET
    }

    fun reset() {
        zcrHistory.clear()
        lfRatioHistory.clear()
    }
}

data class NoiseReport(
    val zcr: Double,
    val lowFreqRatio: Double,
    val noiseType: NoiseType,
    val sampleCount: Int
) {
    /** 历史窗口未达半满时判断不可靠，UI 不应据此告警。 */
    fun isReliable(): Boolean = sampleCount >= 10
}

enum class NoiseType {
    QUIET,
    MECHANICAL,
    WIND_WHITE,
    CROWD
}
