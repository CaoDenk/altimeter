package com.example.altitude.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.altitude.data.LogExporter
import com.example.altitude.data.SatelliteInfo
import com.example.altitude.ui.theme.Accent
import com.example.altitude.ui.theme.Bad
import com.example.altitude.ui.theme.Good
import com.example.altitude.ui.theme.Muted
import com.example.altitude.ui.theme.Warn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val BgTop = Color(0xFF070C15)
private val BgBottom = Color(0xFF101B2D)
private val CardBg = Color(0x14FFFFFF)
private val CardBorder = Color(0x1AFFFFFF)

@Composable
fun HomeScreen(vm: AltitudeViewModel, onRequestPermission: () -> Unit = {}) {
    val s by vm.state.collectAsState()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var showSatellites by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom, BgTop)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 30.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppMark()
                Spacer(Modifier.width(10.dp))
                Text(
                    "ALTIMETER",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp
                )
                Spacer(Modifier.weight(1f))
                Text(LogExporter.appVersion(ctx), color = Muted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(14.dp))
            StatusChip(s.status)
            Spacer(Modifier.height(18.dp))

            AltitudeHero(s)

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val used = s.satellitesUsed ?: 0
                val withSignal = s.satellitesWithSignal ?: 0
                val liveFix = !s.fixFromCache && s.gpsAlt != null
                val gpsOff = s.gpsEnabled == false
                val coarseOnly = s.fineLocation == false
                val level = when {
                    gpsOff || coarseOnly -> 0
                    used > 0 -> signalLevel(used, s.gnssAvgCn0)
                    liveFix -> s.gpsAccuracy?.let { accuracyLevel(it) } ?: 1
                    withSignal > 0 -> 1
                    else -> 0
                }
                val color = when {
                    gpsOff || coarseOnly -> Bad
                    used > 0 -> signalColor(level)
                    liveFix -> if (level >= 3) Good else Warn
                    withSignal > 0 -> Warn
                    else -> Bad
                }
                val label = when {
                    gpsOff -> "GPS 未开启"
                    coarseOnly -> "仅大致位置"
                    used > 0 -> signalLabel(level)
                    liveFix -> "已定位"
                    withSignal > 0 -> "搜星中"
                    else -> "无信号"
                }
                MetricCard("GPS 信号", Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalBars(level, color)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            color = Color.White,
                            fontSize = if (gpsOff || coarseOnly) 13.sp else 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            gpsOff -> "请打开系统 GPS"
                            coarseOnly -> "请开启精确位置"
                            used > 0 -> s.gnssAvgCn0?.let { "平均 ${"%.0f".format(it)} dB" } ?: "已参与定位"
                            withSignal > 0 -> "${withSignal} 颗有信号"
                            else -> "等待卫星"
                        },
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
                MetricCard("卫星", Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            s.satellitesUsed?.toString() ?: "--",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            " / ${s.satellitesVisible ?: "--"}",
                            color = Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                    Text("已定位 / 可见", color = Muted, fontSize = 10.sp)
                }
                MetricCard("气压", Modifier.weight(1f)) {
                    Text(
                        s.pressure?.let { "%.1f".format(it) } ?: "--",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        if (s.hasBarometer) "hPa" else "hPa · 无传感器",
                        color = if (s.hasBarometer) Muted else Warn,
                        fontSize = 10.sp
                    )
                }
            }

            if (s.gpsEnabled != false && s.fineLocation != false &&
                ((s.satellitesUsed ?: 0) == 0 || s.fixFromCache)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { vm.restartGps() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("重新搜索卫星", fontSize = 11.sp, color = Accent)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            CoordinateCard(
                latitude = s.latitude,
                longitude = s.longitude,
                isCache = s.fixFromCache,
                onCopy = {
                    val lat = s.latitude
                    val lon = s.longitude
                    if (lat != null && lon != null) {
                        clipboard.setText(AnnotatedString("${fmt6(lat)}, ${fmt6(lon)}"))
                        Toast.makeText(ctx, "已复制经纬度", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))
            SatelliteSection(
                satellites = s.satellites,
                expanded = showSatellites,
                onToggle = { showSatellites = !showSatellites }
            )

            s.status.takeIf { it.contains("权限") }?.let {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        vm.startGps()
                        onRequestPermission()
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent.copy(alpha = 0.18f),
                        contentColor = Accent
                    )
                ) { Text("授予定位权限", fontSize = 14.sp) }
            }

            if (s.gpsEnabled == false) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Warn.copy(alpha = 0.16f),
                        contentColor = Warn
                    )
                ) { Text("打开系统定位设置", fontSize = 14.sp) }
            }

            if (s.fineLocation == false) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${ctx.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Warn.copy(alpha = 0.16f),
                        contentColor = Warn
                    )
                ) { Text("在设置中开启精确位置", fontSize = 14.sp) }
            }

            Spacer(Modifier.height(12.dp))
            DiagnosticsSection(
                state = s,
                expanded = showDiagnostics,
                onToggle = { showDiagnostics = !showDiagnostics }
            )

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    exporting = true
                    scope.launch {
                        try {
                            val file = vm.exportLogFile()
                            vm.shareFile(file)
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            exporting = false
                        }
                    }
                },
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF00202E),
                    disabledContainerColor = Accent.copy(alpha = 0.3f),
                    disabledContentColor = Color(0xFF00202E)
                )
            ) {
                Text(if (exporting) "导出中..." else "导出诊断日志", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "气压计 × GPS 互补滤波",
                color = Muted.copy(alpha = 0.6f),
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun AppMark() {
    Canvas(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
    ) {
        val w = size.width
        val h = size.height
        // 底：和启动图标一致的深蓝渐变
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF16273F), Color(0xFF070C15))
            ),
            size = size
        )
        // 太阳
        drawCircle(
            color = Color(0xFFFFD54F),
            radius = w * 0.085f,
            center = Offset(w * 0.73f, h * 0.24f)
        )
        // 远山
        drawPath(
            Path().apply {
                moveTo(0f, h * 0.74f)
                lineTo(w * 0.30f, h * 0.40f)
                lineTo(w * 0.46f, h * 0.58f)
                lineTo(w * 0.62f, h * 0.42f)
                lineTo(w, h * 0.74f)
                close()
            },
            Color(0xFF3E5C82)
        )
        // 主雪山
        drawPath(
            Path().apply {
                moveTo(w * 0.08f, h * 0.84f)
                lineTo(w * 0.47f, h * 0.42f)
                lineTo(w * 0.88f, h * 0.84f)
                close()
            },
            Color.White
        )
        // 山体右侧阴影
        drawPath(
            Path().apply {
                moveTo(w * 0.47f, h * 0.42f)
                lineTo(w * 0.88f, h * 0.84f)
                lineTo(w * 0.47f, h * 0.84f)
                close()
            },
            Color(0xFF90CAF9)
        )
        // 峰顶旗杆 + 旗
        val poleX = w * 0.47f
        drawLine(
            color = Color(0xFFFF7043),
            start = Offset(poleX, h * 0.42f),
            end = Offset(poleX, h * 0.24f),
            strokeWidth = w * 0.028f
        )
        drawPath(
            Path().apply {
                moveTo(poleX, h * 0.24f)
                lineTo(poleX + w * 0.13f, h * 0.275f)
                lineTo(poleX, h * 0.31f)
                close()
            },
            Color(0xFFFF7043)
        )
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = statusColor(status)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(
                status,
                color = color,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AltitudeHero(s: AltitudeUiState) {
    val age = rememberFixAge(s.lastFixTimeMs)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("当前海拔", color = Muted, fontSize = 12.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    s.fusedAlt?.let { "%.1f".format(it) } ?: "--",
                    color = Color.White,
                    fontSize = 66.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 70.sp
                )
                Text(
                    " m",
                    color = Muted,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(s.gpsAccuracy?.let { "垂直误差约 ±${"%.1f".format(it)} m" } ?: "误差未知")
                    if (age != null) append(" · 定位 $age")
                },
                color = Muted,
                fontSize = 12.sp
            )
            if (s.fixFromCache) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (age != null) "缓存定位 · 数据 $age，等待新的 GPS 信号"
                    else "缓存定位 · 正在等待新的 GPS 信号",
                    color = Warn,
                    fontSize = 11.sp
                )
            }
            if (!s.hasBarometer) {
                Spacer(Modifier.height(8.dp))
                Text("无气压计 · 已降级为 GPS 模式", color = Warn, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Text(title, color = Muted, fontSize = 10.sp, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun SignalBars(level: Int, color: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(6, 10, 14, 18, 22).forEachIndexed { index, height ->
            Box(
                Modifier
                    .width(4.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < level) color else Color.White.copy(alpha = 0.12f))
            )
        }
    }
}

@Composable
private fun CoordinateCard(latitude: Double?, longitude: Double?, isCache: Boolean, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (isCache) "位置 · 缓存" else "位置",
                    color = if (isCache) Warn else Muted,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(7.dp))
                CoordinateRow("纬度", latitude?.let { fmt6(it) } ?: "--")
                Spacer(Modifier.height(4.dp))
                CoordinateRow("经度", longitude?.let { fmt6(it) } ?: "--")
            }
            TextButton(onClick = onCopy, enabled = latitude != null && longitude != null) {
                Text("复制", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CoordinateRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(34.dp))
        Text(
            value,
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SatelliteSection(satellites: List<SatelliteInfo>, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("卫星详细", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (satellites.isEmpty()) "暂无数据" else "${satellites.size} 颗",
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(if (expanded) "收起 ▴" else "展开 ▾", color = Accent, fontSize = 12.sp)
        }
        AnimatedVisibility(visible = expanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (satellites.isEmpty()) {
                        Text(
                            "还没有卫星数据。到室外空旷处等待 1–2 分钟。",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    } else {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Text("星座", color = Muted, fontSize = 9.sp, modifier = Modifier.width(46.dp))
                            Text("PRN", color = Muted, fontSize = 9.sp, modifier = Modifier.width(48.dp))
                            Text("C/N0", color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                            Text("定位", color = Muted, fontSize = 9.sp)
                        }
                        satellites.forEachIndexed { index, satellite ->
                            SatelliteRow(satellite)
                            if (index != satellites.lastIndex) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.06f),
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SatelliteRow(satellite: SatelliteInfo) {
    val color = constellationColor(satellite.constellation)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(5.dp)) {
            Text(
                satellite.constellation,
                color = color,
                fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            "#${satellite.prn}",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(44.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth((satellite.cn0DbHz / 50f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (satellite.cn0DbHz > 0f) "%.0f".format(satellite.cn0DbHz) else "--",
            color = Muted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (satellite.usedInFix) Good else Color.White.copy(alpha = 0.15f))
        )
    }
}

@Composable
private fun DiagnosticsSection(state: AltitudeUiState, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("诊断信息", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "收起 ▴" else "展开 ▾", color = Accent, fontSize = 12.sp)
        }
        AnimatedVisibility(visible = expanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    InfoRow("定位可用性", when (state.availability) {
                        true -> "可用"
                        false -> "不可用（检查系统定位开关）"
                        null -> "未知"
                    })
                    InfoRow("精确定位", when (state.fineLocation) {
                        true -> "已授权"
                        false -> "仅大致位置"
                        null -> "未知"
                    })
                    InfoRow("GPS 开关", when (state.gpsEnabled) {
                        true -> "开启"
                        false -> "关闭"
                        null -> "未知"
                    })
                    InfoRow("网络定位", when (state.networkEnabled) {
                        true -> "开启"
                        false -> "关闭"
                        null -> "未知"
                    })
                    InfoRow("GPS 海拔", state.gpsAlt?.let { "%.1f m".format(it) } ?: "--")
                    InfoRow("定位来源", when {
                        state.gpsAlt == null -> "--"
                        state.fixFromCache -> "缓存定位"
                        else -> "实时 GPS"
                    })
                    InfoRow(
                        "有信号卫星",
                        state.satellitesWithSignal?.let {
                            "$it / ${state.satellitesVisible ?: "--"}"
                        } ?: "--"
                    )
                    InfoRow("P0", "%.1f hPa".format(state.p0))
                    InfoRow("定位次数", "${state.gpsCount}")
                    InfoRow("气压计", if (state.hasBarometer) "有" else "无")
                    InfoRow("原始信号", state.lastRawFix ?: "无")
                    state.lastError?.let { InfoRow("错误", it) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(76.dp))
        Text(
            value,
            color = Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun signalLevel(used: Int?, avgCn0: Float?): Int {
    if (used == null) return 0
    val cn0 = avgCn0 ?: 0f
    return when {
        used >= 9 && cn0 >= 30f -> 5
        used >= 7 -> 4
        used >= 5 -> 3
        used >= 3 -> 2
        used >= 1 -> 1
        else -> 0
    }
}

/** 没有卫星明细时，用垂直精度兜底估一个信号等级 */
private fun accuracyLevel(acc: Float): Int = when {
    acc <= 5f -> 5
    acc <= 10f -> 4
    acc <= 20f -> 3
    acc <= 35f -> 2
    else -> 1
}

private fun signalLabel(level: Int): String = when (level) {
    5 -> "极佳"
    4 -> "良好"
    3 -> "一般"
    2 -> "较弱"
    1 -> "很弱"
    else -> "无信号"
}

private fun signalColor(level: Int): Color = when (level) {
    5, 4 -> Good
    3 -> Accent
    2, 1 -> Warn
    else -> Bad
}

private fun statusColor(status: String): Color = when {
    status.contains("正常") || status.contains("已校准") -> Good
    status.contains("气压模式") -> Accent
    status.contains("权限") || status.contains("不可用") ||
        status.contains("失败") || status.contains("错误") -> Bad
    else -> Warn
}

private fun constellationColor(name: String): Color = when (name) {
    "GPS" -> Color(0xFF6FD3FF)
    "BDS" -> Color(0xFFFFB86B)
    "GLO" -> Color(0xFFC49BFF)
    "GAL" -> Color(0xFF7BB8FF)
    "QZSS" -> Color(0xFF7BE495)
    "SBAS" -> Color(0xFFFFE07B)
    "NavIC" -> Color(0xFFFF9BC7)
    else -> Muted
}

private fun fmt6(value: Double): String = String.format(Locale.US, "%.6f", value)

/** 定位数据有多旧；每秒刷新一次，缓存值能直接看出是几分钟还是几小时前 */
@Composable
private fun rememberFixAge(lastFixTimeMs: Long?): String? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastFixTimeMs) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    if (lastFixTimeMs == null) return null
    val seconds = ((now - lastFixTimeMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "刚刚"
        seconds < 60 -> "${seconds} 秒前"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86400} 天前"
    }
}
