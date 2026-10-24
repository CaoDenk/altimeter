package com.example.altitude.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/** DEM 事后修正：优先 Open-Meteo（免费），失败返回 null */
object DemRepository {
    suspend fun queryAltitude(lat: Double, lon: Double): Float? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon"
            val text = URL(url).readText()
            JSONObject(text).getJSONArray("elevation").getDouble(0).toFloat()
        } catch (_: Exception) { null }
    }
}
