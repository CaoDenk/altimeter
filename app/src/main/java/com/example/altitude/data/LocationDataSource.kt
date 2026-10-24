package com.example.altitude.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class GpsFix(
    val altitude: Double,
    val verticalAccuracy: Float?,
    val lat: Double,
    val lon: Double,
    val time: Long
)

/** 单颗卫星的状态，用于「详细」面板 */
data class SatelliteInfo(
    val prn: Int,
    val constellation: String,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val elevationDeg: Float,
    val azimuthDeg: Float
)

/** 一次 GNSS 状态回调的汇总 */
data class GnssSignal(
    val visible: Int,
    val usedInFix: Int,
    val withSignal: Int,
    val avgCn0: Float?,
    val satellites: List<SatelliteInfo>
)

const val LocationTag = "AltitudeApp"

/**
 * 系统 LocationManager 直连，不依赖 Google Play Services（国行 ROM 无 GMS 也能用）。
 */
@SuppressLint("MissingPermission")
fun locationFlow(
    ctx: Context,
    onAvailability: (gpsEnabled: Boolean, networkEnabled: Boolean) -> Unit = { _, _ -> },
    onRawFix: (String) -> Unit = {}
) = callbackFlow {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    fun gpsEnabled() =
        runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    fun networkEnabled() =
        runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    // 注意：allProviders 是设备支持的 provider，不代表已启用
    fun reportAvailability() = onAvailability(gpsEnabled(), networkEnabled())

    Log.d(
        LocationTag,
        "providers=${lm.allProviders} gpsEnabled=${gpsEnabled()} networkEnabled=${networkEnabled()} " +
            "masterEnabled=${if (android.os.Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else "n/a"}"
    )
    reportAvailability()

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
            // 冷启动时 gps provider 常先吐一条 alt=0.0 且没有垂直精度的假 fix，
            // 它 hasAltitude() 为 true，能绕过上面那层，必须单独拦掉
            if (acc == null && kotlin.math.abs(l.altitude) < 1.0) {
                Log.d(LocationTag, "gps dropped, bogus zero altitude provider=${l.provider}")
                onRawFix("${l.provider} zero-altitude")
                return
            }
            onRawFix("${l.provider} alt=${l.altitude} vAcc=$acc")
            // 放宽到50m：室内漂移也先显示，界面会标注精度，过滤太严就是永久横线
            if (acc != null && acc > 50f) {
                Log.d(LocationTag, "gps dropped, vAcc too low: $acc")
                onRawFix("dropped vAcc=$acc")
                return
            }
            trySend(GpsFix(l.altitude, acc, l.latitude, l.longitude, l.time))
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(LocationTag, "provider enabled: $provider")
            reportAvailability()
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(LocationTag, "provider disabled: $provider")
            reportAvailability()
        }
    }
    try {
        // 逐个注册，缺 provider 时只记日志不整体失败
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            Log.d(LocationTag, "gps updates requested")
        } catch (e: IllegalArgumentException) {
            Log.w(LocationTag, "no GPS provider", e)
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            Log.d(LocationTag, "network updates requested")
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
        GpsFix(
            it.altitude,
            if (it.hasVerticalAccuracy()) it.verticalAccuracyMeters else null,
            it.latitude,
            it.longitude,
            it.time
        )
    }
}

/**
 * 卫星状态流：可见数、参与定位数、载噪比和每颗卫星的明细。
 * 需要定位权限；没有权限或设备不支持时直接关闭，不影响 GPS/气压显示。
 */
@SuppressLint("MissingPermission")
fun gnssSignalFlow(ctx: Context) = callbackFlow {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var callbacks = 0
    val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var withSignal = 0
            var cn0Sum = 0f
            var cn0Count = 0
            val satellites = ArrayList<SatelliteInfo>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                val cn0 = status.getCn0DbHz(i)
                val inFix = status.usedInFix(i)
                if (inFix) used++
                if (cn0 > 0f) {
                    withSignal++
                    cn0Sum += cn0
                    cn0Count++
                }
                satellites += SatelliteInfo(
                    prn = status.getSvid(i),
                    constellation = constellationName(status.getConstellationType(i)),
                    cn0DbHz = cn0,
                    usedInFix = inFix,
                    elevationDeg = status.getElevationDegrees(i),
                    azimuthDeg = status.getAzimuthDegrees(i)
                )
            }
            // 参与定位的排前面，其次按信号强度排，方便一眼看出哪几颗在干活
            satellites.sortWith(compareByDescending<SatelliteInfo> { it.usedInFix }.thenByDescending { it.cn0DbHz })
            callbacks++
            // 心跳：证明 GnssStatus 回调本身有没有在持续更新
            if (callbacks == 1 || callbacks % 10 == 0) {
                Log.d(
                    LocationTag,
                    "gnss #$callbacks visible=${status.satelliteCount} used=$used withSignal=$withSignal"
                )
            }
            trySend(
                GnssSignal(
                    visible = status.satelliteCount,
                    usedInFix = used,
                    withSignal = withSignal,
                    avgCn0 = if (cn0Count > 0) cn0Sum / cn0Count else null,
                    satellites = satellites
                )
            )
        }
    }
    try {
        lm.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
    } catch (e: SecurityException) {
        Log.w(LocationTag, "gnss status needs location permission", e)
        close(e)
        return@callbackFlow
    } catch (e: Exception) {
        Log.w(LocationTag, "gnss status unavailable", e)
        close(e)
        return@callbackFlow
    }
    awaitClose { lm.unregisterGnssStatusCallback(callback) }
}

private fun constellationName(type: Int): String = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLO"
    GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
    GnssStatus.CONSTELLATION_GALILEO -> "GAL"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "OTHER"
}
