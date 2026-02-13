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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hrz.rgzzn.dashb.ui.theme.DashBTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
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
    val nextHours: List<String> = emptyList(),
    val nextDays: List<String> = emptyList(),
)

data class NewsItem(
    val title: String,
    val source: String,
)

data class AgendaItem(
    val dayLabel: String,
    val title: String,
    val detail: String,
)

class DashboardViewModel : ViewModel() {
    var weather by mutableStateOf(WeatherInfo())
        private set
    var news by mutableStateOf<List<NewsItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    val agenda = listOf(
        AgendaItem("LUNEDÌ 9 FEBBRAIO", "Ferie Niko", "Tutto il giorno"),
        AgendaItem("DOMANI", "Cena San Valentino", "20:00 · Casa Brigandi"),
        AgendaItem("DOMENICA 15 FEBBRAIO", "Music Session", "Tutto il giorno"),
        AgendaItem("LUNEDÌ 16 FEBBRAIO", "Assente LR", "09:00 · Ufficio"),
    )

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
            withContext(Dispatchers.Main) { loading = true }

            runCatching { fetchWeather("Forli") }
                .onSuccess { withContext(Dispatchers.Main) { weather = it } }

            runCatching { fetchRss() }
                .onSuccess { withContext(Dispatchers.Main) { news = it } }

            withContext(Dispatchers.Main) { loading = false }
        }
    }

    private fun fetchWeather(city: String): WeatherInfo {
        val geoUrl = URL("https://geocoding-api.open-meteo.com/v1/search?name=$city&count=1&language=it&format=json")
        val geo = httpGetJson(geoUrl).getJSONArray("results").getJSONObject(0)
        val lat = geo.getDouble("latitude")
        val lon = geo.getDouble("longitude")
        val cityName = geo.getString("name")

        val weatherUrl = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=6&timezone=auto"
        )
        val weatherJson = httpGetJson(weatherUrl)
        val current = weatherJson.getJSONObject("current")
        val currentTemp = current.getDouble("temperature_2m").toInt()
        val code = current.getInt("weather_code")

        val hourly = weatherJson.getJSONObject("hourly")
        val hourlyTimes = hourly.getJSONArray("time")
        val hourlyTemps = hourly.getJSONArray("temperature_2m")

        val hourLabels = buildList {
            for (i in 0 until minOf(4, hourlyTimes.length())) {
                val time = LocalDateTime.parse(hourlyTimes.getString(i))
                    .format(DateTimeFormatter.ofPattern("HH:mm"))
                add("$time  ${hourlyTemps.getDouble(i).toInt()}°")
            }
        }

        val daily = weatherJson.getJSONObject("daily")
        val days = daily.getJSONArray("time")
        val maxTemps = daily.getJSONArray("temperature_2m_max")
        val minTemps = daily.getJSONArray("temperature_2m_min")

        val dayLabels = buildList {
            for (i in 0 until minOf(5, days.length())) {
                val day = LocalDate.parse(days.getString(i))
                val dayLabel = day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                add("$dayLabel  ${maxTemps.getDouble(i).toInt()}° ${minTemps.getDouble(i).toInt()}°")
            }
        }

        return WeatherInfo(
            city = cityName,
            temperature = "$currentTemp°",
            description = weatherCodeToItalian(code),
            nextHours = hourLabels,
            nextDays = dayLabels,
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
            var insideItem = false
            var title: String? = null
            val items = mutableListOf<NewsItem>()

            while (event != XmlPullParser.END_DOCUMENT && items.size < 6) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name.equals("item", ignoreCase = true)) insideItem = true
                        if (insideItem && parser.name.equals("title", ignoreCase = true)) {
                            title = parser.nextText().trim()
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("item", ignoreCase = true)) {
                            insideItem = false
                            title
                                ?.takeIf { it.isNotBlank() }
                                ?.let { items += NewsItem(title = it, source = "ANSA") }
                            title = null
                        }
                    }
                }
                event = parser.next()
            }
            return items
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
    var currentDate by androidx.compose.runtime.remember { mutableStateOf("--") }
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALY)
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALY)

    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            currentTime = now.format(timeFormatter)
            currentDate = now.format(dateFormatter).replaceFirstChar { it.uppercase() }
            delay(1_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF04042A), Color(0xFF160E4F), Color(0xFF2B1A6C)),
                ),
            )
            .padding(horizontal = 42.dp, vertical = 28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Buongiorno, Luca", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        vm.weather.description.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(currentTime, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                    Text(currentDate, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                GlassCard(modifier = Modifier.weight(1.05f)) {
                    Text("Meteo · ${vm.weather.city}", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(vm.weather.temperature, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    Text(vm.weather.description, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("Prossime ore", style = MaterialTheme.typography.titleMedium)
                    vm.weather.nextHours.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Spacer(Modifier.height(12.dp))
                    Text("Previsioni 5 giorni", style = MaterialTheme.typography.titleMedium)
                    vm.weather.nextDays.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }

                GlassCard(modifier = Modifier.weight(1.12f)) {
                    Text("Agenda", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    vm.agenda.forEach {
                        AgendaPill(day = it.dayLabel, title = it.title, detail = it.detail)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                NewsHeroCard(
                    modifier = Modifier.weight(1.8f),
                    mainNews = vm.news.firstOrNull()?.title ?: "Caricamento notizie...",
                )
            }
        }

        if (vm.loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
private fun AgendaPill(day: String, title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NewsHeroCard(modifier: Modifier = Modifier, mainNews: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11162E)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF324772), Color(0xCC11162E), Color(0xFF0A0B16)),
                    ),
                )
                .padding(24.dp),
        ) {
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    "IL RESTO DEL CARLINO",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    mainNews,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text("Aggiornamento live", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(74.dp)
                    .height(74.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.88f)),
            )
        }
    }
}
