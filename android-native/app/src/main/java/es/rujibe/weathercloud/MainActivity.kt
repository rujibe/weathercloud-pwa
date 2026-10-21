package es.rujibe.weathercloud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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

@Composable
fun WeathercloudApp(prefs: android.content.SharedPreferences) {
    val scope = rememberCoroutineScope()
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
    var newName by remember { mutableStateOf("") }
    var newId by remember { mutableStateOf("") }
    var readings by remember { mutableStateOf<List<Reading>>(emptyList()) }
    var forecast by remember { mutableStateOf<List<String>>(emptyList()) }
    var history by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var status by remember { mutableStateOf("Listo para consultar la estación") }
    var loading by remember { mutableStateOf(false) }
    var forecastLat by remember { mutableStateOf(prefs.getString("forecastLat", "40.4168") ?: "40.4168") }
    var forecastLon by remember { mutableStateOf(prefs.getString("forecastLon", "-3.7038") ?: "-3.7038") }
    val accentColor = when (accent) { "mint" -> Color(0xFF8EE6CF); "gold" -> Color(0xFFFFD27B); "purple" -> Color(0xFFC5A4FF); else -> Blue }
    val light = theme == "light"
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
                    arrayOf("solar","Radiación solar","W/m²","☀️"), arrayOf("uvi","Índice UV","","🕶"),
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
                    val day = d.getJSONArray("time").getString(i)
                    "$day · ${weatherText(d.getJSONArray("weather_code").optInt(i))} · ${d.getJSONArray("temperature_2m_min").optDouble(i).toInt()}° / ${d.getJSONArray("temperature_2m_max").optDouble(i).toInt()}° · lluvia ${d.getJSONArray("precipitation_probability_max").optInt(i)}% · ${d.getJSONArray("precipitation_sum").optDouble(i)} mm"
                }
                val cur = data.optJSONObject("current")
                if (cur != null) status = "Previsión cargada · ahora ${cur.optDouble("temperature_2m").toInt()}°C · viento ${cur.optDouble("wind_speed_10m").toInt()} km/h"
            } catch (e: Exception) { status = "Error al cargar previsión: ${e.message}" }
            loading = false
        }
    }
    fun refreshHistory() {
        val station = stations.firstOrNull { it.id == selectedId } ?: return
        loading = true
        scope.launch {
            try {
                val base = apiBase.trim().trimEnd('/')
                val json = withContext(Dispatchers.IO) {
                    if (base.isNotBlank()) JSONObject(httpGet("$base/api/history?station_id=${enc(station.id)}&variable=temp&period=month"))
                    else JSONObject(httpGet("https://rujibe.github.io/weathercloud-pwa/history.json")).optJSONObject("stations")?.optJSONObject(station.id)?.optJSONObject("series") ?: JSONObject()
                }
                val pts = json.optJSONArray("points") ?: json.optJSONArray("temp") ?: JSONArray()
                history = (0 until pts.length()).mapNotNull { i ->
                    val p = pts.optJSONObject(i) ?: return@mapNotNull null
                    val v = p.optDouble("value", Double.NaN)
                    if (v.isNaN()) null else (p.optLong("time").toString() to v)
                }.takeLast(90)
                status = "Histórico de temperatura · ${history.size} puntos"
            } catch (e: Exception) { status = "No se pudo cargar el histórico: ${e.message}" }
            loading = false
        }
    }

    MaterialTheme(colorScheme = if (light) lightColorScheme(primary = accentColor, background = bg, surface = card, onSurface = fg) else darkColorScheme(primary = accentColor, background = bg, surface = card, onSurface = fg)) {
        Column(Modifier.fillMaxSize().background(bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("WEATHERCLOUD", color = accentColor, fontSize = (12 * scale).sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(when(tab) { "Estación" -> "Mi estación"; "Pronóstico" -> "Previsión de 7 días"; "Análisis" -> "Análisis e históricos"; else -> tab }, color = fg, fontSize = (24 * scale).sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { showSettings = true }) { Text("⚙ Ajustes", color = accentColor) }
            }
            Text(status, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), color = muted, fontSize = (12 * scale).sp)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accentColor)
            when (tab) {
                "Estación" -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(color = card, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                Text(stations.firstOrNull { it.id == selectedId }?.name ?: "Mi estación", color = muted)
                                val temp = readings.firstOrNull { it.key == "temp" }
                                Text(if (temp != null) "${temp.value} °C" else "— °C", color = fg, fontSize = (46 * scale).sp, fontWeight = FontWeight.Bold)
                                Text("Datos meteorológicos actuales", color = muted)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { refreshCurrent() }, colors = ButtonDefaults.buttonColors(containerColor = accentColor)) { Text("Actualizar", color = Navy) }
                                    OutlinedButton(onClick = { showStations = true }) { Text("Mis estaciones", color = fg) }
                                }
                            }
                        }
                    }
                    if (readings.isEmpty()) item { Text("Pulsa Actualizar para consultar las lecturas de tu estación.", color = muted, modifier = Modifier.padding(8.dp)) }
                    items(readings.chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { r ->
                                Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("${r.icon}  ${r.label}", color = muted, fontSize = (12 * scale).sp)
                                        Text("${r.value} ${r.unit}", color = fg, fontSize = (22 * scale).sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    item { Text("El indicador de cambio meteorológico y los detalles avanzados se irán incorporando a esta interfaz nativa.", color = muted, fontSize = 12.sp) }
                }
                "Pronóstico" -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Ubicación de la previsión", color = fg, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(forecastLat, { forecastLat = it; prefs.edit().putString("forecastLat", it).apply() }, label = { Text("Latitud") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                                    OutlinedTextField(forecastLon, { forecastLon = it; prefs.edit().putString("forecastLon", it).apply() }, label = { Text("Longitud") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                                }
                                Button(onClick = { refreshForecast() }, modifier = Modifier.padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = accentColor)) { Text("Cargar previsión", color = Navy) }
                                Text("Fuente: Open-Meteo · se muestran máximas, mínimas, lluvia y estado diario.", color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                    items(forecast) { day ->
                        Surface(color = card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Text(day, Modifier.padding(16.dp), color = fg, fontSize = (14 * scale).sp) }
                    }
                    if (forecast.isEmpty()) item { Text("Carga la previsión. Puedes usar las coordenadas de tu ubicación o de la zona que quieras consultar.", color = muted) }
                }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                Text("Histórico de temperatura", color = fg, fontWeight = FontWeight.Bold)
                                Text("Últimos datos disponibles para la estación seleccionada.", color = muted, fontSize = 12.sp)
                                Button(onClick = { refreshHistory() }, colors = ButtonDefaults.buttonColors(containerColor = accentColor), modifier = Modifier.padding(top = 8.dp)) { Text("Cargar histórico", color = Navy) }
                                if (history.isNotEmpty()) {
                                    val values = history.map { it.second }
                                    val min = values.minOrNull() ?: 0.0
                                    val max = values.maxOrNull() ?: 1.0
                                    Spacer(Modifier.height(12.dp))
                                    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                                        val span = (max - min).coerceAtLeast(0.1)
                                        val points = values.mapIndexed { i, v -> androidx.compose.ui.geometry.Offset(if (values.size == 1) 0f else i * size.width / (values.size - 1), size.height - ((v - min) / span * (size.height - 12f)).toFloat() - 6f) }
                                        for (i in 0 until points.lastIndex) drawLine(accentColor, points[i], points[i + 1], strokeWidth = 3f, cap = StrokeCap.Round)
                                    }
                                    Text("Mín. ${String.format(Locale.getDefault(), "%.1f", min)} °C · Máx. ${String.format(Locale.getDefault(), "%.1f", max)} °C", color = muted)
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
                    ChoiceRow(listOf("blue" to "Azul", "mint" to "Menta", "gold" to "Dorado", "purple" to "Violeta"), accent) { accent = it; prefs.edit().putString("accent", it).apply() }
                    Text("Tamaño de letra")
                    ChoiceRow(listOf("small" to "Pequeño", "normal" to "Normal", "large" to "Grande"), font) { font = it; prefs.edit().putString("font", it).apply() }
                    Text("Densidad de tarjetas")
                    ChoiceRow(listOf("compact" to "Compacta", "comfortable" to "Cómoda"), density) { density = it; prefs.edit().putString("density", it).apply() }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Reducir animaciones", Modifier.weight(1f)); Switch(motion, { motion = it; prefs.edit().putBoolean("motion", it).apply() }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Estaciones cercanas", Modifier.weight(1f)); Switch(nearby, { nearby = it; prefs.edit().putBoolean("nearby", it).apply() }) }
                    Text("URL del puente de datos", fontWeight = FontWeight.Bold)
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
private fun weatherText(code: Int): String = when (code) {
    0 -> "Despejado"; 1 -> "Mayormente despejado"; 2 -> "Parcialmente nuboso"; 3 -> "Nublado"
    45, 48 -> "Niebla"; 51, 53, 55 -> "Llovizna"; 56, 57 -> "Llovizna helada"
    61, 63, 65 -> "Lluvia"; 66, 67 -> "Lluvia helada"; 71, 73, 75, 77 -> "Nieve"
    80, 81, 82 -> "Chubascos"; 85, 86 -> "Chubascos de nieve"; 95, 96, 99 -> "Tormenta"
    else -> "Tiempo variable"
}
