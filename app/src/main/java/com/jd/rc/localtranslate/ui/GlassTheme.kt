package com.jd.rc.localtranslate.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import com.jd.rc.localtranslate.data.ThemeMode
import com.jd.rc.localtranslate.data.UserSettings

private val LightColors = lightColorScheme(
    primary = Color(0xFF79572D), onPrimary = Color(0xFFFFF8E8),
    primaryContainer = Color(0xFFEEE1C4), onPrimaryContainer = Color(0xFF3E2C15),
    secondary = Color(0xFF885548), onSecondary = Color(0xFFFFF7ED),
    secondaryContainer = Color(0xFFF3E5DD), onSecondaryContainer = Color(0xFF442B22),
    tertiary = Color(0xFF68613A), onTertiary = Color(0xFFFFF8E8),
    tertiaryContainer = Color(0xFFEDE7D1), onTertiaryContainer = Color(0xFF36321B),
    background = Color(0xFFF6EFDF), onBackground = Color(0xFF342C20),
    surface = Color(0xFFFCF7EB), onSurface = Color(0xFF342C20),
    surfaceVariant = Color(0xFFF0E7D4), onSurfaceVariant = Color(0xFF655944),
    surfaceTint = Color(0xFF79572D),
    surfaceDim = Color(0xFFE7DDC9), surfaceBright = Color(0xFFFFFBF2),
    surfaceContainerLowest = Color(0xFFFFFCF5), surfaceContainerLow = Color(0xFFFCF7EB),
    surfaceContainer = Color(0xFFF8F1E2), surfaceContainerHigh = Color(0xFFF2E9D7),
    surfaceContainerHighest = Color(0xFFEBE1CD),
    outline = Color(0xFF88785E), outlineVariant = Color(0xFFD8CCB5),
    inverseSurface = Color(0xFF393024), inverseOnSurface = Color(0xFFFAF0D9),
    inversePrimary = Color(0xFFE4BF83))
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE4BF83), onPrimary = Color(0xFF432D10),
    primaryContainer = Color(0xFF5B4222), onPrimaryContainer = Color(0xFFF6DCAE),
    secondary = Color(0xFFE4B6A3), onSecondary = Color(0xFF482A20),
    secondaryContainer = Color(0xFF654034), onSecondaryContainer = Color(0xFFFFDACC),
    tertiary = Color(0xFFD1C792), onTertiary = Color(0xFF383313),
    tertiaryContainer = Color(0xFF504A28), onTertiaryContainer = Color(0xFFEDE3AC),
    background = Color(0xFF211D16), onBackground = Color(0xFFF0E5CF),
    surface = Color(0xFF2B251C), onSurface = Color(0xFFF0E5CF),
    surfaceVariant = Color(0xFF483F30), onSurfaceVariant = Color(0xFFD0C2A7),
    surfaceTint = Color(0xFFE4BF83),
    surfaceDim = Color(0xFF211D16), surfaceBright = Color(0xFF514637),
    surfaceContainerLowest = Color(0xFF19160F), surfaceContainerLow = Color(0xFF29231A),
    surfaceContainer = Color(0xFF30291F), surfaceContainerHigh = Color(0xFF3B3226),
    surfaceContainerHighest = Color(0xFF463C2E),
    outline = Color(0xFF9B8D74), outlineVariant = Color(0xFF5B4F3C),
    inverseSurface = Color(0xFFF0E5CF), inverseOnSurface = Color(0xFF342C20),
    inversePrimary = Color(0xFF79572D))
val LocalOpaque = compositionLocalOf { false }
val LocalDark = compositionLocalOf { false }

internal object UiSpacing {
    val Page = 16.dp
    val Panel = 8.dp
    // A 12 dp panel around an 8 dp control uses a concentric 4 dp inset.
    val PanelInset = 4.dp
    val Inline = 8.dp
    val Compact = 4.dp
    val Section = 24.dp
    val TouchTarget = 48.dp
    val Icon = 24.dp
    val ActionIcon = 20.dp
}

internal object UiCorners {
    val Control = 8.dp
    val Panel = 12.dp
}

private val PaperShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(UiCorners.Control),
    medium = RoundedCornerShape(UiCorners.Control),
    large = RoundedCornerShape(UiCorners.Panel),
    extraLarge = RoundedCornerShape(16.dp),
)

private fun paperTypography(serif: Boolean): Typography {
    val family = if (serif) FontFamily.Serif else FontFamily.SansSerif
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

@Composable
fun GlassTheme(settings: UserSettings, content: @Composable () -> Unit) {
    val typography = remember(settings.serifFont) { paperTypography(settings.serifFont) }
    val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val view = LocalView.current
    val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    SideEffect { activity?.window?.let { window ->
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    } }
    CompositionLocalProvider(LocalOpaque provides settings.reduceTransparency, LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = PaperShapes, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
}

@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.background))
}

@Composable
fun GlassSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val opaque = LocalOpaque.current
    val shape = MaterialTheme.shapes.large
    val colors = MaterialTheme.colorScheme
    Box(modifier.shadow(if (opaque) 0.dp else 2.dp, shape,
        ambientColor = colors.onSurface.copy(alpha = .06f), spotColor = colors.onSurface.copy(alpha = .08f)).clip(shape)
        .background(colors.surface)
        .border(1.dp, colors.outlineVariant, shape),
        content = content)
}
