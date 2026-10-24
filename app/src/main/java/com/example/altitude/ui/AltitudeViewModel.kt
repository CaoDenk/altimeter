package com.example.altitude.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.altitude.data.BarometerDataSource
import com.example.altitude.data.LocationTag
import com.example.altitude.data.LogExporter
import com.example.altitude.data.SatelliteInfo
import com.example.altitude.data.gnssSignalFlow
import com.example.altitude.data.lastKnownFix
import com.example.altitude.data.locationFlow
import com.example.altitude.domain.AltitudeFusionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class AltitudeUiState(
    val fusedAlt: Float? = null,
    val gpsAlt: Float? = null,
    val gpsAccuracy: Float? = null,
    val pressure: Float? = null,
    val p0: Float = 1013.25f,
    val hasBarometer: Boolean = false,
    val status: String = "初始化...",
    val gpsCount: Int = 0,
    val availability: Boolean? = null,
    val gpsEnabled: Boolean? = null,
    val networkEnabled: Boolean? = null,
    val fineLocation: Boolean? = null,
    val lastRawFix: String? = null,
    val lastError: String? = null,
    // 位置
    val latitude: Double? = null,
    val longitude: Double? = null,
    // 卫星
    val satellitesVisible: Int? = null,
    val satellitesUsed: Int? = null,
    val satellitesWithSignal: Int? = null,
    val gnssAvgCn0: Float? = null,
    val satellites: List<SatelliteInfo> = emptyList(),
    // 当前海拔是缓存定位还是实时定位
    val fixFromCache: Boolean = false,
    val lastFixTimeMs: Long? = null
)

class AltitudeViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(AltitudeUiState())
    val state: StateFlow<AltitudeUiState> = _state.asStateFlow()

    private var baroStarted = false
    private var gpsStarted = false
    private var lastKnownJob: Job? = null
    private var gnssJob: Job? = null
    private var locationJob: Job? = null

    fun hasLocationPermission(): Boolean {
        val ctx = getApplication<Application>()
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun hasFineLocationPermission(): Boolean {
        val ctx = getApplication<Application>()
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 气压采集：无需权限，可独立显示，室内也有数 */
    fun startBaro() {
        if (baroStarted) return
        baroStarted = true
        val ctx = getApplication<Application>().applicationContext
        val hasBaro = BarometerDataSource.hasBarometer(ctx)
        Log.d(LocationTag, "startBaro hasBarometer=$hasBaro")
        _state.update { it.copy(hasBarometer = hasBaro) }
        if (!hasBaro) {
            _state.update { it.copy(status = "无气压计，等待GPS...") }
            return
        }
        viewModelScope.launch {
            BarometerDataSource.pressureFlow(ctx)
                .catch { e ->
                    Log.w(LocationTag, "barometer failed", e)
                    _state.update { it.copy(status = "气压传感器失败: ${e.message}", lastError = e.message) }
                }
                .collect { p ->
                    val baroAlt = AltitudeFusionEngine.pressureToAltitude(p, _state.value.p0)
                    val fused = AltitudeFusionEngine.fuse(baroAlt, _state.value.gpsAlt, _state.value.fusedAlt)
                    _state.update {
                        it.copy(
                            pressure = p,
                            fusedAlt = fused,
                            status = when {
                                it.fixFromCache -> "缓存定位，等待 GPS 更新"
                                it.gpsAlt == null -> "气压模式（GPS无信号）"
                                else -> "定位正常"
                            }
                        )
                    }
                }
        }
    }

    /** GPS采集：需要定位权限；失败只更新状态，不杀死气压显示 */
    fun startGps() {
        if (gpsStarted) return
        gpsStarted = true
        val ctx = getApplication<Application>().applicationContext
        if (!hasLocationPermission()) {
            _state.update { it.copy(status = "请授予定位权限") }
            gpsStarted = false // 授权后允许重试
            return
        }
        Log.d(LocationTag, "startGps")
        val fine = hasFineLocationPermission()
        _state.update {
            it.copy(
                fineLocation = fine,
                status = if (!fine) "仅大致位置，请授予精确位置" else it.status
            )
        }
        Log.d(
            LocationTag,
            "permissions fine=$fine coarse=${ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED}"
        )
        // 先取一次最后已知位置，秒出数，不用干等卫星
        lastKnownJob = viewModelScope.launch {
            try {
                val last = lastKnownFix(ctx)
                if (last != null) onGpsFix(
                    last.altitude.toFloat(),
                    last.verticalAccuracy,
                    last.lat,
                    last.lon,
                    fromCache = true,
                    fixTime = last.time
                )
            } catch (e: SecurityException) {
                _state.update { it.copy(status = "请授予定位权限", lastError = e.message) }
            } catch (e: Exception) {
                Log.d(LocationTag, "lastLocation failed: ${e.message}")
            }
        }
        // 卫星状态：和 GPS 一起启动，失败只影响「详细」面板
        gnssJob = viewModelScope.launch {
            gnssSignalFlow(ctx)
                .catch { e -> Log.d(LocationTag, "gnss status failed: ${e.message}") }
                .collect { g ->
                    _state.update {
                        it.copy(
                            satellitesVisible = g.visible,
                            satellitesUsed = g.usedInFix,
                            satellitesWithSignal = g.withSignal,
                            gnssAvgCn0 = g.avgCn0,
                            satellites = g.satellites
                        )
                    }
                }
        }
        locationJob = viewModelScope.launch {
            locationFlow(
                ctx,
                onAvailability = { gpsOn, netOn ->
                    _state.update {
                        it.copy(
                            availability = gpsOn || netOn,
                            gpsEnabled = gpsOn,
                            networkEnabled = netOn,
                            status = when {
                                it.fineLocation == false -> "仅大致位置，请授予精确位置"
                                !gpsOn && netOn -> "GPS 未开启，仅网络定位（无海拔）"
                                !gpsOn && !netOn -> "系统定位不可用，请打开定位开关"
                                gpsOn && it.fusedAlt == null -> "搜索GPS信号中（请到室外等待1-2分钟）"
                                else -> it.status
                            }
                        )
                    }
                },
                onRawFix = { raw -> _state.update { it.copy(lastRawFix = raw) } }
            )
                .catch { e ->
                    Log.w(LocationTag, "gps flow failed", e)
                    _state.update { it.copy(status = "定位失败: ${e.message}", lastError = e.message) }
                }
                .collect { g ->
                    onGpsFix(
                        g.altitude.toFloat(),
                        g.verticalAccuracy,
                        g.lat,
                        g.lon,
                        fromCache = false,
                        fixTime = g.time
                    )
                }
        }
    }

    /** 重新注册定位与卫星监听：GNSS 卡死或长时间没有 fix 时手动救活 */
    fun restartGps() {
        Log.d(LocationTag, "restartGps")
        lastKnownJob?.cancel()
        gnssJob?.cancel()
        locationJob?.cancel()
        gpsStarted = false
        _state.update { it.copy(status = "重新搜索 GPS...", lastError = null) }
        startGps()
    }

    private fun onGpsFix(
        gpsAlt: Float,
        acc: Float?,
        lat: Double,
        lon: Double,
        fromCache: Boolean,
        fixTime: Long
    ) {
        // 实时 fix 的兜底校验：0 米或瞬间跳变上千米都当假定位丢掉，保留上一个可信值
        if (!fromCache) {
            val prev = _state.value.fusedAlt
            if (gpsAlt == 0f) {
                Log.w(LocationTag, "gps fix rejected: zero altitude")
                return
            }
            if (prev != null && kotlin.math.abs(gpsAlt - prev) > 1000f) {
                Log.w(LocationTag, "gps fix rejected: jump ${prev} -> $gpsAlt")
                return
            }
        }
        Log.d(LocationTag, "gps fix accepted alt=$gpsAlt vAcc=$acc lat=$lat lon=$lon cached=$fromCache")
        val fused = if (_state.value.pressure != null && _state.value.hasBarometer) {
            AltitudeFusionEngine.fuse(
                AltitudeFusionEngine.pressureToAltitude(_state.value.pressure!!, _state.value.p0),
                gpsAlt,
                _state.value.fusedAlt
            )
        } else {
            gpsAlt
        }
        _state.update {
            it.copy(
                gpsAlt = gpsAlt,
                gpsAccuracy = acc,
                fusedAlt = fused,
                status = if (fromCache) "缓存定位，等待 GPS 更新" else "定位正常",
                gpsCount = it.gpsCount + if (fromCache) 0 else 1,
                latitude = lat,
                longitude = lon,
                fixFromCache = fromCache,
                lastFixTimeMs = fixTime
            )
        }
    }

    /** 手动校准：输入已知海拔，反推 P0 */
    fun calibrateWithKnownAltitude(pressure: Float, knownAlt: Float) {
        val p0 = AltitudeFusionEngine.altitudeToP0(pressure, knownAlt)
        _state.update { it.copy(p0 = p0, status = "已校准 P0=${"%.1f".format(p0)}") }
    }

    /** 导出诊断日志，返回分享 Intent 用的文件 */
    suspend fun exportLogFile(): File {
        val ctx = getApplication<Application>().applicationContext
        return LogExporter.export(ctx, _state.value)
    }

    fun shareFile(file: File) {
        val ctx = getApplication<Application>().applicationContext
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "发送诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
