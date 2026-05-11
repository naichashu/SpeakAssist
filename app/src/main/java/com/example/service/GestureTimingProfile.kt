package com.example.service

import android.os.Build
import android.util.Log

/**
 * 设备手势时序 profile。按 Build.BRAND / MANUFACTURER 选择合适的时长。
 *
 * 背景：AccessibilityService.dispatchGesture 在国产 ROM（特别是华为 / 荣耀
 * MagicOS / EMUI）上经常被吞掉。100ms 单击在荣耀机上频繁失败（参见 docs/
 * 适配调整/荣耀手机点击无反应分析与修复方案_2026-05-08）。
 *
 * 时长选取依据：
 *
 * 有官方依据的硬数据：
 * - Android `getLongPressTimeout()` 默认 500ms（长按阈值），来源：Android
 *   framework 源码。tap 时长必须远低于此。
 * - 真人 tap 实测均值 133ms ± 83ms（Asakawa et al. 2017，n=13）。即
 *   p50 ≈ 133ms、p70 ≈ 180ms、p85 ≈ 220ms。
 *
 * 仍是推断、查不到官方依据的：
 * - 国产 OEM 对 dispatchGesture 注入事件的具体过滤阈值（仅有 XDA 同症状
 *   报告 + 项目内对照实验作为间接证据：100ms tap 失败、300ms swipe 正常）。
 * - 三档具体值（180/200/220）以及华为/小米/Pixel 的具体分档：基于真人 tap
 *   分布 + 已知故障数据点拍的，没有"测出来"的依据。
 *
 * 三档设计：
 *
 * - default 180ms：真人 tap p70，给未知品牌设备的合理初值。
 * - balanced 200ms：MIUI / ColorOS 等略严的 ROM 留更多余量（p78）。
 * - strict 220ms：华为 EMUI / 荣耀 MagicOS（已知故障点），p85，最大化容错。
 * - 双击间隔 350-380ms：第一次 up 到第二次 down 间隔 ~170ms，在
 *   `getDoubleTapTimeout()` 300ms 内，会被识别为双击。
 * - 三档都远小于 long press 500ms，不会被误判为长按。
 *
 * 具体值仍需在真实设备上跑成功率统计校准。
 */
data class GestureTimingProfile(
    val name: String,
    val tapDurationMs: Long,
    val doubleTapFirstDurationMs: Long,
    val doubleTapSecondDurationMs: Long,
    val doubleTapStartGapMs: Long,
    /**
     * 是否在 dispatchGesture 失败时启用 `AccessibilityNodeInfo.ACTION_CLICK` 兜底。
     *
     * 仅 strict（已知故障机型）打开。default / balanced 关闭——它们
     * dispatchGesture 不失败，不需要兜底；关闭后兜底分支的代码路径完全不会执行，
     * 对正常机型零风险。
     */
    val enableActionClickFallback: Boolean,
) {
    companion object {
        private const val TAG = "GestureTimingProfile"

        val DEFAULT = GestureTimingProfile(
            name = "default",
            tapDurationMs = 180,
            doubleTapFirstDurationMs = 180,
            doubleTapSecondDurationMs = 180,
            doubleTapStartGapMs = 350,
            enableActionClickFallback = false,
        )

        val BALANCED = GestureTimingProfile(
            name = "balanced",
            tapDurationMs = 200,
            doubleTapFirstDurationMs = 200,
            doubleTapSecondDurationMs = 200,
            doubleTapStartGapMs = 360,
            enableActionClickFallback = false,
        )

        val STRICT = GestureTimingProfile(
            name = "strict",
            tapDurationMs = 220,
            doubleTapFirstDurationMs = 220,
            doubleTapSecondDurationMs = 220,
            doubleTapStartGapMs = 380,
            enableActionClickFallback = true,
        )

        private val STRICT_KEYWORDS = listOf("huawei", "honor")
        private val BALANCED_KEYWORDS = listOf("xiaomi", "redmi", "oppo", "vivo", "realme", "oneplus")

        val current: GestureTimingProfile by lazy {
            val brand = (Build.BRAND ?: "").lowercase()
            val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
            val profile = when {
                STRICT_KEYWORDS.any { it in brand || it in manufacturer } -> STRICT
                BALANCED_KEYWORDS.any { it in brand || it in manufacturer } -> BALANCED
                else -> DEFAULT
            }
            Log.i(
                TAG,
                "selected profile=${profile.name} tap=${profile.tapDurationMs}ms " +
                        "brand=$brand manufacturer=$manufacturer",
            )
            profile
        }
    }
}
