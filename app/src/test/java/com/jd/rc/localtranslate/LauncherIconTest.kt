package com.jd.rc.localtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
            var objectPixels = 0
            for (y in 0 until size) for (x in 0 until size) {
                val pixel = rendered.getPixel(x, y)
                colors += pixel
                if (Color.red(pixel) > 140 && Color.alpha(pixel) > 200) objectPixels++
            }
            assertTrue("Launcher must show the jelly rather than only a background", objectPixels > size * size / 5)
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
}
