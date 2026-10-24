package com.example.altitude.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.altitude.data.LogExporter
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: AltitudeViewModel, onRequestPermission: () -> Unit = {}) {
    val s by vm.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("当前海拔", style = MaterialTheme.typography.titleMedium)
        Text(
            if (s.fusedAlt == null) "--" else "${"%.1f".format(s.fusedAlt)} m",
            fontSize = 56.sp
        )
        Text(
            s.gpsAccuracy?.let { "误差约 ±${"%.1f".format(it)} 米" } ?: "误差未知",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(s.status)
        if (s.status.contains("权限")) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                vm.startGps()
                onRequestPermission()
            }) { Text("授予定位权限") }
        }
        Spacer(Modifier.height(16.dp))
        Text("GPS: ${s.gpsAlt?.let { "%.1f m".format(it) } ?: "--"}  (共${s.gpsCount}次)")
        Text("气压: ${s.pressure?.let { "%.1f hPa".format(it) } ?: "无传感器"}")
        Text("P0: ${"%.1f hPa".format(s.p0)}")
        Text(if (s.hasBarometer) "气压计: 有" else "气压计: 无，已降级为GPS")
        Spacer(Modifier.height(8.dp))
        // 诊断区：横线时直接看这几行就知道卡在哪
        Text("定位可用性: ${s.availability?.let { if (it) "可用" else "不可用（检查系统定位开关）" } ?: "未知"}")
        Text("原始信号: ${s.lastRawFix ?: "无"}")
        s.lastError?.let { Text("错误: $it") }
        Spacer(Modifier.height(16.dp))
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
            enabled = !exporting
        ) { Text(if (exporting) "导出中..." else "导出诊断日志") }
        Spacer(Modifier.weight(1f))
        Text(LogExporter.appVersion(ctx), style = MaterialTheme.typography.bodySmall)
    }
}
