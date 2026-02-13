package hrz.rgzzn.dashb

import android.os.Bundle
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import hrz.rgzzn.dashb.ui.theme.DashBTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DashBTheme {
                DashBApp()
            }
        }
    }
}

data class WeatherInfo(
    val city: String = "Forlì",
    val temperature: String = "--°",
    val description: String = "Caricamento meteo...",
    val nextHours: List<String> = emptyList()
)

data class NewsItem(
    val title: String,
    val source: String
)

class DashboardViewModel : ViewModel() {
    var weather by mutableStateOf(WeatherInfo())
        private set
    var news by mutableStateOf<List<NewsItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    init {
        refreshData()
        viewModelScope.launch {
            while (true) {
                delay(15 * 60 * 1000)
                refreshData()
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            loading = true
            runCatching {
                fetchWeather("Forli")
            }.onSuccess { weather = it }

            runCatching {
                fetchRss()
            }.onSuccess { news = it }
            loading = false
        }
    }

    private fun fetchWeather(city: String): WeatherInfo {
        val geoUrl = URL("https://geocoding-api.open-meteo.com/v1/search?name=$city&count=1&language=it&format=json")
        val geoJson = httpGetJson(geoUrl)
        val geo = geoJson.getJSONArray("results").getJSONObject(0)
        val lat = geo.getDouble("latitude")
        val lon = geo.getDouble("longitude")
        val cityName = geo.getString("name")

        val weatherUrl = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&hourly=temperature_2m&forecast_days=1&timezone=auto"
        )
        val weatherJson = httpGetJson(weatherUrl)
        val current = weatherJson.getJSONObject("current")
        val currentTemp = current.getDouble("temperature_2m").toInt()
        val code = current.getInt("weather_code")

        val hourly = weatherJson.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val hourLabels = mutableListOf<String>()
        for (i in 0 until minOf(6, times.length())) {
            val raw = times.getString(i)
            val time = LocalDateTime.parse(raw).format(DateTimeFormatter.ofPattern("HH:mm"))
            val temp = temps.getDouble(i).toInt()
            hourLabels += "$time  ${temp}°"
        }

        return WeatherInfo(
            city = cityName,
            temperature = "$currentTemp°",
            description = weatherCodeToItalian(code),
            nextHours = hourLabels
        )
    }

    private fun fetchRss(): List<NewsItem> {
        val url = URL("https://www.ansa.it/sito/ansait_rss.xml")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        connection.inputStream.use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            var event = parser.eventType
            val news = mutableListOf<NewsItem>()
            var title: String? = null

            while (event != XmlPullParser.END_DOCUMENT && news.size < 8) {
                if (event == XmlPullParser.START_TAG && parser.name.equals("title", true)) {
                    val value = parser.nextText().trim()
                    if (!value.contains("ANSA", ignoreCase = true)) {
                        title = value
                    }
                }
                if (event == XmlPullParser.END_TAG && parser.name.equals("item", true) && !title.isNullOrBlank()) {
                    news += NewsItem(title = title!!, source = "ANSA")
                    title = null
                }
                event = parser.next()
            }
            return news
        }
    }

    private fun httpGetJson(url: URL): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun weatherCodeToItalian(code: Int): String = when (code) {
        0 -> "Sereno"
        1, 2 -> "Poco nuvoloso"
        3 -> "Coperto"
        45, 48 -> "Nebbia"
        51, 53, 55, 56, 57 -> "Pioviggine"
        61, 63, 65, 80, 81, 82 -> "Pioggia"
        66, 67 -> "Pioggia gelata"
        71, 73, 75, 77, 85, 86 -> "Neve"
        95, 96, 99 -> "Temporale"
        else -> "Variabile"
    }
}

@Composable
private fun DashBApp(vm: DashboardViewModel = viewModel()) {
    var currentTime by androidx.compose.runtime.remember { mutableStateOf("--:--") }
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALY)

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalDateTime.now().format(formatter)
            delay(1_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF0A1A33), Color(0xFF1B3A5D), Color(0xFF29557C))
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DashboardCard {
                Text("Ciao, Luca", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(currentTime, style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DashboardCard(modifier = Modifier.weight(1f)) {
                    Text("Meteo · ${vm.weather.city}", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(vm.weather.temperature, style = MaterialTheme.typography.displaySmall, color = Color.White)
                    Text(vm.weather.description, color = Color.White.copy(alpha = 0.88f))
                    Spacer(Modifier.height(10.dp))
                    vm.weather.nextHours.forEach {
                        Text("• $it", color = Color.White.copy(alpha = 0.8f))
                    }
                }

                DashboardCard(modifier = Modifier.weight(1f)) {
                    Text("Agenda", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Versione Android TV: integrazione calendario pronta per Google/Outlook (OAuth Device Flow da implementare).",
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            DashboardCard {
                Text("Notizie", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                vm.news.forEach {
                    Text("• ${it.title}", color = Color.White.copy(alpha = 0.9f))
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        if (vm.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }
    }
}

@Composable
private fun DashboardCard(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(18.dp)
    ) {
        content()
    }
}
