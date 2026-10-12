package com.jd.rc.localtranslate

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jd.rc.localtranslate.core.SourceLanguage
import com.jd.rc.localtranslate.inference.HyTranslator
import com.jd.rc.localtranslate.model.HyModelProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.SystemClock
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class NativeHyTranslationTest {
    @Test fun importedStqWeightsTranslateSixDirectionsAndReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as TranslateApplication
        val input = InstrumentationRegistry.getArguments().getString("modelPackage") ?: error("Provide private modelPackage path")
        val archive = File(input).canonicalFile
        require(archive.path.startsWith(app.filesDir.canonicalPath + File.separator))
        app.models.import(Uri.fromFile(archive))
        assertEquals("模型文件已校验。", app.models.state.value.message)
        assertEquals(HyModelProfile.VERSION, app.models.state.value.installed.single { it.id == "hy-mt2" }.version)
        val model = File(app.filesDir, "models/hy-mt2/model.gguf")
        assertEquals(HyModelProfile.SIZE, model.length())
        val samples = JSONArray()
        for ((source, target) in listOf(
            "Good morning." to SourceLanguage.ZH, "おはようございます。" to SourceLanguage.ZH,
            "早上好。" to SourceLanguage.JA, "Good morning." to SourceLanguage.JA,
            "早上好。" to SourceLanguage.EN, "おはようございます。" to SourceLanguage.EN)) {
            val translator = HyTranslator(model)
            try {
                val start = SystemClock.elapsedRealtime()
                val result = translator.translate(source, target)
                assertTrue("Expected morning greeting in $target: $result", when (target) {
                    SourceLanguage.ZH -> result.contains("早")
                    SourceLanguage.JA -> result.contains("おはよう")
                    SourceLanguage.EN -> result.lowercase().contains("morning")
                    else -> false
                })
                assertFalse(result.contains("<think>"))
                samples.put(JSONObject().put("publicSource", source).put("targetLanguage", target.name).put("translation", result)
                    .put("loadAndTranslateMillis", SystemClock.elapsedRealtime() - start))
            } finally { translator.close(); translator.close() }
        }
        // Only these fixed public strings are recorded, never production subtitles.
        val report = File(app.filesDir, "validation/hy-public-fixtures.json")
        check(report.parentFile!!.mkdirs() || report.parentFile!!.isDirectory)
        report.writeText(JSONObject()
            .put("modelVersion", HyModelProfile.VERSION).put("abi", Build.SUPPORTED_ABIS.first())
            .put("package", app.packageName).put("samples", samples).toString(2))
    }
}
