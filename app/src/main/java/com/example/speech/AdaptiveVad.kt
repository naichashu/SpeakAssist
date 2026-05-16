package com.example.speech

import com.example.diagnostics.AppLog
import kotlin.math.sqrt

/**
 * 自适应语音活动检测器。
 *
 * 通过指数移动平均（EMA）跟踪环境噪声基底，
 * 动态调整语音判定阈值。基底只在连续确认无语音时更新，
 * 避免人声被误吸入基底。
 *
 * 用 STE（短时能量，平方和）替代 RMS，省一次 sqrt。
 * STE 等价于 RMS²，在做能量阈值比较时数学等价但更快。
 *
 * @param alpha                EMA 系数，越小基底更新越慢（默认 0.05）
 * @param snrThreshold         信噪比系数（默认 3.5，~11dB）
 * @param confirmSilenceFrames 连续多少帧无语音才更新基底（默认 15 ~500ms）
 */
class AdaptiveVad(
    private val alpha: Double = 0.05,
    private val snrThreshold: Double = 3.5,
    private val confirmSilenceFrames: Int = 15
) {

    companion object {
        private const val TAG = "AdaptiveVad"

        /**
         * 计算短时能量（平方和，不开方）。
         * PCM 16bit little-endian，sign-extension 通过 toShort 完成。
         */
        fun calculateSTE(buffer: ByteArray, read: Int): Double {
            var sum = 0.0
            var i = 0
            while (i + 1 < read) {
                val sample = ((buffer[i].toInt() and 0xFF) or
                    (buffer[i + 1].toInt() shl 8)).toShort().toInt()
                sum += sample.toDouble() * sample.toDouble()
                i += 2
            }
            return sum
        }

        /** STE → RMS。 */
        fun steToRms(ste: Double, sampleCount: Int): Double =
            if (sampleCount > 0) sqrt(ste / sampleCount) else 0.0
    }

    private var noiseFloor = 100.0
    private var silenceFrameCount = 0

    fun isSpeechCandidate(ste: Double): Boolean = ste > noiseFloor * snrThreshold

    /**
     * 用当前帧 STE 更新基底。
     * @return true 表示基底本次被更新（说明已确认环境处于纯噪声状态）
     */
    fun updateNoiseFloor(ste: Double): Boolean {
        if (ste < noiseFloor * snrThreshold) {
            silenceFrameCount++
            if (silenceFrameCount >= confirmSilenceFrames) {
                noiseFloor = (1 - alpha) * noiseFloor + alpha * ste
                silenceFrameCount = 0
                AppLog.v(TAG, "噪声基底更新: ${noiseFloor.toInt()} STE")
                return true
            }
        } else {
            silenceFrameCount = 0
        }
        return false
    }

    fun setInitialNoiseFloor(steFloor: Double) {
        noiseFloor = steFloor
    }

    fun reset(baseLevel: Double = 100.0) {
        noiseFloor = baseLevel
        silenceFrameCount = 0
    }

    fun getNoiseFloorSte(): Double = noiseFloor

    fun getNoiseFloorRms(sampleCount: Int): Double =
        steToRms(noiseFloor, sampleCount)

    fun getSpeechThresholdRms(sampleCount: Int): Double =
        steToRms(noiseFloor * snrThreshold, sampleCount)
}
