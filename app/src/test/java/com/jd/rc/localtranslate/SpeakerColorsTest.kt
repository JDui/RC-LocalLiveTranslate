package com.jd.rc.localtranslate

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import com.jd.rc.localtranslate.ui.*
import org.junit.Assert.*
import org.junit.Test

class SpeakerColorsTest {
    @Test fun everyAllocatedSpeakerHasStableDistinctColoursAndReadableTextInBothThemes() {
        assertEquals(16, ('A'..'P').map { speakerAccent(it.toString()) }.distinct().size)
        val themes = listOf(
            listOf(Color(0xFF342C20), Color(0xFF655944), Color(0xFFF6EFDF), Color(0xFFFCF7EB)),
            listOf(Color(0xFFF0E5CF), Color(0xFFD0C2A7), Color(0xFF211D16), Color(0xFF2B251C)))
        for ((foreground, secondary, background, surface) in themes) for (id in 'A'..'P') {
            val badge = requireNotNull(speakerAccent(id.toString())).copy(alpha = .12f).compositeOver(background)
            val label = readableSpeakerColor(foreground, id.toString(), background, surface, extraBackground = badge, tint = 1f)
            assertTrue(textContrast(label, badge) >= 4.5f)
            assertTrue(textContrast(label, background) >= 4.5f)
            assertTrue(textContrast(label, surface) >= 4.5f)
            for (base in listOf(foreground, secondary)) {
                val text = readableSpeakerColor(base, id.toString(), background, surface)
                assertTrue("$id on background", textContrast(text, background) >= 4.5f)
                assertTrue("$id on surface", textContrast(text, surface) >= 4.5f)
                val mixed = lerp(base, requireNotNull(speakerAccent(id.toString())), .33f)
                if (textContrast(mixed, background) >= 4.5f && textContrast(mixed, surface) >= 4.5f) assertEquals(mixed, text)
            }
        }
        assertEquals(Color.Gray, readableSpeakerColor(Color.Gray, null, Color.White, Color.White))
    }
}
