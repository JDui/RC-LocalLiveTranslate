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

/** Language scores are raw model logits; absence means no usable score evidence. */
data class RecognizedSpeech(val text: String, val language: SourceLanguage,
    val languageScores: Map<SourceLanguage, Float> = emptyMap(), val tokens: List<RecognitionToken> = emptyList())
data class RecognitionToken(val text: String, val seconds: Float)
data class DetectedSpeech(val start: Int, val samples: FloatArray)

interface SpeechEngines {
    fun acceptVad(samples: FloatArray)
    fun speechDetected(): Boolean
    fun takeSegments(): List<DetectedSpeech>
    fun flushVad()
    fun resetVad()
    fun recognize(samples: FloatArray): RecognizedSpeech
    fun recognizePreferred(samples: FloatArray, language: SourceLanguage): RecognizedSpeech? = null
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
    private var vadAccepted = 0L
    override fun acceptVad(samples: FloatArray) { vad.acceptWaveform(samples); vadAccepted += samples.size }
    override fun speechDetected() = vad.isSpeechDetected()
    override fun takeSegments(): List<DetectedSpeech> = buildList {
        while (!vad.empty()) {
            val segment = vad.front()
            val size = minOf(segment.samples.size.toLong(), (vadAccepted - segment.start).coerceAtLeast(0)).toInt()
            if (size > 0) add(DetectedSpeech(segment.start, segment.samples.copyOf(size)))
            vad.pop()
        }
    }
    override fun flushVad() {
        // Native Silero retains sub-window input. Complete it, then trim padding from returned audio.
        val remainder = (vadAccepted % 512).toInt()
        if (remainder > 0) vad.acceptWaveform(FloatArray(512 - remainder))
        vad.flush()
    }
    override fun resetVad() { vad.reset(); vadAccepted = 0 }
    override fun recognize(samples: FloatArray): RecognizedSpeech = decode(samples, null)
    override fun recognizePreferred(samples: FloatArray, language: SourceLanguage): RecognizedSpeech? =
        if (language == SourceLanguage.UNKNOWN) null else decode(samples, language)
    private fun decode(samples: FloatArray, hint: SourceLanguage?): RecognizedSpeech {
        val stream = recognizer.createStream()
        try {
            if (hint != null) stream.setOption("rc_language_hint", hint.code)
            stream.acceptWaveform(samples, 16_000); recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val language = SourceLanguage.fromAsrTag(result.lang)
            val scores = SourceLanguage.supported.mapNotNull { candidate ->
                stream.getOption("rc_language_score_${candidate.code}").toFloatOrNull()
                    ?.takeIf { it.isFinite() }?.let { candidate to it }
            }.toMap().takeIf { it.size == SourceLanguage.supported.size }.orEmpty()
            val tokens = if (result.tokens.size == result.timestamps.size)
                result.tokens.indices.map { RecognitionToken(result.tokens[it], result.timestamps[it]) } else emptyList()
            return RecognizedSpeech(result.text.trim(), language, scores, tokens)
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
                        minSilenceDuration = .28f, minSpeechDuration = .25f, maxSpeechDuration = 10f)))
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
