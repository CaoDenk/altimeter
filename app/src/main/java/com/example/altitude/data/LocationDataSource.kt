package com.example.altitude.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class GpsFix(val altitude: Double, val verticalAccuracy: Float?, val lat: Double, val lon: Double)

const val LocationTag = "AltitudeApp"

/**
 * 系统 LocationManager 直连，不依赖 Google Play Services（国行 ROM 无 GMS 也能用）。
 */
@SuppressLint("MissingPermission")
fun locationFlow(
    ctx: Context,
    onAvailability: (Boolean) -> Unit = {},
    onRawFix: (String) -> Unit = {}
) = callbackFlow {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    fun anyEnabled() =
        runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)

    Log.d(LocationTag, "providers=${lm.allProviders} anyEnabled=${anyEnabled()}")
    onAvailability(anyEnabled())

    val listener = object : LocationListener {
        override fun onLocationChanged(l: android.location.Location) {
            // 无高程的 fix（常见 network/gps 冷启动吐 0.0）必须丢掉，否则海拔会被瞬间拉到 0
            if (!l.hasAltitude()) {
                Log.d(LocationTag, "gps dropped, no altitude provider=${l.provider}")
                onRawFix("${l.provider} no-altitude")
                return
            }
            val acc = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null
            Log.d(LocationTag, "gps raw alt=${l.altitude} vAcc=$acc provider=${l.provider}")
            onRawFix("${l.provider} alt=${l.altitude} vAcc=$acc")
            // 放宽到50m：室内漂移也先显示，界面会标注精度，过滤太严就是永久横线
            if (acc != null && acc > 50f) {
                Log.d(LocationTag, "gps dropped, vAcc too low: $acc")
                onRawFix("dropped vAcc=$acc")
                return
            }
            trySend(GpsFix(l.altitude, acc, l.latitude, l.longitude))
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(LocationTag, "provider enabled: $provider")
            onAvailability(true)
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(LocationTag, "provider disabled: $provider")
            onAvailability(anyEnabled())
        }
    }
    try {
        // 逐个注册，缺 provider 时只记日志不整体失败
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, listener, Looper.getMainLooper())
        } catch (e: IllegalArgumentException) {
            Log.w(LocationTag, "no GPS provider", e)
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 2f, listener, Looper.getMainLooper())
        } catch (e: IllegalArgumentException) {
            Log.w(LocationTag, "no network provider", e)
        }
    } catch (e: SecurityException) {
        Log.w(LocationTag, "location permission missing", e)
        close(e)
        return@callbackFlow
    }
    awaitClose { lm.removeUpdates(listener) }
}

/** 最后已知位置：GPS 优先，其次基站，都没有返回 null */
@SuppressLint("MissingPermission")
fun lastKnownFix(ctx: Context): GpsFix? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val loc = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        ?.takeIf { it.hasAltitude() }
        ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            ?.takeIf { it.hasAltitude() }
    Log.d(LocationTag, "lastKnown=${loc?.altitude} provider=${loc?.provider}")
    return loc?.let {
        GpsFix(it.altitude, if (it.hasVerticalAccuracy()) it.verticalAccuracyMeters else null, it.latitude, it.longitude)
    }
}
