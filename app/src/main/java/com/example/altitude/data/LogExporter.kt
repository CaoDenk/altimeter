package com.example.altitude.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.altitude.ui.AltitudeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {
    fun appVersion(ctx: Context): String {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toString() else pi.versionCode.toString()
            "v${pi.versionName} (build $code)"
        } catch (_: Exception) { "unknown" }
    }

    suspend fun export(ctx: Context, state: AltitudeUiState): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "logs")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "altitude-log-$ts.txt")
        val header = buildString {
            appendLine("=== AltitudeApp diagnosis ===")
            appendLine("time=$ts")
            appendLine("app=${appVersion(ctx)}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            appendLine("state: fused=${state.fusedAlt} gps=${state.gpsAlt} vAcc=${state.gpsAccuracy} pressure=${state.pressure} p0=${state.p0}")
            appendLine("hasBarometer=${state.hasBarometer} gpsCount=${state.gpsCount} availability=${state.availability}")
            appendLine("lastRaw=${state.lastRawFix}")
            appendLine("lastError=${state.lastError}")
            appendLine("status=${state.status}")
            appendLine("=== logcat ===")
        }
        file.writeText(header)
        try {
            // 本App日志 + 全系统 Error，方便看到 PlayServices / 定位报错
            val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "AltitudeApp:D", "*:E").start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            file.appendText(out.ifEmpty { "(logcat empty)" })
        } catch (e: Exception) {
            Log.w(LocationTag, "logcat dump failed", e)
            file.appendText("logcat dump failed: ${e.message}")
        }
        file
    }
}
