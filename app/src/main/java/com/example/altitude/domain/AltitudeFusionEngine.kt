package com.example.altitude.domain

import kotlin.math.pow

/** 气压测高 + 融合核心，可在 JVM 单测 */
object AltitudeFusionEngine {
    // 标准大气公式，P单位hPa，P0为海平面气压
    fun pressureToAltitude(pressureHpa: Float, p0Hpa: Float): Float {
        return (44330f * (1f - (pressureHpa / p0Hpa).pow(0.1903f))).toFloat()
    }

    // 由已知海拔反推 P0，用于校准
    fun altitudeToP0(pressureHpa: Float, altitudeM: Float): Float {
        return (pressureHpa / (1f - altitudeM / 44330f).pow(1 / 0.1903f)).toFloat()
    }

    // 互补滤波：baro相对精度高，gps绝对稳定。alpha越大越信任气压
    fun fuse(baroAlt: Float?, gpsAlt: Float?, lastFused: Float?, alpha: Float = 0.85f): Float? {
        if (baroAlt == null) return gpsAlt
        if (gpsAlt == null) return baroAlt
        if (lastFused == null) return (baroAlt + gpsAlt) / 2f
        val predicted = baroAlt // 气压响应快，作为预测
        return alpha * predicted + (1 - alpha) * gpsAlt
    }

    // 爬升统计死区过滤，避免抖动虚增
    fun accumulateGain(points: List<Float>, deadband: Float = 3f): Float {
        var gain = 0f
        for (i in 1 until points.size) {
            val d = points[i] - points[i - 1]
            if (d > deadband) gain += d
        }
        return gain
    }
}
