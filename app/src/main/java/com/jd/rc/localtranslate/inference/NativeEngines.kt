package com.jd.rc.localtranslate.inference

import android.content.Context
import com.jd.rc.localtranslate.core.SourceLanguage
import com.jd.rc.localtranslate.model.ModelLease
import com.jd.rc.localtranslate.model.ModelBundle
import com.k2fsa.sherpa.onnx.*
import io.github.yinvoker.foxlet.ExternalModel
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.FoxletConfig
import io.github.yinvoker.foxlet.LanguagePair
import io.github.yinvoker.foxlet.ModelFiles
import io.github.yinvoker.foxlet.ModelRetention
import io.github.yinvoker.foxlet.Threading
import io.github.yinvoker.foxlet.TranslationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class RecognizedSpeech(val text: String, val language: SourceLanguage)
data class DetectedSpeech(val start: Int, val samples: FloatArray)

internal fun resolveTranslationModel(bundle: ModelBundle, source: String, target: String): ExternalModel {
    val files = ModelFiles.fromDirectory(bundle.directory)
    val modelAssets = listOf(files.model, files.srcVocab, files.trgVocab, files.shortlist).distinct()
    val hashes = modelAssets.associate { it.name to bundle.hashes.getValue(it.name) }
    return ExternalModel(LanguagePair(source, target), files.copy(expectedSha256 = hashes))
}

interface SpeechEngines {
    fun acceptVad(samples: FloatArray)
    fun speechDetected(): Boolean
    fun takeSegments(): List<DetectedSpeech>
    fun flushVad()
    fun resetVad()
    fun recognize(samples: FloatArray): RecognizedSpeech
    fun embedding(samples: FloatArray): FloatArray?
    suspend fun translate(text: String, language: SourceLanguage): String?
    fun releaseSpeaker() {}
    suspend fun releaseTranslation() {}
    suspend fun close()
}

class NativeEngines private constructor(private val lease: ModelLease, private val vad: Vad,
    private val recognizer: OfflineRecognizer, private val context: Context,
    private val english: ExternalModel?, private val japanese: ExternalModel?) : SpeechEngines {
    private var speaker: SpeakerEmbeddingExtractor? = null
    private var foxlet: Foxlet? = null
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
            val language = when (result.lang.lowercase().removePrefix("<|").removeSuffix("|>")) {
                "zh" -> SourceLanguage.ZH; "en" -> SourceLanguage.EN; "ja" -> SourceLanguage.JA; else -> SourceLanguage.UNKNOWN
            }
            return RecognizedSpeech(result.text.trim(), language)
        } finally { stream.release() }
    }
    override fun embedding(samples: FloatArray): FloatArray? {
        if (samples.size < 19_200) return null
        val extractor = speaker ?: SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
            model = lease.bundles.getValue("speaker").required("speaker.onnx").path, numThreads = 1)).also { speaker = it }
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, 16_000); stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally { stream.release() }
    }
    override suspend fun translate(text: String, language: SourceLanguage): String? {
        val en = english ?: return null
        if (language == SourceLanguage.UNKNOWN) return null
        if (language == SourceLanguage.ZH) return ""
        if (language == SourceLanguage.JA && japanese == null) return null
        val client = foxlet ?: withContext(NonCancellable + Dispatchers.IO) {
            Foxlet.create(context, FoxletConfig(translation = TranslationConfig(
                threading = Threading.Fixed(1), retention = ModelRetention.AfterRequest))).also { foxlet = it }
        }
        currentCoroutineContext().ensureActive()
        return when (language) {
            SourceLanguage.EN -> client.translator.translate(text, en)
            SourceLanguage.JA -> japanese?.let { client.translator.translatePivot(text, it, en) }
            SourceLanguage.ZH -> ""
            SourceLanguage.UNKNOWN -> null
        }
    }
    override fun releaseSpeaker() {
        val current = speaker; speaker = null
        current?.release()
    }
    override suspend fun releaseTranslation(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val current = foxlet; foxlet = null
        current?.shutdown()
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
                    fun translation(id: String, source: String, target: String) = bundles[id]?.let {
                        resolveTranslationModel(it, source, target)
                    }
                    val english = translation("en-zh", "en", "zh-Hans")
                    val japanese = translation("ja-en", "ja", "en")
                    NativeEngines(lease, vad, recognizer, context.applicationContext, english, japanese)
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
