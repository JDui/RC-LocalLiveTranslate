package com.jd.rc.localtranslate.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Stable session labels never derive their colour from language or row order. */
internal fun speakerAccent(id: String?): Color? {
    val index = id?.singleOrNull()?.takeIf { it in 'A'..'Z' }?.minus('A') ?: return null
    return Color.hsv((index * 137.508f + 25f) % 360f, .72f, .78f)
}

internal fun textContrast(foreground: Color, background: Color): Float {
    val a = foreground.luminance(); val b = background.luminance()
    return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
}

internal fun readableSpeakerColor(base: Color, id: String?, background: Color, surface: Color,
    extraBackground: Color? = null, tint: Float = .33f): Color {
    val backgrounds = listOfNotNull(background, surface, extraBackground)
    val accent = speakerAccent(id) ?: return base
    val mixed = lerp(base, accent, tint)
    if (backgrounds.all { textContrast(mixed, it) >= 4.5f }) return mixed
    val target = if (backgrounds.sumOf { it.luminance().toDouble() } / backgrounds.size > .5) Color.Black else Color.White
    for (step in 1..100) {
        val adjusted = lerp(mixed, target, step / 100f)
        if (backgrounds.all { textContrast(adjusted, it) >= 4.5f }) return adjusted
    }
    return base
}
