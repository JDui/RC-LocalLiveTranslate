package com.jd.rc.localtranslate

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jd.rc.localtranslate.audio.RecordingPhase
import com.jd.rc.localtranslate.audio.RecordingService
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.data.*
import com.jd.rc.localtranslate.runtime.RunDiagnostics
import com.jd.rc.localtranslate.runtime.DiagnosticExport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class LiveState(val sessionId: String = "", val captions: List<SubtitleItem> = emptyList(),
    val demo: Boolean = false, val running: Boolean = false, val paused: Boolean = false,
    val elapsedSeconds: Long = 0, val createdAt: Long = 0)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TranslateApplication
    val settings = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val sessions = app.sessions.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val models = app.models.state
    val recording = app.recording.state
    val performance = app.recording.performance
    val diagnostics = app.recording.diagnostics
    val processSample = app.recording.processSample
    private var pendingDiagnosticExport: RunDiagnostics? = null
    private val mutableLive = MutableStateFlow(LiveState())
    val conversation = app.recording.conversation.state
    val live = combine(mutableLive, conversation, recording) { demo, speech, audio ->
        if (demo.demo) demo else LiveState(speech.sessionId, speech.captions, running = audio.phase == RecordingPhase.RECORDING,
            paused = audio.phase == RecordingPhase.PAUSED, elapsedSeconds = maxOf(audio.sampleOffset, speech.durationSamples) / 16_000, createdAt = speech.createdAt)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveState())
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private var demoJob: Job? = null
    private var reducer = SubtitleReducer("")
    private var demoTitle = ""
    private var demoMode = TranslationMode.TO_ZH
    private var demoStep = 0
    init { viewModelScope.launch { app.models.refresh() } }

    fun setDemo(enabled: Boolean) {
        if (mutableLive.value.running || recording.value.phase in listOf(RecordingPhase.RECORDING, RecordingPhase.PAUSED, RecordingPhase.STARTING, RecordingPhase.STOPPING)) return
        mutableLive.value = LiveState(demo = enabled)
    }
    fun startDemo() {
        if (!mutableLive.value.demo || mutableLive.value.running || recording.value.phase == RecordingPhase.RECORDING) return
        if (!mutableLive.value.paused) {
            demoMode = settings.value.translationMode
            val id = UUID.randomUUID().toString(); reducer = SubtitleReducer(id, demoMode); demoStep = 0
            val createdAt = System.currentTimeMillis()
            demoTitle = SessionTitle.create(createdAt, settings.value.sessionCity)
            mutableLive.value = LiveState(id, demo = true, createdAt = createdAt)
        }
        mutableLive.update { it.copy(running = true, paused = false) }
        demoJob = viewModelScope.launch {
            while (demoStep < 28) {
                delay(650)
                val row = demoStep / 4
                val phase = demoStep % 4
                val sample = demoSamples[row % demoSamples.size]
                val state = mutableLive.value
                val segment = "demo-$row"
                val start = row * 2_600L * 16
                val event = when (phase) {
                    0 -> SubtitleEvent.Transcript(state.sessionId, segment, 0, start, start + 10_400,
                        sample.language, sample.source.take(sample.source.length / 2), CaptionStage.PARTIAL)
                    1 -> SubtitleEvent.Transcript(state.sessionId, segment, 1, start, start + 41_600,
                        sample.language, sample.source, CaptionStage.FINAL)
                    2 -> SubtitleEvent.Speaker(state.sessionId, segment, 1, sample.speaker)
                    else -> SubtitleEvent.Translation(state.sessionId, segment, 1, sample.translated(demoMode.target(sample.language)))
                }
                mutableLive.update { it.copy(captions = reducer.apply(event), elapsedSeconds = (demoStep + 1) * 650L / 1000) }
                demoStep++
            }
            finishDemo()
        }
    }
    fun pauseDemo() {
        demoJob?.cancel(); mutableLive.update { it.copy(running = false, paused = true) }
    }
    fun stopDemo() { demoJob?.cancel(); finishDemo() }
    private fun finishDemo() {
        val state = mutableLive.value
        val title = demoTitle
        mutableLive.update { it.copy(running = false, paused = false) }
        if (state.captions.isNotEmpty()) viewModelScope.launch {
            if (!app.settings.canKeepSessions()) return@launch
            runCatching { app.sessions.save(SavedSession(state.sessionId, title, state.createdAt,
                state.elapsedSeconds, true, CaptionJson.encode(state.captions))) }.onFailure { mutableNotice.value = "无法保存会话。" }
        }
    }
    fun record(action: String) { if (!mutableLive.value.demo) app.recording.command(action) }
    fun setSettings(change: SettingsChange) { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { app.settings.update(change) }.onFailure { mutableNotice.value = "无法保存设置。" }
    } }
    fun importModel(uri: Uri) { viewModelScope.launch { app.models.import(uri) } }
    fun deleteModel(id: String) { viewModelScope.launch {
        runCatching { app.models.delete(id) }.onFailure { mutableNotice.value = "模型删除失败。" }
    } }
    fun deleteSession(id: String) { viewModelScope.launch {
        runCatching { app.sessions.delete(id) }.onFailure { mutableNotice.value = "会话删除失败。" }
    } }
    fun renameSession(id: String, title: String) { viewModelScope.launch {
        runCatching { app.sessions.rename(id, title.trim().take(80).ifBlank { "未命名会话" }) }
            .onFailure { mutableNotice.value = "会话重命名失败。" }
    } }
    fun export(uri: Uri, session: SavedSession, format: String) { viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val items = CaptionJson.decode(session.captionsJson)
            val text = when (format) { "srt" -> SessionExport.srt(items); "json" -> session.captionsJson; else -> SessionExport.text(items) }
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: error("output unavailable")
        }.onSuccess { mutableNotice.value = "会话已导出。" }.onFailure { mutableNotice.value = "导出失败，请检查目标文件。" }
    } }
    fun acknowledgeNotice() { mutableNotice.value = null }
    fun prepareDiagnosticExport(): Boolean {
        pendingDiagnosticExport = diagnostics.value
        if (pendingDiagnosticExport == null) mutableNotice.value = "暂无运行诊断。"
        return pendingDiagnosticExport != null
    }
    fun exportDiagnostics(uri: Uri, format: String) {
        val run = pendingDiagnosticExport; pendingDiagnosticExport = null
        if (run == null) { mutableNotice.value = "运行诊断已不可用。"; return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val report = run.report()
                val text = if (format == "csv") DiagnosticExport.csv(report) else DiagnosticExport.json(report)
                app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: error("output unavailable")
            }.onSuccess { mutableNotice.value = "运行诊断已导出。" }
                .onFailure { mutableNotice.value = "诊断导出失败，请检查目标文件。" }
        }
    }
    private data class DemoSample(val language: SourceLanguage, val source: String, val target: String, val speaker: String,
        val english: String = source, val japanese: String = source) {
        fun translated(language: SourceLanguage?) = when (language) {
            SourceLanguage.ZH -> target
            SourceLanguage.EN -> english
            SourceLanguage.JA -> japanese
            else -> ""
        }
    }
    private val demoSamples = listOf(
        DemoSample(SourceLanguage.EN, "Shall we take a walk by the river after the meeting?", "会议结束后，我们去河边走走好吗？", "A", japanese = "会議の後、川沿いを散歩しませんか？"),
        DemoSample(SourceLanguage.JA, "いいですね。夕方は少し涼しくなりそうです。", "好呀，傍晚应该会凉快一些。", "B", english = "Sounds good. It should be cooler this evening."),
        DemoSample(SourceLanguage.ZH, "那我们六点在楼下见，顺便一起吃晚饭。", "", "A", "Then let us meet downstairs at six and have dinner together.", "それでは六時に下で会って、一緒に夕食を食べましょう。"),
        DemoSample(SourceLanguage.EN, "Perfect. I know a quiet place just around the corner.", "太好了。拐角附近有一家安静的小店。", "B", japanese = "いいですね。角を曲がった所に静かなお店があります。"),
        DemoSample(SourceLanguage.JA, "予約は必要ですか？", "需要预约吗？", "A", english = "Do we need a reservation?"),
        DemoSample(SourceLanguage.ZH, "我先打电话问一下，稍后告诉你。", "", "B", "I will call and ask, then let you know.", "電話で確認して、後でお知らせします。"),
        DemoSample(SourceLanguage.EN, "Thank you. See you downstairs at six.", "谢谢，六点楼下见。", "A", japanese = "ありがとう。六時に下で会いましょう。"),
    )
}
