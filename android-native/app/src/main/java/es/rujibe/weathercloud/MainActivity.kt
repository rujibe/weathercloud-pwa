package es.rujibe.weathercloud

import android.os.Bundle
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF101D2E)
private val TextMain = Color(0xFFF2F7FC)
private val Muted = Color(0xFF9CB0C6)
private val Blue = Color(0xFF77D5FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WeathercloudApp(getSharedPreferences("weathercloud-native", MODE_PRIVATE)) }
    }
}

data class Station(val name: String, val id: String)
data class Reading(val key: String, val label: String, val value: String, val unit: String, val icon: String)
data class StationAnalysis(val station: Station, val value: Double?, val unit: String, val label: String, val error: String = "", val observations: Int = 0, val variables: Int = 0)
data class ValidationMetric(val label: String, val score: Double?, val mae: Double?, val count: Int, val unit: String)
data class StationValidationResult(val station: Station, val place: String, val overall: Double?, val coverage: Int, val metrics: List<ValidationMetric>, val error: String = "")
data class NearbyStation(val name: String, val id: String, val city: String, val distanceKm: Double, val updateMinutes: Int?)
data class ForecastDay(val date: String, val code: Int, val minimum: Int, val maximum: Int, val rainChance: Int, val rainMm: Double)
data class ForecastHour(val time: String, val code: Int, val temperature: Double, val rainChance: Int, val rainMm: Double, val wind: Double, val gust: Double)
data class ForecastPlace(val name: String, val latitude: Double, val longitude: Double, val admin: String, val country: String)

