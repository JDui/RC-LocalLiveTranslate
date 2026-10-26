package com.jd.rc.localtranslate.inference

import android.content.Context
import com.jd.rc.localtranslate.core.SourceLanguage
import com.jd.rc.localtranslate.model.ModelLease
import com.jd.rc.localtranslate.model.ModelBundle
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class RecognizedSpeech(val text: String, val language: SourceLanguage)
data class DetectedSpeech(val start: Int, val samples: FloatArray)

interface SpeechEngines {
    fun acceptVad(samples: FloatArray)
    fun speechDetected(): Boolean
    fun takeSegments(): List<DetectedSpeech>
    fun flushVad()
    fun resetVad()
    fun recognize(samples: FloatArray): RecognizedSpeech
    fun embedding(samples: FloatArray): FloatArray?
    suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage = SourceLanguage.ZH): String?
    fun configureTranslation(highPerformance: Boolean) {}
    fun releaseSpeaker() {}
    suspend fun releaseTranslation() {}
    suspend fun close()
}

class NativeEngines private constructor(private val lease: ModelLease, private val vad: Vad,
    private val recognizer: OfflineRecognizer, private val context: Context,
    private val translationModel: ModelBundle?) : SpeechEngines {
    private var speaker: SpeakerEmbeddingExtractor? = null
    private var translator: HyTranslator? = null
    private var translationHighPerformance = true
    override fun acceptVad(samples: FloatArray) = vad.acceptWaveform(samples)
    override fun speechDetected() = vad.isSpeechDetected()
    override fun takeSegments(): List<DetectedSpeech> = buildList {
        while (!vad.empty()) { val segment = vad.front(); add(DetectedSpeech(segment.start, segment.samples)); vad.pop() }
    }
    override fun flushVad() = vad.flush()
    override fun resetVad() = vad.reset()
    override fun recognize(samples: FloatArray): RecognizedSpeech {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16_000); recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val language = SourceLanguage.fromAsrTag(result.lang)
            return RecognizedSpeech(result.text.trim(), language)
        } finally { stream.release() }
    }
    override fun embedding(samples: FloatArray): FloatArray? {
        if (samples.size < com.jd.rc.localtranslate.core.SpeakerTracker.MINIMUM_SAMPLES) return null
        val extractor = speaker ?: SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
            model = lease.bundles.getValue("speaker").required("speaker.onnx").path, numThreads = 1)).also { speaker = it }
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, 16_000); stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally { stream.release() }
    }
    override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String? {
        if (language == target) return ""
        if (language == SourceLanguage.UNKNOWN || target == SourceLanguage.UNKNOWN) return null
        val model = translationModel ?: return null
        val client = translator ?: HyTranslator(model.required("model.gguf")).also { translator = it }
        return client.translate(text, target, translationHighPerformance)
    }
    override fun configureTranslation(highPerformance: Boolean) { translationHighPerformance = highPerformance }
    override fun releaseSpeaker() {
        val current = speaker; speaker = null
        current?.release()
    }
    override suspend fun releaseTranslation(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val current = translator; translator = null
        current?.close()
    }
    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) {
            try { releaseTranslation() } finally {
                try { releaseSpeaker() } finally {
                    try { recognizer.release() } finally { try { vad.release() } finally { lease.close() } }
                }
            }
        }
    }
    companion object {
        suspend fun create(context: Context, lease: ModelLease): NativeEngines {
            var vad: Vad? = null; var recognizer: OfflineRecognizer? = null
            try {
                return withContext(Dispatchers.IO) {
                    val bundles = lease.bundles
                    val asr = bundles.getValue("sensevoice")
                    vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                        model = bundles.getValue("silero-vad").required("silero_vad.onnx").path,
                        minSilenceDuration = .4f, minSpeechDuration = .25f, maxSpeechDuration = 5f)))
                    recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(model = asr.required("model.onnx").path, language = "auto"),
                        tokens = asr.required("tokens.txt").path, numThreads = 2, modelType = "sense_voice")))
                    NativeEngines(lease, vad, recognizer, context.applicationContext, bundles["hy-mt2"])
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { recognizer?.release() } finally { try { vad?.release() } finally { lease.close() } }
                }
                throw error
            }
        }
    }
}
