package com.tankful.android.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.tankful.android.R

private val NightBackground = Color(0xFF07090E)
private val NightSurface = Color(0xFF12161F)
private val NightSurfaceHigh = Color(0xFF1B2130)
private val NightText = Color(0xFFF4F0E6)
private val NightTextSecondary = Color(0xFFC4CBD6)
private val NightTextTertiary = Color(0xFF8B93A3)
private val NightGold = Color(0xFFE6B325)
private val NightInk = Color(0xFF14120A)
private val NightAbove = Color(0xFFF0C2B4)
private val NightBelow = Color(0xFF9FD9C8)
private val NightHairline = Color(0xFF2C3344)

private val DayBackground = Color(0xFFF4F1E8)
private val DaySurface = Color(0xFFFFFCF7)
private val DaySurfaceHigh = Color(0xFFE7E2D6)
private val DayText = Color(0xFF14181F)
private val DayTextSecondary = Color(0xFF3E4856)
private val DayTextTertiary = Color(0xFF5C6674)
private val DayGold = Color(0xFF8A5A00)
private val DayButton = Color(0xFF1C2433)
private val DayAbove = Color(0xFF7A3328)
private val DayBelow = Color(0xFF1F5C45)
private val DayHairline = Color(0xFFD5D2C8)

val Figures = FontFamily(
    Font(R.font.barlow_condensed_regular, FontWeight.Normal),
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
)

val Reading = FontFamily(
    Font(R.font.source_sans_3_regular, FontWeight.Normal),
    Font(R.font.source_sans_3_medium, FontWeight.Medium),
)

val HeroStyle = TextStyle(
    fontFamily = Figures,
    fontWeight = FontWeight.Medium,
    fontSize = 64.sp,
    lineHeight = 64.sp,
    letterSpacing = (-0.5).sp,
    fontFeatureSettings = "tnum",
)

val MetricStyle = TextStyle(
    fontFamily = Figures,
    fontWeight = FontWeight.Medium,
    fontSize = 28.sp,
    fontFeatureSettings = "tnum",
)

val FieldStyle = TextStyle(
    fontFamily = Figures,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    fontFeatureSettings = "tnum",
)

val HudLabel = TextStyle(
    fontFamily = Figures,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 1.2.sp,
)

private val NightScheme = darkColorScheme(
    background = NightBackground,
    surface = NightSurface,
    surfaceVariant = NightSurfaceHigh,
    surfaceContainer = NightSurface,
    surfaceContainerLow = NightBackground,
    surfaceContainerHigh = NightSurfaceHigh,
    primary = NightGold,
    onPrimary = NightInk,
    secondary = NightGold,
    onSecondary = NightInk,
    onBackground = NightText,
    onSurface = NightText,
    onSurfaceVariant = NightTextSecondary,
    outline = NightHairline,
    error = NightAbove,
    tertiary = NightBelow,
)

private val DayScheme = lightColorScheme(
    background = DayBackground,
    surface = DaySurface,
    surfaceVariant = DaySurfaceHigh,
    surfaceContainer = DaySurface,
    surfaceContainerLow = DayBackground,
    surfaceContainerHigh = DaySurfaceHigh,
    primary = DayButton,
    onPrimary = DayBackground,
    secondary = DayGold,
    onSecondary = DayBackground,
    onBackground = DayText,
    onSurface = DayText,
    onSurfaceVariant = DayTextSecondary,
    outline = DayHairline,
    error = DayAbove,
    tertiary = DayBelow,
)

private val Type = Typography(
    headlineLarge = HeroStyle,
    headlineMedium = MetricStyle,
    titleLarge = TextStyle(fontFamily = Figures, fontWeight = FontWeight.Medium, fontSize = 28.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontFamily = Reading, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = Reading, fontSize = 12.sp),
    bodyMedium = TextStyle(fontFamily = Reading, fontSize = 15.sp),
)

private val Corners = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
)

@Composable
fun TankfulTheme(dark: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect { paintBars(view, dark) }
    }
    MaterialTheme(
        colorScheme = if (dark) NightScheme else DayScheme,
        typography = Type,
        shapes = Corners,
        content = content,
    )
}

private fun paintBars(view: android.view.View, dark: Boolean) {
    val activity = findActivity(view.context) ?: return
    val controller = WindowCompat.getInsetsController(activity.window, view)
    controller.isAppearanceLightStatusBars = !dark
    controller.isAppearanceLightNavigationBars = !dark
}

private fun findActivity(context: android.content.Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
fun groupOutline(): Color {
    if (MaterialTheme.colorScheme.background.red < 0.2f) return Color(0xFFE6B325)
    return Color(0xFF1E4D8C)
}

@Composable
fun prefersDark(theme: String): Boolean {
    if (theme == "LIGHT") return false
    if (theme == "DARK") return true
    return isSystemInDarkTheme()
}

val TertiaryInk = NightTextTertiary
val DayTertiaryInk = DayTextTertiary
