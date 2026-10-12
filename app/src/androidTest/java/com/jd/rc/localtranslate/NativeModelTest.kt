package com.jd.rc.localtranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import com.jd.rc.localtranslate.audio.CaptureFrontend
import com.jd.rc.localtranslate.audio.CaptureSampleClock
import com.jd.rc.localtranslate.core.SourceLanguage
import com.jd.rc.localtranslate.core.TranslationStatus
import com.jd.rc.localtranslate.inference.ConversationStore
import com.jd.rc.localtranslate.inference.NativeEngines
import com.jd.rc.localtranslate.inference.RealtimePipeline
import com.jd.rc.localtranslate.runtime.*
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Requires audited models imported into the target app and a public test WAV; never commits user audio. */
@RunWith(AndroidJUnit4::class)
class NativeModelTest {
    @Test fun localEnglishAudioPassesProductionVadAsrAndTranslation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as TranslateApplication
        val input = InstrumentationRegistry.getArguments().getString("audioFile") ?: error("Provide -e audioFile <target-app-private WAV path>")
        val file = File(input).canonicalFile
        require(file.path.startsWith(app.filesDir.canonicalPath + File.separator)) { "Test WAV must be staged in target app private storage" }
        require(file.length() in 1..32L * 1024 * 1024) { "Test WAV must not exceed 32 MiB" }
        val audio = WaveReader.readWave(file.path)
        assertTrue("WAV input rate must be 8-96 kHz", audio.sampleRate in 8_000..96_000)
        assertTrue(audio.samples.isNotEmpty())
        assertTrue("Limit test WAV to 90 seconds", audio.samples.size <= audio.sampleRate * 90)
        app.models.refresh()
        assertTrue("Install audited en-zh package for this test", app.models.state.value.installed.any { it.id == "en-zh" })
        val lease = app.models.acquire()
        val diagnostics = try { androidDiagnostics(app, RunSource.ANDROID_TEST_FIXTURE, lease, app.models.state.value.installed) }
        catch (error: Throwable) { lease.close(); throw error }
        val engines = diagnostics.measureSuspending(InferenceStage.MODEL_INIT) { NativeEngines.create(app, lease) }
        val store = ConversationStore().apply { begin("device-model-test") }
        val fatal = AtomicBoolean(false)
        val pipeline = RealtimePipeline("device-model-test", engines, store, this, diagnostics = diagnostics) { fatal.set(true) }
        diagnostics.prepareCapture(audio.sampleRate)
        val frontend = CaptureFrontend("device-model-test", emit = pipeline::offer, diagnostics = diagnostics)
        val hardware = launch(Dispatchers.IO) {
            while (isActive) {
                diagnostics.hardware(sampleProcess(app, diagnostics, RuntimePerformance()))
                delay(5000)
            }
        }
        val clock = CaptureSampleClock(0).apply { begin(audio.sampleRate) }
        try {
            withTimeout(120_000) {
                var start = 0
                while (start < audio.samples.size) {
                    ensureActive(); check(!fatal.get()) { "Production inference failed" }
                    val count = minOf(audio.sampleRate / 50, audio.samples.size - start)
                    val pcm = ShortArray(count) { (audio.samples[start + it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                    assertTrue("Raw capture queue overloaded", frontend.offer(clock.frame(pcm, SystemClock.elapsedRealtime())))
                    start += count
                    delay(count * 1000L / audio.sampleRate)
                }
                frontend.finish()
                pipeline.finish()
            }
        } finally { withContext(NonCancellable) {
            hardware.cancelAndJoin()
            try { frontend.finish() } finally {
                try { pipeline.finish() } finally {
                    withContext(Dispatchers.IO) {
                        val directory = File(instrumentation.context.filesDir, "diagnostics").apply { check(mkdirs() || isDirectory) }
                        val report = diagnostics.report()
                        File(directory, "native-model.json").writeText(DiagnosticExport.json(report), Charsets.UTF_8)
                        File(directory, "native-model.csv").writeText(DiagnosticExport.csv(report), Charsets.UTF_8)
                    }
                }
            }
        } }
        assertEquals("DSP or inference audio queue dropped frames", 0L, frontend.droppedFrames)
        val captions = store.state.value.captions
        assertTrue("Expected English speech", captions.any { it.sourceLanguage == SourceLanguage.EN && it.sourceText.isNotBlank() })
        assertTrue("Expected locally generated Chinese translation", captions.any {
            it.translationStatus == TranslationStatus.COMPLETE && it.targetText.any { character -> character in '\u4e00'..'\u9fff' }
        })
        assertEquals(0L, store.state.value.droppedSegments)
        assertFalse(app.models.state.value.inUse)
    }
}
