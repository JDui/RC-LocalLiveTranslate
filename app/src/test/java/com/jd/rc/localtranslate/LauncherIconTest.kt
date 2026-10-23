package com.jd.rc.localtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun launcherUsesTransparentKonjacAndHasThemedVariant() {
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        val source = BitmapFactory.decodeResource(context.resources, R.drawable.translation_konjac)
        assertNotNull(source)
        assertTrue(source.hasAlpha())
        assertTrue(source.width >= 1024 && source.height >= 1024)
        assertEquals(0, Color.alpha(source.getPixel(0, 0)))
        assertTrue(Color.alpha(source.getPixel(source.width / 2, source.height / 2)) > 200)
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
        source.recycle()
    }

    @Test @Config(sdk = [28]) fun circularLauncherIsNonblankAtSmallSizes() {
        val preview = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val previewCanvas = Canvas(preview)
        previewCanvas.drawColor(Color.rgb(240, 245, 243))
        for ((index, size) in listOf(48, 192).withIndex()) {
            val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
            icon.setBounds(0, 0, size, size)
            val rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rendered)
            // Launcher masks are OEM-specific. Exercise an explicit circular crop.
            canvas.clipPath(android.graphics.Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW)
            })
            icon.draw(canvas)
            val colors = mutableSetOf<Int>()
            for (y in 0 until size) for (x in 0 until size) {
                val pixel = rendered.getPixel(x, y)
                colors += pixel
            }
            assertTranslationAccents(rendered)
            assertTrue("Launcher render must retain visual detail", colors.size > 100)
            assertEquals(0, Color.alpha(rendered.getPixel(0, 0)))
            previewCanvas.drawBitmap(rendered, if (index == 0) 100f else 350f, (320 - size) / 2f, null)
            rendered.recycle()
        }
        File("build/ui-screenshots").mkdirs()
        File("build/ui-screenshots/launcher-konjac.png").outputStream().use {
            preview.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        preview.recycle()
    }

    @Test fun roundedRectangleIsFilledAtSmallAndLargeSizes() {
        val preview = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val previewCanvas = Canvas(preview)
        previewCanvas.drawColor(Color.rgb(246, 239, 223))
        val coverage = mutableListOf<Float>()
        for ((index, size) in listOf(48, 192).withIndex()) {
            val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
            icon.setBounds(0, 0, size, size)
            val mask = Path().apply {
                addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * .18f, size * .18f, Path.Direction.CW)
            }
            val foreground = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(foreground).apply { clipPath(mask); icon.foreground.draw(this) }
            var objectPixels = 0
            for (y in 0 until size) for (x in 0 until size) {
                if (Color.alpha(foreground.getPixel(x, y)) > 200) objectPixels++
            }
            val fraction = objectPixels.toFloat() / (size * size)
            assertTrue("Jelly must fill the rounded rectangle at $size px: $fraction", fraction > .80f)
            coverage += fraction
            assertTranslationAccents(foreground)
            val rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            // Draw framework-sized adaptive layers with an explicit launcher mask.
            Canvas(rendered).apply {
                clipPath(mask)
                icon.background.draw(this)
                icon.foreground.draw(this)
            }
            assertEquals(0, Color.alpha(rendered.getPixel(0, 0)))
            previewCanvas.drawBitmap(rendered, if (index == 0) 100f else 350f, (320 - size) / 2f, null)
            foreground.recycle()
            rendered.recycle()
        }
        assertEquals("Small icons must retain the same visual scale", coverage[0], coverage[1], .04f)
        File("build/ui-screenshots").mkdirs()
        File("build/ui-screenshots/launcher-konjac-rounded.png").outputStream().use {
            preview.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        preview.recycle()
    }

    private fun assertTranslationAccents(bitmap: Bitmap) {
        var jade = 0
        var coral = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) <= 200) continue
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            if (g > r * 1.12f && g > b * 1.05f) jade++
            if (r > g * 1.15f && r > b * 1.15f) coral++
        }
        assertTrue("Jade translation accent must remain visible", jade > bitmap.width * bitmap.height / 100)
        assertTrue("Coral translation accent must remain visible", coral > bitmap.width * bitmap.height / 100)
    }
}
