package hrz.rgzzn.dashb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DashBDarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = OnElectricBlue,
    secondary = AuroraPurple,
    onSecondary = OnAuroraPurple,
    tertiary = CoralAccent,
    onTertiary = OnCoralAccent,
    background = DeepNight,
    onBackground = SoftWhite,
    surface = SurfaceNight,
    onSurface = SoftWhite,
    surfaceVariant = SurfaceNightVariant,
    onSurfaceVariant = SoftWhiteVariant,
)

@Composable
fun DashBTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DashBDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