@Composable
fun WeathercloudApp(prefs: android.content.SharedPreferences) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableStateOf("Estación") }
    var theme by remember { mutableStateOf(prefs.getString("theme", "dark") ?: "dark") }
    var accent by remember { mutableStateOf(prefs.getString("accent", "blue") ?: "blue") }
    var font by remember { mutableStateOf(prefs.getString("font", "normal") ?: "normal") }
    var density by remember { mutableStateOf(prefs.getString("density", "comfortable") ?: "comfortable") }
    var motion by remember { mutableStateOf(prefs.getBoolean("motion", false)) }
    var nearby by remember { mutableStateOf(prefs.getBoolean("nearby", true)) }
    var apiBase by remember { mutableStateOf(prefs.getString("apiBase", "") ?: "") }
    var stations by remember { mutableStateOf(loadStations(prefs)) }
    var selectedId by remember { mutableStateOf(prefs.getString("selectedId", stations.firstOrNull()?.id ?: "3102580268") ?: "3102580268") }
    var showSettings by remember { mutableStateOf(false) }
    var showStations by remember { mutableStateOf(false) }
    var stationMenuExpanded by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newId by remember { mutableStateOf("") }
    var readings by remember { mutableStateOf<List<Reading>>(emptyList()) }
    var forecast by remember { mutableStateOf<List<ForecastDay>>(emptyList()) }
    var hourlyForecast by remember { mutableStateOf<List<ForecastHour>>(emptyList()) }
    var forecastSearchResults by remember { mutableStateOf<List<ForecastPlace>>(emptyList()) }
    var forecastNow by remember { mutableStateOf<ForecastHour?>(null) }
    var showAllForecastHours by remember { mutableStateOf(false) }
    var forecastSearch by remember { mutableStateOf("") }
    var forecastPlaceName by remember { mutableStateOf(prefs.getString("forecastPlaceName", "Madrid, España") ?: "Madrid, España") }
    var history by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var selectedHistoryIndex by remember { mutableIntStateOf(-1) }
    var historyVariable by remember { mutableStateOf("temp") }
    var historyPeriod by remember { mutableStateOf("week") }
    var analysisVariable by remember { mutableStateOf("temp") }
    var analysisResults by remember { mutableStateOf<List<StationAnalysis>>(emptyList()) }
    var analysisSelectedIds by remember { mutableStateOf<Set<String>>(stations.map { it.id }.toSet()) }
    var analysisMessage by remember { mutableStateOf("Selecciona las estaciones guardadas para comparar sus condiciones.") }
    var validationStationId by remember { mutableStateOf(selectedId) }
    var validationMessage by remember { mutableStateOf("Conecta el Worker para comparar los últimos 30 días con el archivo histórico de Open-Meteo.") }
    var validationResult by remember { mutableStateOf<StationValidationResult?>(null) }
    var nearbyRadius by remember { mutableStateOf("10") }
    var nearbyMessage by remember { mutableStateOf("Selecciona un lugar en Previsión y conecta el Worker para buscar estaciones.") }
    var nearbyResults by remember { mutableStateOf<List<NearbyStation>>(emptyList()) }
    var status by remember { mutableStateOf("Listo para consultar la estación") }
    var loading by remember { mutableStateOf(false) }
    var forecastLat by remember { mutableStateOf(prefs.getString("forecastLat", "40.4168") ?: "40.4168") }
    var forecastLon by remember { mutableStateOf(prefs.getString("forecastLon", "-3.7038") ?: "-3.7038") }
    val accentColor = when (accent) { "mint" -> Color(0xFF8EE6CF); "gold", "orange" -> Color(0xFFFFD27B); "purple", "violet" -> Color(0xFFC5A4FF); else -> Blue }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val light = when (theme) { "light" -> true; "system" -> !systemDark; else -> false }
    val bg = if (light) Color(0xFFF1F5FA) else Navy
    val card = if (light) Color.White else Panel
    val fg = if (light) Color(0xFF14243A) else TextMain
    val muted = if (light) Color(0xFF52677D) else Muted
    val scale = if (font == "small") 0.9f else if (font == "large") 1.12f else 1f

    fun persistStations() {
        prefs.edit().putString("stations", JSONArray().apply { stations.forEach { put(JSONObject().put("name", it.name).put("id", it.id)) } }.toString())
            .putString("selectedId", selectedId).apply()
    }
    fun refreshCurrent() {
        val station = stations.firstOrNull { it.id == selectedId } ?: return
        loading = true
        status = "Consultando ${station.name}…"
        scope.launch {
            try {
                val base = apiBase.trim().trimEnd('/')
                val result = withContext(Dispatchers.IO) {
                    if (base.isNotBlank()) JSONObject(httpGet("$base/api/current?station_id=${enc(station.id)}"))
                    else {
                        val all = JSONObject(httpGet("https://rujibe.github.io/weathercloud-pwa/weather.json"))
                        all.optJSONObject("stations")?.optJSONObject(station.id)?.optJSONObject("readings")
                            ?: throw IllegalStateException("No hay datos guardados para este ID. Configura la URL del puente de datos en Ajustes.")
                    }
                }
                val fields = listOf(
                    arrayOf("temp","Temperatura","°C","🌡"), arrayOf("hum","Humedad","%","💧"),
                    arrayOf("bar","Presión","hPa","🧭"), arrayOf("wspd","Viento","km/h","💨"),
                    arrayOf("gust","Racha","km/h","🌬"), arrayOf("rain","Lluvia","mm","🌧"),
                    arrayOf("rainrate","Intensidad lluvia","mm/h","🌦"), arrayOf("wdir","Dirección viento","°","🧭"),
                    arrayOf("solar","Radiación solar","W/m²","☀️"), arrayOf("uv","Índice UV","","🕶"),
                    arrayOf("dew","Punto de rocío","°C","❄️"), arrayOf("heat","Sensación térmica","°C","🌡"),
                    arrayOf("tempin","Temperatura interior","°C","🏠"), arrayOf("humin","Humedad interior","%","🏡")
                )
                readings = fields.mapNotNull { f ->
                    val raw = result.opt(f[0])
                    if (raw == null || raw == JSONObject.NULL || raw.toString().isBlank()) null
                    else Reading(f[0], f[1], raw.toString().toDoubleOrNull()?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: raw.toString(), f[2], f[3])
                }
                status = "Actualizado · ${station.name}"
            } catch (e: Exception) { status = "No se pudieron cargar los datos: ${e.message}" }
            loading = false
        }
    }
    fun refreshForecast() {
        val lat = forecastLat.toDoubleOrNull()
        val lon = forecastLon.toDoubleOrNull()
        if (lat == null || lon == null) { status = "Introduce latitud y longitud válidas"; return }
        loading = true
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_gusts_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum&hourly=weather_code,is_day,temperature_2m,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m&forecast_days=7&timezone=auto&temperature_unit=celsius&precipitation_unit=mm&wind_speed_unit=kmh"))
                }
                val d = data.getJSONObject("daily")
                forecast = (0 until d.getJSONArray("time").length()).map { i ->
                    ForecastDay(
                        date = d.getJSONArray("time").getString(i),
                        code = d.getJSONArray("weather_code").optInt(i),
                        minimum = d.getJSONArray("temperature_2m_min").optDouble(i).toInt(),
                        maximum = d.getJSONArray("temperature_2m_max").optDouble(i).toInt(),
                        rainChance = d.getJSONArray("precipitation_probability_max").optInt(i),
                        rainMm = d.getJSONArray("precipitation_sum").optDouble(i)
                    )
                }
                val h = data.getJSONObject("hourly")
                val times = h.getJSONArray("time")
                val temps = h.getJSONArray("temperature_2m")
                val probs = h.getJSONArray("precipitation_probability")
                val codes = h.getJSONArray("weather_code")
                val winds = h.getJSONArray("wind_speed_10m")
                val gusts = h.getJSONArray("wind_gusts_10m")
                val rains = h.getJSONArray("precipitation")
                val currentTime = runCatching { java.time.LocalDateTime.parse(data.optJSONObject("current")?.optString("time")) }.getOrNull()
                    ?: java.time.LocalDateTime.now()
                val currentHour = currentTime.withMinute(0).withSecond(0).withNano(0)
                hourlyForecast = (0 until times.length()).filter { i ->
                    runCatching { !java.time.LocalDateTime.parse(times.optString(i)).isBefore(currentHour) }.getOrDefault(false)
                }.take(48).map { i ->
                    ForecastHour(times.optString(i).substringAfter('T'), codes.optInt(i), temps.optDouble(i), probs.optInt(i), rains.optDouble(i), winds.optDouble(i), gusts.optDouble(i))
                }
                showAllForecastHours = false
                val cur = data.optJSONObject("current")
                if (cur != null) {
                    forecastNow = ForecastHour(cur.optString("time").substringAfter('T'), cur.optInt("weather_code"), cur.optDouble("temperature_2m"), cur.optInt("precipitation_probability"), cur.optDouble("precipitation"), cur.optDouble("wind_speed_10m"), cur.optDouble("wind_gusts_10m"))
                    status = "Previsión cargada · ahora ${cur.optDouble("temperature_2m").toInt()}°C · viento ${cur.optDouble("wind_speed_10m").toInt()} km/h"
                }
            } catch (e: Exception) { status = "Error al cargar previsión: ${e.message}" }
            loading = false
        }
    }
    fun searchForecastPlace() {
        val query = forecastSearch.trim()
        if (query.length < 2) { status = "Escribe una ciudad o lugar"; return }
        loading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=${enc(query)}&count=8&language=es&format=json"))
                }
                val places = result.optJSONArray("results")
                if (places == null || places.length() == 0) {
                    forecastSearchResults = emptyList()
                    status = "No se encontraron lugares para «$query»"
                } else {
                    forecastSearchResults = (0 until places.length()).mapNotNull { i ->
                        val place = places.optJSONObject(i) ?: return@mapNotNull null
                        ForecastPlace(place.optString("name"), place.optDouble("latitude"), place.optDouble("longitude"), place.optString("admin1"), place.optString("country"))
                    }
                    status = "Selecciona el lugar correcto entre los resultados."
                }
            } catch (e: Exception) { status = "No se pudo buscar el lugar: ${e.message}" }
            loading = false
        }
    }
    fun searchNearbyStations() {
        val base = apiBase.trim().trimEnd('/')
        val lat = forecastLat.toDoubleOrNull()
        val lon = forecastLon.toDoubleOrNull()
        if (base.isBlank()) { nearbyMessage = "Conecta el Worker de Cloudflare desde Ajustes para buscar estaciones."; return }
        if (lat == null || lon == null) { nearbyMessage = "Selecciona una ubicación válida en Previsión."; return }
        loading = true
        nearbyMessage = "Buscando estaciones públicas cercanas…"
        scope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    httpGet(base + "/api/nearby?latitude=" + lat + "&longitude=" + lon + "&distance_km=" + nearbyRadius)
                }
                val root = if (body.trimStart().startsWith("{")) JSONObject(body) else JSONObject()
                val records = if (body.trimStart().startsWith("[")) JSONArray(body) else root.optJSONArray("devices") ?: root.optJSONArray("results") ?: root.optJSONArray("stations") ?: JSONArray()
                val parsed = (0 until records.length()).mapNotNull { i ->
                    val item = records.optJSONObject(i) ?: return@mapNotNull null
                    val id = item.optString("code", item.optString("id", ""))
                    val lat2 = item.optDouble("latitude", Double.NaN)
                    val lon2 = item.optDouble("longitude", Double.NaN)
                    if (id.isBlank() || !id.matches(Regex("[0-9]{1,20}")) || lat2.isNaN() || lon2.isNaN() || item.optString("type", "device") != "device") null
                    else {
                        val dLat = Math.toRadians(lat2 - lat)
                        val dLon = Math.toRadians(lon2 - lon)
                        val h = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
                        val distance = 6371.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt((1 - h).coerceAtLeast(0.0)))
                        val update = if (item.has("update")) item.optDouble("update", Double.NaN).takeIf { it.isFinite() }?.div(60)?.toInt() else null
                        NearbyStation(item.optString("name", item.optString("city", "Estación " + id)), id, item.optString("city", ""), distance, update)
                    }
                }.sortedBy { it.distanceKm }.take(30)
                nearbyResults = parsed
                nearbyMessage = if (parsed.isEmpty()) "Weathercloud no ha devuelto estaciones públicas con ubicación en este radio. Prueba ampliarlo." else parsed.size.toString() + " estaciones encontradas, ordenadas por distancia."
            } catch (e: Exception) { nearbyMessage = "No se pudo completar la búsqueda: " + e.message }
            loading = false
        }
    }
    fun runHistoricalValidation() {
        val station = stations.firstOrNull { it.id == validationStationId }
        if (station == null) { validationMessage = "Selecciona una estación guardada."; return }
        val base = apiBase.trim().trimEnd('/')
        if (base.isBlank()) { validationMessage = "Conecta el Worker de Cloudflare en Ajustes para consultar el histórico Weathercloud."; return }
        loading = true
        validationMessage = "Comparando observaciones y pronósticos horarios coincidentes de los últimos 30 días…"
        validationResult = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val location = JSONObject(httpGet(base + "/api/station?station_id=" + enc(station.id)))
                    val lat = location.optDouble("latitude", Double.NaN)
                    val lon = location.optDouble("longitude", Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) throw IllegalStateException("Weathercloud no ha devuelto coordenadas válidas.")
                    val end = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1)
                    val start = end.minusDays(29)
                    val archiveUrl = "https://historical-forecast-api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&start_date=$start&end_date=$end&timezone=UTC&temperature_unit=celsius&precipitation_unit=mm&wind_speed_unit=kmh&hourly=temperature_2m,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m"
                    val archive = JSONObject(httpGet(archiveUrl))
                    val hourly = archive.optJSONObject("hourly") ?: throw IllegalStateException("Open-Meteo no devolvió el archivo horario.")
                    val timeArray = hourly.optJSONArray("time") ?: throw IllegalStateException("El archivo de Open-Meteo no contiene horas.")
                    val archiveTimes = (0 until timeArray.length()).map { idx ->
                        runCatching { java.time.LocalDateTime.parse(timeArray.getString(idx)).toEpochSecond(java.time.ZoneOffset.UTC) }.getOrDefault(0L)
                    }
                    val configs = listOf(
                        Triple("temp", "Temperatura", "°C"),
                        Triple("wspd", "Velocidad del viento", "km/h"),
                        Triple("gust", "Rachas de viento", "km/h"),
                        Triple("rain", "Precipitación acumulada", "mm"),
                        Triple("rainrate", "Intensidad de lluvia", "mm/h")
                    )
                    val observedByKey = mutableMapOf<String, List<Pair<Long, Double>>>()
                    val metrics = configs.map { (key, label, unit) ->
                        val historyJson = JSONObject(httpGet(base + "/api/history?station_id=" + enc(station.id) + "&variable=" + key + "&period=month"))
                        val points = historyJson.optJSONArray("points") ?: JSONArray()
                        observedByKey[key] = (0 until points.length()).mapNotNull { pointIndex ->
                            val point = points.optJSONObject(pointIndex) ?: return@mapNotNull null
                            val time = point.optDouble("time", Double.NaN)
                            val value = point.optDouble("value", Double.NaN)
                            if (time.isFinite() && value.isFinite()) time.toLong() to value else null
                        }
                        val forecastKey = when (key) { "temp" -> "temperature_2m"; "wspd" -> "wind_speed_10m"; "gust" -> "wind_gusts_10m"; else -> "precipitation" }
                        val forecastValues = hourly.optJSONArray(forecastKey)
                        val pairs = mutableListOf<Pair<Double, Double>>()
                        for (idx in 0 until points.length()) {
                            val point = points.optJSONObject(idx) ?: continue
                            val time = point.optDouble("time", Double.NaN)
                            val value = point.optDouble("value", Double.NaN)
                            if (!time.isFinite() || !value.isFinite()) continue
                            var nearest = -1
                            var delta = Long.MAX_VALUE
                            for (j in archiveTimes.indices) {
                                val diff = kotlin.math.abs(archiveTimes[j] - time.toLong())
                                if (diff < delta) { delta = diff; nearest = j }
                                if (archiveTimes[j] > time.toLong() + 3600) break
                            }
                            if (nearest < 0 || delta > 1800 || forecastValues == null) continue
                            val predicted = forecastValues.optDouble(nearest, Double.NaN)
                            if (!predicted.isFinite()) continue
                            val observed = if (key == "wspd" || key == "gust") value * 3.6 else value
                            pairs.add(observed to predicted)
                        }
                        val tolerance = when (key) { "temp" -> 2.0; "wspd" -> 10.0; "gust" -> 15.0; else -> 1.0 }
                        val score = if (pairs.isEmpty()) null else 100.0 * pairs.count { kotlin.math.abs(it.first - it.second) <= tolerance } / pairs.size
                        val mae = if (pairs.isEmpty()) null else pairs.sumOf { kotlin.math.abs(it.first - it.second) } / pairs.size
                        ValidationMetric(label, score, mae, pairs.size, unit)
                    }
                    val rainRatePoints = observedByKey["rainrate"] ?: emptyList()
                    val rainPoints = observedByKey["rain"] ?: emptyList()
                    val probabilities = hourly.optJSONArray("precipitation_probability")
                    val brierErrors = mutableListOf<Double>()
                    if (probabilities != null) rainRatePoints.forEach { (time, rate) ->
                        var nearest = -1
                        var delta = Long.MAX_VALUE
                        for (j in archiveTimes.indices) {
                            val diff = kotlin.math.abs(archiveTimes[j] - time)
                            if (diff < delta) { delta = diff; nearest = j }
                            if (archiveTimes[j] > time + 3600) break
                        }
                        if (nearest >= 0 && delta <= 1800) {
                            val probability = probabilities.optDouble(nearest, Double.NaN)
                            if (probability.isFinite()) {
                                val observedRain = rate > 0.0 || rainPoints.any { (rainTime, amount) -> kotlin.math.abs(rainTime - time) <= 1800 && amount > 0.0 }
                                val p = probability / 100.0
                                val actual = if (observedRain) 1.0 else 0.0
                                brierErrors.add((p - actual) * (p - actual))
                            }
                        }
                    }
                    val brier = if (brierErrors.isEmpty()) null else brierErrors.average()
                    val allMetrics = metrics + ValidationMetric("Probabilidad de lluvia", if (brier == null) null else 100.0 * (1.0 - brier), brier, brierErrors.size, "Brier")
                    val available = allMetrics.filter { it.score != null }
                    StationValidationResult(station, location.optString("name", station.name), if (available.isEmpty()) null else available.mapNotNull { it.score }.average(), available.size, allMetrics)
                }
                validationResult = result
                validationMessage = if (result.coverage == 0) "No hay suficientes observaciones coincidentes para calcular un porcentaje fiable." else "Comparación terminada · ${result.place} · ${result.coverage} variables evaluables."
            } catch (e: Exception) {
                validationMessage = "No se pudo completar la comparación: " + (e.message ?: "comprueba el Worker y los datos históricos.")
            }
            loading = false
        }
    }
    fun refreshAnalysis() {
        val selectedStations = stations.filter { analysisSelectedIds.contains(it.id) }
        if (selectedStations.size < 2) { analysisMessage = "Selecciona al menos dos estaciones para comparar."; return }
        if (selectedStations.size > 8) { analysisMessage = "Selecciona un máximo de ocho estaciones cada vez."; return }
        val base = apiBase.trim().trimEnd('/')
        if (base.isBlank()) { analysisMessage = "Conecta el Worker de Cloudflare en Ajustes para comparar los históricos."; return }
        loading = true
        analysisMessage = "Consultando el histórico mensual de las estaciones seleccionadas…"
        scope.launch {
            val metadata = listOf(
                "temp" to ("Temperatura" to "°C"), "hum" to ("Humedad" to "%"),
                "bar" to ("Presión" to "hPa"), "wspd" to ("Viento" to "km/h"),
                "gust" to ("Rachas" to "km/h"), "rain" to ("Lluvia" to "mm"),
                "rainrate" to ("Intensidad de lluvia" to "mm/h"), "solar" to ("Radiación solar" to "W/m²"),
                "uv" to ("Índice UV" to "")
            )
            val meta = metadata.firstOrNull { it.first == analysisVariable }?.second ?: ("Temperatura" to "°C")
            val results = mutableListOf<StationAnalysis>()
            for (station in selectedStations) {
                try {
                    val summary = coroutineScope {
                        val series = metadata.map { (key, _) ->
                            async(Dispatchers.IO) {
                                val json = JSONObject(httpGet(base + "/api/history?station_id=" + enc(station.id) + "&variable=" + enc(key) + "&period=month"))
                                val points = json.optJSONArray("points") ?: JSONArray()
                                val values = (0 until points.length()).mapNotNull { i ->
                                    val point = points.optJSONObject(i) ?: return@mapNotNull null
                                    val value = point.optDouble("value", Double.NaN)
                                    if (value.isFinite()) value else null
                                }
                                key to values
                            }
                        }.awaitAll()
                        val total = series.sumOf { it.second.size }
                        val available = series.count { it.second.isNotEmpty() }
                        val selectedValues = series.firstOrNull { it.first == analysisVariable }?.second ?: emptyList()
                        Triple(total, available, selectedValues)
                    }
                    val values = summary.third
                    val average = values.takeIf { it.isNotEmpty() }?.average()
                    results.add(StationAnalysis(station, average, meta.second, meta.first, if (average == null) "Sin datos para esta variable" else "", summary.first, summary.second))
                } catch (e: Exception) {
                    results.add(StationAnalysis(station, null, meta.second, meta.first, e.message ?: "No disponible"))
                }
            }
            analysisResults = results.sortedByDescending { it.observations }
            val count = results.count { it.value != null }
            analysisMessage = count.toString() + " de " + results.size + " estaciones con datos para " + meta.first.lowercase(Locale.getDefault()) + "; ordenadas por cobertura histórica."
            loading = false
        }
    }
    fun readDeviceLocation() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { status = "Permite la ubicación para consultar tu previsión local."; return }
        val provider = when {
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) { status = "Activa la ubicación del dispositivo e inténtalo de nuevo."; return }
        try {
            val last = manager.getLastKnownLocation(provider)
            if (last != null) {
                forecastLat = last.latitude.toString()
                forecastLon = last.longitude.toString()
                forecastPlaceName = "Mi ubicación"
                prefs.edit().putString("forecastLat", forecastLat).putString("forecastLon", forecastLon).putString("forecastPlaceName", forecastPlaceName).apply()
                refreshForecast()
            } else {
                nearbyMessage = "Obteniendo la ubicación del dispositivo…"
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        forecastLat = location.latitude.toString()
                        forecastLon = location.longitude.toString()
                        forecastPlaceName = "Mi ubicación"
                        prefs.edit().putString("forecastLat", forecastLat).putString("forecastLon", forecastLon).putString("forecastPlaceName", forecastPlaceName).apply()
                        refreshForecast()
                    }
                }
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) { status = "No se ha concedido permiso de ubicación." }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) readDeviceLocation() else status = "Permiso de ubicación denegado."
    }
    fun requestDeviceLocation() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) readDeviceLocation() else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun refreshHistory() {
        val station = stations.firstOrNull { it.id == selectedId } ?: return
        loading = true
        scope.launch {
            try {
                val base = apiBase.trim().trimEnd('/')
                val json = withContext(Dispatchers.IO) {
                    if (base.isNotBlank()) JSONObject(httpGet("$base/api/history?station_id=${enc(station.id)}&variable=${enc(historyVariable)}&period=${enc(if (historyPeriod == "yesterday") "week" else historyPeriod)}"))
                    else JSONObject(httpGet("https://rujibe.github.io/weathercloud-pwa/history.json")).optJSONObject("stations")?.optJSONObject(station.id)?.optJSONObject("series") ?: JSONObject()
                }
                val pts = json.optJSONArray("points") ?: json.optJSONArray(historyVariable) ?: json.optJSONArray("temp") ?: JSONArray()
                val points = (0 until pts.length()).mapNotNull { i ->
                    val p = pts.optJSONObject(i) ?: return@mapNotNull null
                    val v = p.optDouble("value", Double.NaN)
                    if (v.isNaN()) null else (p.optLong("time").toString() to v)
                }
                val now = System.currentTimeMillis() / 1000
                history = when (historyPeriod) {
                    "day" -> points.filter { it.first.toLongOrNull()?.let { t -> t >= now - 86400 } == true }
                    "yesterday" -> {
                        val zone = java.time.ZoneId.systemDefault()
                        val start = java.time.LocalDate.now().minusDays(1).atStartOfDay(zone).toEpochSecond()
                        val end = java.time.LocalDate.now().atStartOfDay(zone).toEpochSecond()
                        points.filter { it.first.toLongOrNull()?.let { t -> t >= start && t < end } == true }
                    }
                    else -> points
                }.takeLast(500)
                selectedHistoryIndex = -1
                status = "Histórico · ${history.size} puntos"
            } catch (e: Exception) { status = "No se pudo cargar el histórico: ${e.message}" }
            loading = false
        }
    }

    LaunchedEffect(selectedId) {
        refreshCurrent()
        while (true) {
            delay(5 * 60 * 1000L)
            refreshCurrent()
        }
    }

    MaterialTheme(colorScheme = if (light) lightColorScheme(primary = accentColor, background = bg, surface = card, onSurface = fg) else darkColorScheme(primary = accentColor, background = bg, surface = card, onSurface = fg)) {
        Column(Modifier.fillMaxSize().background(bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("WEATHERCLOUD  ·  MI ESTACIÓN", color = accentColor, fontSize = (11 * scale).sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(when(tab) { "Estación" -> "El tiempo en tu estación"; "Pronóstico" -> "Previsión para 7 días"; "Análisis" -> "Análisis por estación"; else -> tab }, color = fg, fontSize = (24 * scale).sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { showSettings = true }) { Text("⚙ Ajustes", color = accentColor) }
            }
            Text(status, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), color = muted, fontSize = (12 * scale).sp)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accentColor)
            when (tab) {
                "Estación" -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        val heroShape = RoundedCornerShape(28.dp)
                        Box(Modifier.fillMaxWidth().clip(heroShape).background(Brush.linearGradient(if (light) listOf(Color(0xFFD8F0FF), Color(0xFFB9D5F4), Color(0xFFDCE6FA)) else listOf(Color(0xFF17466B), Color(0xFF183650), Color(0xFF24375C)))).border(1.dp, if (light) Color(0xFFB4D4E9) else Color(0xFF3B6485), heroShape)) {
                            Column(Modifier.padding(24.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("●", color = if (readings.isNotEmpty()) Color(0xFF65E0AE) else muted, fontSize = 10.sp)
                                    Text(if (readings.isNotEmpty()) "ESTACIÓN CONECTADA" else "PANEL METEOROLÓGICO", color = if (light) Color(0xFF315D7B) else Color(0xFFB9F6DC), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                                }
                                Spacer(Modifier.height(14.dp))
                                Box {
                                    TextButton(onClick = { stationMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                                        Text("▾  " + (stations.firstOrNull { it.id == selectedId }?.name ?: "Mi estación"), color = if (light) Color(0xFF14243A) else TextMain, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    }
                                    DropdownMenu(expanded = stationMenuExpanded, onDismissRequest = { stationMenuExpanded = false }) {
                                        stations.forEach { station ->
                                            DropdownMenuItem(text = { Text(station.name) }, onClick = {
                                                selectedId = station.id
                                                persistStations()
                                                stationMenuExpanded = false
                                                refreshCurrent()
                                            })
                                        }
                                        DropdownMenuItem(text = { Text("Gestionar mis estaciones") }, onClick = { stationMenuExpanded = false; showStations = true })
                                    }
                                }
                                val temp = readings.firstOrNull { it.key == "temp" }
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (temp != null) temp.value else "—", color = if (light) Color(0xFF14243A) else TextMain, fontSize = (58 * scale).sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp)
                                    Text("°C", Modifier.padding(bottom = 9.dp), color = if (light) Color(0xFF315D7B) else Blue, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                                }
                                Text("Condiciones meteorológicas actuales", color = if (light) Color(0xFF52677D) else Color(0xFFBCD0E4), fontSize = 13.sp)
                                Spacer(Modifier.height(18.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = { refreshCurrent() }, colors = ButtonDefaults.buttonColors(containerColor = if (light) Color(0xFF142F4A) else accentColor), shape = RoundedCornerShape(13.dp)) { Text("↻  Actualizar", color = if (light) Color.White else Navy) }
                                    OutlinedButton(onClick = { showStations = true }, shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (light) Color(0xFF14243A) else TextMain)) { Text("Mis estaciones") }
                                }
                            }
                        }
                    }
                    item {
                        Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(18.dp))) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("🌤️", fontSize = 26.sp)
                                Column(Modifier.weight(1f)) {
                                    Text("Cambio de tiempo", color = fg, fontWeight = FontWeight.SemiBold)
                                    Text(if (readings.isEmpty()) "Se analizará la evolución cuando haya datos suficientes." else "Lecturas actuales disponibles. Actualiza la estación para consultar los últimos datos.", color = muted, fontSize = 12.sp)
                                    Text(if (readings.isEmpty()) "Sin tendencia calculada todavía" else "Estado de la estación: conectada", color = accentColor, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    if (readings.isEmpty()) item {
                        Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFD8E2ED) else Color(0xFF263A52), RoundedCornerShape(18.dp))) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (loading) "Conectando con tu estación…" else "Aún no hay lecturas disponibles", color = fg, fontWeight = FontWeight.SemiBold)
                                Text("Comprueba la conexión de datos en Ajustes y vuelve a actualizar.", color = muted, fontSize = 13.sp)
                                if (!loading) OutlinedButton(onClick = { refreshCurrent() }) { Text("Reintentar") }
                            }
                        }
                    }
                    items(readings.chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { r ->
                                Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(18.dp))) {
                                    Column(Modifier.padding(if (density == "compact") 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("${r.icon}  ${r.label}", color = muted, fontSize = (12 * scale).sp)
                                        Text("${r.value} ${r.unit}", color = fg, fontSize = (22 * scale).sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (nearby) item {
                        Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(18.dp))) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Estaciones Weathercloud cercanas", color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("Busca estaciones públicas alrededor de la ubicación seleccionada en Previsión.", color = muted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("5", "10", "25", "50").forEach { radius ->
                                        FilterChip(selected = nearbyRadius == radius, onClick = { nearbyRadius = radius }, label = { Text(radius + " km") })
                                    }
                                }
                                Button(onClick = { searchNearbyStations() }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = accentColor)) { Text("📡 Buscar estaciones", color = Navy) }
                                Text(nearbyMessage, color = muted, fontSize = 12.sp)
                                nearbyResults.forEach { station ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(station.name, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            val details = mutableListOf(station.city, String.format(Locale.getDefault(), "%.1f km", station.distanceKm), "ID " + station.id)
                                            station.updateMinutes?.let { details.add(if (it < 1) "actualizada ahora" else "actualizada hace " + it + " min") }
                                            Text(details.filter { it.isNotBlank() }.joinToString(" · "), color = muted, fontSize = 11.sp)
                                        }
                                        TextButton(onClick = {
                                            if (stations.none { it.id == station.id }) {
                                                stations = stations + Station(station.name, station.id)
                                                selectedId = station.id
                                                persistStations()
                                                refreshCurrent()
                                            }
                                        }, enabled = stations.none { it.id == station.id }) { Text(if (stations.any { it.id == station.id }) "Ya añadida" else "＋ Añadir") }
                                    }
                                    Divider(color = if (light) Color(0xFFDCE5EF) else Color(0xFF263A52))
                                }
                            }
                        }
                    }
                    item {
                        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(20.dp))) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Histórico meteorológico", color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("Evolución de la estación seleccionada", color = muted, fontSize = 12.sp)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("temp" to "Temperatura", "hum" to "Humedad", "bar" to "Presión", "wspd" to "Viento", "rain" to "Lluvia", "gust" to "Rachas").forEach { (key, label) ->
                                        FilterChip(selected = historyVariable == key, onClick = { historyVariable = key; refreshHistory() }, label = { Text(label) })
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("day" to "Hoy", "yesterday" to "Ayer", "week" to "7 días", "month" to "30 días").forEach { (key, label) ->
                                        FilterChip(selected = historyPeriod == key, onClick = { historyPeriod = key; refreshHistory() }, label = { Text(label) })
                                    }
                                }
                                if (history.isNotEmpty()) {
                                    val values = history.map { it.second }
                                    val minValue = values.minOrNull() ?: 0.0
                                    val maxValue = values.maxOrNull() ?: 1.0
                                    val historyUnit = when (historyVariable) { "temp" -> "°C"; "hum" -> "%"; "bar" -> "hPa"; "wspd", "gust" -> "km/h"; "rain" -> "mm"; else -> "" }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("Mín." to minValue, "Media" to values.average(), "Máx." to maxValue).forEach { pair ->
                                            Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                                                Column(Modifier.padding(9.dp)) {
                                                    Text(pair.first, color = muted, fontSize = 11.sp)
                                                    Text(String.format(Locale.getDefault(), "%.1f %s", pair.second, historyUnit), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                    Canvas(Modifier.fillMaxWidth().height(145.dp).pointerInput(history) {
                                        detectTapGestures { offset ->
                                            if (history.isNotEmpty()) selectedHistoryIndex = if (history.size == 1) 0 else ((offset.x / size.width) * (history.size - 1)).toInt().coerceIn(0, history.lastIndex)
                                        }
                                    }) {
                                        val span = (maxValue - minValue).coerceAtLeast(0.1)
                                        val points = values.mapIndexed { i, value -> androidx.compose.ui.geometry.Offset(if (values.size == 1) 0f else i * size.width / (values.size - 1), size.height - ((value - minValue) / span * (size.height - 12f)).toFloat() - 6f) }
                                        for (i in 0 until points.lastIndex) drawLine(accentColor, points[i], points[i + 1], strokeWidth = 3f, cap = StrokeCap.Round)
                                        if (selectedHistoryIndex in points.indices) drawCircle(accentColor, radius = 6f, center = points[selectedHistoryIndex])
                                    }
                                    if (selectedHistoryIndex in history.indices) {
                                        val point = history[selectedHistoryIndex]
                                        Text(point.first + " · " + String.format(Locale.getDefault(), "%.2f %s", point.second, historyUnit), color = accentColor, fontSize = 12.sp)
                                    }
                                } else {
                                    Text("Carga el histórico para ver la evolución de tus sensores.", color = muted, fontSize = 12.sp)
                                }
                                Button(onClick = { refreshHistory() }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = accentColor), modifier = Modifier.fillMaxWidth()) {
                                    Text(if (loading) "Cargando histórico…" else "Actualizar histórico", color = Navy)
                                }
                            }
                        }
                    }
                }
                "Pronóstico" -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Previsión para 7 días", color = fg, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                Text("Lugar: $forecastPlaceName", color = muted, fontSize = 12.sp)
                                OutlinedTextField(forecastSearch, { forecastSearch = it }, label = { Text("Buscar ciudad o lugar") }, placeholder = { Text("Ej. Mérida, España") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { searchForecastPlace() }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = accentColor)) { Text("Buscar lugar", color = Navy) }
                                    OutlinedButton(onClick = { requestDeviceLocation() }, enabled = !loading) { Text("Usar mi ubicación") }
                                }
                                OutlinedButton(onClick = { refreshForecast() }, modifier = Modifier.fillMaxWidth()) { Text("Actualizar previsión") }
                                if (forecastSearchResults.isNotEmpty()) {
                                    Text("Resultados de búsqueda", color = fg, fontWeight = FontWeight.SemiBold)
                                    forecastSearchResults.forEach { place ->
                                        TextButton(onClick = {
                                            forecastLat = place.latitude.toString()
                                            forecastLon = place.longitude.toString()
                                            forecastPlaceName = listOf(place.name, place.admin, place.country).filter { it.isNotBlank() }.distinct().joinToString(", ")
                                            forecastSearchResults = emptyList()
                                            prefs.edit().putString("forecastLat", forecastLat).putString("forecastLon", forecastLon).putString("forecastPlaceName", forecastPlaceName).apply()
                                            refreshForecast()
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.fillMaxWidth()) {
                                                Text(place.name, color = fg)
                                                Text(listOf(place.admin, place.country).filter { it.isNotBlank() }.distinct().joinToString(", "), color = muted, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                                Text("Coordenadas de la ubicación seleccionada", color = muted, fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(forecastLat, { forecastLat = it; prefs.edit().putString("forecastLat", it).apply() }, label = { Text("Latitud") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                                    OutlinedTextField(forecastLon, { forecastLon = it; prefs.edit().putString("forecastLon", it).apply() }, label = { Text("Longitud") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                                }
                                Text("Fuente: Open-Meteo · máximas, mínimas, lluvia y estado diario.", color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                    items(forecast) { day ->
                        Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(18.dp))) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column(Modifier.width(74.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(day.date.takeLast(5), color = muted, fontSize = 11.sp)
                                    Text(weatherEmoji(day.code), fontSize = 27.sp)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(weatherText(day.code), color = fg, fontWeight = FontWeight.SemiBold, fontSize = (14 * scale).sp)
                                    Text("Lluvia " + day.rainChance + "% · " + String.format(Locale.getDefault(), "%.1f", day.rainMm) + " mm", color = muted, fontSize = 12.sp)
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(day.maximum.toString() + "°", color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(day.minimum.toString() + "°", color = muted, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                    forecastNow?.let { current ->
                        item {
                            Surface(color = if (light) Color(0xFFDCEEFF) else Color(0xFF153451), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFB4D4E9) else Color(0xFF3B6485), RoundedCornerShape(22.dp))) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("AHORA · $forecastPlaceName", color = if (light) Color(0xFF315D7B) else Color(0xFFB9F6DC), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(weatherEmoji(current.code), fontSize = 48.sp)
                                        Column(Modifier.weight(1f)) {
                                            Text(String.format(Locale.getDefault(), "%.0f°", current.temperature), color = fg, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                                            Text(weatherText(current.code), color = muted, fontSize = 14.sp)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("Lluvia" to (current.rainChance.toString() + "%"), "Viento" to (current.wind.toInt().toString() + " km/h"), "Rachas" to (current.gust.toInt().toString() + " km/h")).forEach { metric ->
                                            Surface(color = if (light) Color(0xFFEAF4FD) else Color(0xFF0B1C2D), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                                                Column(Modifier.padding(10.dp)) {
                                                    Text(metric.first, color = muted, fontSize = 10.sp)
                                                    Text(metric.second, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (hourlyForecast.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (showAllForecastHours) "Próximas 48 horas" else "Próximas horas", color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                TextButton(onClick = { showAllForecastHours = !showAllForecastHours }) { Text(if (showAllForecastHours) "Ver menos" else "Ver 48 h", color = accentColor) }
                            }
                        }
                        items(if (showAllForecastHours) hourlyForecast else hourlyForecast.take(8)) { hour ->
                            Surface(color = card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(14.dp))) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(hour.time, color = muted, fontSize = 12.sp, modifier = Modifier.width(46.dp))
                                    Text(weatherEmoji(hour.code), fontSize = 24.sp)
                                    Column(Modifier.weight(1f)) {
                                        Text(String.format(Locale.getDefault(), "%.0f°", hour.temperature), color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(weatherText(hour.code), color = muted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("💧 " + hour.rainChance + "%", color = accentColor, fontSize = 11.sp)
                                        Text("💨 " + hour.wind.toInt(), color = muted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (forecast.isEmpty()) item { Text("Busca una ciudad o lugar para consultar la previsión y sus próximas horas.", color = muted) }
                }
                "Análisis" -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(20.dp))) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Validación histórica por estación", color = fg, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("IA que compara Weathercloud con Open-Meteo", color = muted, fontSize = 13.sp)
                                Text("Compara las observaciones de los últimos 30 días con pronósticos horarios archivados. Solo se evalúan horas coincidentes; el porcentaje no garantiza aciertos futuros.", color = muted, fontSize = 12.sp)
                                Text("Estación que quieres validar", color = muted, fontSize = 12.sp)
                                stations.forEach { station ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = validationStationId == station.id, onClick = { validationStationId = station.id; validationResult = null })
                                        Column(Modifier.weight(1f)) {
                                            Text(station.name, color = fg, fontSize = 13.sp)
                                            Text("ID " + station.id, color = muted, fontSize = 10.sp)
                                        }
                                    }
                                }
                                Button(onClick = { runHistoricalValidation() }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = accentColor), modifier = Modifier.fillMaxWidth()) {
                                    Text(if (loading) "Comparando datos archivados…" else "✦ Comparar últimos 30 días", color = Navy)
                                }
                                Text(validationMessage, color = muted, fontSize = 12.sp)
                                validationResult?.let { result ->
                                    if (result.overall != null) {
                                        Surface(color = if (light) Color(0xFFE5F4EE) else Color(0xFF12352F), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Porcentaje de acierto de Open-Meteo", color = muted, fontSize = 12.sp)
                                                Text(String.format(Locale.getDefault(), "%.0f%%", result.overall), color = if (light) Color(0xFF176B50) else Color(0xFF8EE6CF), fontSize = 38.sp, fontWeight = FontWeight.Bold)
                                                Text("Media simple de " + result.coverage + " variables evaluables", color = muted, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    result.metrics.forEach { metric ->
                                        Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(metric.label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                Text(if (metric.score == null) "Sin datos comparables (" + metric.count + " pares)" else "Acierto " + String.format(Locale.getDefault(), "%.0f%%", metric.score) + " · " + metric.count + " horas · error medio " + String.format(Locale.getDefault(), "%.2f", metric.mae) + " " + metric.unit, color = muted, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    Text("La lluvia registrada por Weathercloud puede representar intervalos distintos a la precipitación horaria del modelo. Las métricas se interpretan con cautela.", color = muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    item {
                        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(20.dp))) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Comparador estadístico · estaciones guardadas", color = fg, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("Cobertura del histórico, anomalías y ranking por estaciones.", color = muted, fontSize = 13.sp)
                                Text("Estaciones que quieres comparar", color = muted, fontSize = 12.sp)
                                stations.forEach { station ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = analysisSelectedIds.contains(station.id), onCheckedChange = { checked ->
                                            analysisSelectedIds = if (checked) analysisSelectedIds + station.id else analysisSelectedIds - station.id
                                        })
                                        Column(Modifier.weight(1f)) {
                                            Text(station.name, color = fg, fontSize = 13.sp)
                                            Text("ID " + station.id, color = muted, fontSize = 10.sp)
                                        }
                                    }
                                }
                                Text("Variable de comparación", color = muted, fontSize = 12.sp)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("temp" to "Temperatura", "hum" to "Humedad", "bar" to "Presión", "wspd" to "Viento", "gust" to "Rachas", "rain" to "Lluvia", "rainrate" to "Intensidad de lluvia", "solar" to "Radiación solar", "uv" to "Índice UV").forEach { pair ->
                                        FilterChip(selected = analysisVariable == pair.first, onClick = { analysisVariable = pair.first }, label = { Text(pair.second) })
                                    }
                                }
                                Button(onClick = { refreshAnalysis() }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = accentColor), modifier = Modifier.fillMaxWidth()) {
                                    Text(if (loading) "Consultando…" else "Comparar estaciones", color = Navy)
                                }
                                Text(analysisMessage, color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                    if (analysisResults.isNotEmpty()) {
                        val valid = analysisResults.filter { it.value != null }
                        if (valid.isNotEmpty()) {
                            item {
                                val values = valid.map { it.value!! }
                                Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(18.dp))) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Resumen comparativo", color = fg, fontWeight = FontWeight.SemiBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("Mínimo" to values.minOrNull()!!, "Media" to values.average(), "Máximo" to values.maxOrNull()!!).forEach { pair ->
                                                Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                                                    Column(Modifier.padding(10.dp)) {
                                                        Text(pair.first, color = muted, fontSize = 11.sp)
                                                        Text(String.format(Locale.getDefault(), "%.1f %s", pair.second, valid.first().unit), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                            }
                                        }
                                        Text("Las cifras muestran la media mensual de la variable elegida. El ranking se ordena por número total de observaciones históricas recuperadas, no por precisión meteorológica.", color = muted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        items(analysisResults) { item ->
                            Surface(color = card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, if (light) Color(0xFFDCE5EF) else Color(0xFF263A52), RoundedCornerShape(16.dp))) {
                                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.station.name, color = fg, fontWeight = FontWeight.SemiBold)
                                        Text("ID ${item.station.id} · ${item.observations} observaciones · ${item.variables}/9 variables", color = muted, fontSize = 11.sp)
                                    }
                                    Text(if (item.value != null) String.format(Locale.getDefault(), "%.1f %s", item.value, item.unit) else item.error.ifBlank { "Sin datos" }, color = if (item.value != null) accentColor else muted, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        item { Text("Pulsa «Comparar estaciones» para consultar las estaciones que tienes guardadas.", color = muted) }
                    }
                }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                Text("Histórico meteorológico", color = fg, fontWeight = FontWeight.Bold)
                                Text("Datos de la estación seleccionada · ${when (historyPeriod) { "day" -> "hoy"; "yesterday" -> "ayer"; "week" -> "últimos 7 días"; else -> "últimos 30 días" }}", color = muted, fontSize = 12.sp)
                                if (history.isNotEmpty()) {
                                    val values = history.map { it.second }
                                    val unit = when (historyVariable) { "temp" -> "°C"; "hum" -> "%"; "bar" -> "hPa"; "wspd", "gust" -> "km/h"; "rain" -> "mm"; else -> "" }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("Mínimo" to (values.minOrNull() ?: 0.0), "Media" to values.average(), "Máximo" to (values.maxOrNull() ?: 0.0)).forEach { pair ->
                                            Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                                                Column(Modifier.padding(10.dp)) {
                                                    Text(pair.first, color = muted, fontSize = 11.sp)
                                                    Text(String.format(Locale.getDefault(), "%.1f %s", pair.second, unit), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }
                                Text("Variable", color = muted, fontSize = 12.sp)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("temp" to "Temperatura", "hum" to "Humedad", "bar" to "Presión", "wspd" to "Viento", "rain" to "Lluvia", "gust" to "Rachas").forEach { (key, label) ->
                                        FilterChip(selected = historyVariable == key, onClick = { historyVariable = key; refreshHistory() }, label = { Text(label) })
                                    }
                                }
                                Text("Periodo", color = muted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("day" to "Hoy", "yesterday" to "Ayer", "week" to "7 días", "month" to "30 días").forEach { (key, label) ->
                                        FilterChip(selected = historyPeriod == key, onClick = { historyPeriod = key; refreshHistory() }, label = { Text(label) })
                                    }
                                }
                                Button(onClick = { refreshHistory() }, colors = ButtonDefaults.buttonColors(containerColor = accentColor), modifier = Modifier.padding(top = 8.dp)) { Text("Actualizar gráfico", color = Navy) }
                                if (history.isNotEmpty()) {
                                    val values = history.map { it.second }
                                    val min = values.minOrNull() ?: 0.0
                                    val max = values.maxOrNull() ?: 1.0
                                    Spacer(Modifier.height(12.dp))
                                    Canvas(Modifier.fillMaxWidth().height(180.dp).pointerInput(history) {
                                        detectTapGestures { offset ->
                                            if (history.isNotEmpty()) selectedHistoryIndex = if (history.size == 1) 0 else ((offset.x / size.width) * (history.size - 1)).toInt().coerceIn(0, history.lastIndex)
                                        }
                                    }) {
                                        val span = (max - min).coerceAtLeast(0.1)
                                        val points = values.mapIndexed { i, v -> androidx.compose.ui.geometry.Offset(if (values.size == 1) 0f else i * size.width / (values.size - 1), size.height - ((v - min) / span * (size.height - 12f)).toFloat() - 6f) }
                                        for (i in 0 until points.lastIndex) drawLine(accentColor, points[i], points[i + 1], strokeWidth = 3f, cap = StrokeCap.Round)
                                        if (selectedHistoryIndex in points.indices) {
                                            val point = points[selectedHistoryIndex]
                                            drawLine(accentColor.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(point.x, 0f), androidx.compose.ui.geometry.Offset(point.x, size.height), strokeWidth = 1.5f)
                                            drawCircle(accentColor, radius = 6f, center = point)
                                        }
                                    }
                                    if (selectedHistoryIndex in history.indices) {
                                        val point = history[selectedHistoryIndex]
                                        Text("Punto seleccionado · " + point.first + " · " + String.format(Locale.getDefault(), "%.2f", point.second) + " " + (when (historyVariable) { "temp" -> "°C"; "hum" -> "%"; "bar" -> "hPa"; "wspd", "gust" -> "km/h"; "rain" -> "mm"; else -> "" }), color = accentColor, fontSize = 12.sp)
                                    }
                                    val historyUnit = when (historyVariable) { "temp" -> "°C"; "hum" -> "%"; "bar" -> "hPa"; "wspd", "gust" -> "km/h"; "rain" -> "mm"; else -> "" }
                                    Text("Mín. ${String.format(Locale.getDefault(), "%.1f", min)} $historyUnit · Máx. ${String.format(Locale.getDefault(), "%.1f", max)} $historyUnit", color = muted)
                                }
                            }
                        }
                    }
                    item { Text("La versión nativa inicial usa la misma API de datos configurada en la PWA cuando se indica su URL.", color = muted, fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth().background(card).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Estación", "Pronóstico", "Análisis").forEach { label ->
                    TextButton(onClick = { tab = label; if (label == "Estación" && readings.isEmpty()) refreshCurrent() }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when(label) { "Estación" -> "☁"; "Pronóstico" -> "▦"; else -> "⌁" }, color = if (tab == label) accentColor else muted, fontSize = 20.sp)
                            Text(label, color = if (tab == label) accentColor else muted, fontSize = 11.sp, fontWeight = if (tab == label) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        if (showSettings) AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Ajustes de Weathercloud") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tema")
                    ChoiceRow(listOf("dark" to "Oscuro", "light" to "Claro", "system" to "Sistema"), theme) { theme = it; prefs.edit().putString("theme", it).apply() }
                    Text("Color de acento")
                    ChoiceRow(listOf("blue" to "Azul", "mint" to "Menta", "violet" to "Violeta", "orange" to "Naranja"), accent) { accent = it; prefs.edit().putString("accent", it).apply() }
                    Text("Tamaño del texto")
                    ChoiceRow(listOf("small" to "Pequeño", "normal" to "Normal", "large" to "Grande"), font) { font = it; prefs.edit().putString("font", it).apply() }
                    Text("Densidad de tarjetas")
                    ChoiceRow(listOf("compact" to "Compacta", "comfortable" to "Cómoda"), density) { density = it; prefs.edit().putString("density", it).apply() }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Reducir animaciones", Modifier.weight(1f)); Switch(motion, { motion = it; prefs.edit().putBoolean("motion", it).apply() }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Mostrar estaciones cercanas", Modifier.weight(1f)); Switch(nearby, { nearby = it; prefs.edit().putBoolean("nearby", it).apply() }) }
                    OutlinedButton(onClick = {
                        theme = "dark"; accent = "blue"; font = "normal"; density = "comfortable"; motion = false; nearby = true
                        prefs.edit().putString("theme", theme).putString("accent", accent).putString("font", font).putString("density", density).putBoolean("motion", motion).putBoolean("nearby", nearby).apply()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Restablecer apariencia") }
                    OutlinedButton(onClick = { showSettings = false; showStations = true }, modifier = Modifier.fillMaxWidth()) { Text("📍 Gestionar mis estaciones") }
                    Text("Conexión con los datos de Weathercloud", fontWeight = FontWeight.Bold)
                    OutlinedTextField(apiBase, { apiBase = it }, placeholder = { Text("https://tu-api.example") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { prefs.edit().putString("apiBase", apiBase.trim().trimEnd('/')).apply(); showSettings = false; refreshCurrent() }) { Text("Guardar conexión") }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Cerrar") } }
        )
        if (showStations) AlertDialog(
            onDismissRequest = { showStations = false },
            title = { Text("Gestionar mis estaciones") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stations.forEach { station ->
                        Surface(color = card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(station.name, color = fg, fontWeight = FontWeight.Bold); Text(station.id, color = muted, fontSize = 12.sp) }
                                TextButton(onClick = { selectedId = station.id; persistStations(); showStations = false; refreshCurrent() }) { Text(if (selectedId == station.id) "Activa" else "Usar") }
                                TextButton(onClick = { stations = stations.filterNot { it.id == station.id }; if (stations.isEmpty()) stations = listOf(Station("Mi estación", "3102580268")); if (stations.none { it.id == selectedId }) selectedId = stations.first().id; persistStations() }) { Text("Eliminar") }
                            }
                        }
                    }
                    OutlinedTextField(newName, { newName = it }, label = { Text("Nombre de estación") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(newId, { newId = it }, label = { Text("ID de Weathercloud") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(onClick = {
                        val id = newId.trim()
                        if (id.isNotEmpty() && stations.none { it.id == id }) {
                            stations = stations + Station(newName.trim().ifBlank { "Estación ${id.takeLast(4)}" }, id)
                            selectedId = id; persistStations(); newName = ""; newId = ""
                        }
                    }) { Text("Añadir estación") }
                }
            },
            confirmButton = { TextButton(onClick = { showStations = false }) { Text("Cerrar") } }
        )
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

private fun loadStations(prefs: android.content.SharedPreferences): List<Station> {
    return try {
        val arr = JSONArray(prefs.getString("stations", "[]") ?: "[]")
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Station(it.optString("name"), it.optString("id")) } }.ifEmpty { listOf(Station("Mi estación", "3102580268")) }
    } catch (_: Exception) { listOf(Station("Mi estación", "3102580268")) }
}
private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 12000
    connection.readTimeout = 16000
    connection.requestMethod = "GET"
    return try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $body")
        body
    } finally { connection.disconnect() }
}
private fun weatherEmoji(code: Int): String = when (code) {
    0 -> "☀️"; 1 -> "🌤️"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
    51, 53, 55, 56, 57 -> "🌦️"; 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
    71, 73, 75, 77, 85, 86 -> "❄️"; 95, 96, 99 -> "⛈️"; else -> "🌡️"
}
private fun weatherText(code: Int): String = when (code) {
    0 -> "Despejado"; 1 -> "Mayormente despejado"; 2 -> "Parcialmente nuboso"; 3 -> "Nublado"
    45, 48 -> "Niebla"; 51, 53, 55 -> "Llovizna"; 56, 57 -> "Llovizna helada"
    61, 63, 65 -> "Lluvia"; 66, 67 -> "Lluvia helada"; 71, 73, 75, 77 -> "Nieve"
    80, 81, 82 -> "Chubascos"; 85, 86 -> "Chubascos de nieve"; 95, 96, 99 -> "Tormenta"
    else -> "Tiempo variable"
}
