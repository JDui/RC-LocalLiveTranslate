package com.jd.rc.localtranslate.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import com.jd.rc.localtranslate.data.ThemeMode
import com.jd.rc.localtranslate.data.UserSettings

val Jade = Color(0xFF126F65)
val Coral = Color(0xFFAE514E)
private val LightColors = lightColorScheme(primary = Jade, onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEE7), onPrimaryContainer = Color(0xFF14483F),
    secondary = Coral, secondaryContainer = Color(0xFFF0E1DE), onSecondaryContainer = Color(0xFF653C37),
    surface = Color(0xFFF3F6F5), onSurface = Color(0xFF202D2B),
    onSurfaceVariant = Color(0xFF52645F), surfaceVariant = Color(0xFFE1EBE7),
    background = Color(0xFFEDF3F1), outlineVariant = Color(0xFFC5D3CE))
private val DarkColors = darkColorScheme(primary = Color(0xFF93DACA), onPrimary = Color(0xFF003B32),
    primaryContainer = Color(0xFF244B43), onPrimaryContainer = Color(0xFFBCECDF),
    secondary = Color(0xFFF0ABA2), secondaryContainer = Color(0xFF553B36), onSecondaryContainer = Color(0xFFEEC6BD),
    surface = Color(0xFF192321), onSurface = Color(0xFFE5EEEA),
    onSurfaceVariant = Color(0xFFB6C8C0), surfaceVariant = Color(0xFF2D3C37),
    background = Color(0xFF111C19), outlineVariant = Color(0xFF43574F))
val LocalOpaque = compositionLocalOf { false }
val LocalDark = compositionLocalOf { false }

@Composable
fun GlassTheme(settings: UserSettings, content: @Composable () -> Unit) {
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
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
}

@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val dark = LocalDark.current
    val base = MaterialTheme.colorScheme.background
    Canvas(modifier.background(base)) {
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(-w * .4f, h * .25f)
            cubicTo(w * .25f, -h * .05f, w * .2f, h * .65f, w * 1.3f, h * .42f)
        }
        drawPath(path, Brush.linearGradient(listOf(
            Color(0xFF75B9A7).copy(alpha = if (dark) .10f else .15f),
            Color(0xFF94CCD2).copy(alpha = if (dark) .07f else .20f))), style = Stroke(w * .7f))
        val lower = Path().apply {
            moveTo(-w * .3f, h * .92f)
            cubicTo(w * .35f, h * .72f, w * .7f, h * 1.05f, w * 1.25f, h * .62f)
        }
        drawPath(lower, Brush.linearGradient(listOf(Color(0xFFD0ABAE).copy(alpha = if (dark) .08f else .17f),
            Color(0xFFB4CDC2).copy(alpha = .08f))), style = Stroke(w * .4f))
        drawPath(path, Color.White.copy(alpha = if (dark) .025f else .22f), style = Stroke(1.dp.toPx()))
    }
}

@Composable
fun GlassSurface(modifier: Modifier = Modifier, radius: Int = 28, content: @Composable BoxScope.() -> Unit) {
    val dark = LocalDark.current
    val opaque = LocalOpaque.current
    val shape = RoundedCornerShape(radius.dp)
    val surface = MaterialTheme.colorScheme.surface
    val tint = if (dark) Color(0xFF4D655C) else Color.White
    Box(modifier.shadow(if (opaque) 0.dp else 12.dp, shape, ambientColor = Color(0xFF294C40).copy(alpha = .08f),
        spotColor = Color(0xFF294C40).copy(alpha = .12f)).clip(shape)
        .background(if (opaque) Brush.linearGradient(listOf(surface, surface)) else Brush.linearGradient(
            listOf(tint.copy(alpha = if (dark) .36f else .78f), surface.copy(alpha = .67f), tint.copy(alpha = if (dark) .20f else .51f)),
            start = Offset.Zero, end = Offset(700f, 500f)))
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .27f else .95f),
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f), Color.White.copy(alpha = if (dark) .1f else .7f))), shape),
        content = content)
}
