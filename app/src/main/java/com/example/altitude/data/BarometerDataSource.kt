package com.example.altitude.data

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

object BarometerDataSource {
    fun hasBarometer(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER)

    /** 低通滤波后的气压流，单位 hPa */
    fun pressureFlow(ctx: Context) = callbackFlow {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sensor == null) { close(); return@callbackFlow }
        var filtered: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val raw = e.values[0]
                filtered = if (filtered == null) raw else filtered!! * 0.85f + raw * 0.15f
                trySend(filtered!!)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sm.unregisterListener(listener) }
    }
}
