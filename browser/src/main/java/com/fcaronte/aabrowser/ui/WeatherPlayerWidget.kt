package com.fcaronte.aabrowser.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.WeatherData
import com.fcaronte.aabrowser.utils.fetchWeather
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes

@Composable
fun WeatherPlayerWidget(
    isPlaying: Boolean,
    classicTitle: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val weatherEnabled by AppSettings.weatherWidgetEnabled
    var weatherData by remember { mutableStateOf<WeatherData?>(null) }

    LaunchedEffect(weatherEnabled) {
        if (weatherEnabled) {
            while (true) {
                weatherData = fetchWeather(context)
                delay(30.minutes)
            }
        }
    }

    val showWeather = weatherEnabled && !isPlaying && weatherData != null

    AnimatedContent(
        targetState = showWeather,
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        modifier = modifier,
        label = "PlayerWeatherToggle"
    ) { activeWeather ->
        if (activeWeather && weatherData != null) {
            val data = weatherData!!
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = data.icon,
                    contentDescription = data.description,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${data.temperature.toInt()}°C • ${data.description}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Text(
                text = classicTitle,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
