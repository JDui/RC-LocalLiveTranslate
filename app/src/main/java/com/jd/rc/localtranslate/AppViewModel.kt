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

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TranslateApplication
    val settings = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    private val mutableSessionPage = MutableStateFlow(0)
    val sessionPage = mutableSessionPage.asStateFlow()
    val sessionCount = app.sessions.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val sessions = mutableSessionPage.flatMapLatest { app.sessions.observeSummaries(SESSION_PAGE_SIZE, it * SESSION_PAGE_SIZE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun changeSessionPage(page: Int) {
        mutableSessionPage.value = page.coerceIn(0, ((sessionCount.value - 1).coerceAtLeast(0) / SESSION_PAGE_SIZE))
    }
    fun loadSessionPage(session: SavedSession, page: Int) = app.sessionRecords.observePage(session.id, page)
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
    private var modelDownloadJob: Job? = null
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
            while (demoStep < demoSamples.size * 4) {
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
        val acceptedEpoch = app.settings.retentionEpoch()
        if (state.captions.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) {
            if (!app.settings.canKeepSessions()) return@launch
            runCatching { app.sessionRecords.saveDemo(SavedSession(state.sessionId, title, state.createdAt,
                state.elapsedSeconds, true, "[]"), state.captions, app.settings, acceptedEpoch) }.onFailure { mutableNotice.value = "无法保存会话。" }
        }
    }
    fun record(action: String) { if (!mutableLive.value.demo) app.recording.command(action) }
    fun setSettings(change: SettingsChange) { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { app.settings.update(change) }.onFailure { mutableNotice.value = "无法保存设置。" }
    } }
    fun importModel(uri: Uri) { viewModelScope.launch { app.models.import(uri) } }
    fun downloadModels() {
        if (modelDownloadJob?.isActive == true || models.value.busy || models.value.inUse) return
        modelDownloadJob = viewModelScope.launch { app.models.downloadMissing() }
    }
    fun cancelModelDownload() { modelDownloadJob?.cancel() }
    fun deleteModel(id: String) { viewModelScope.launch {
        runCatching { app.models.delete(id) }.onFailure { mutableNotice.value = "模型删除失败。" }
    } }
    fun deleteSession(id: String) { viewModelScope.launch {
        if (id == conversation.value.sessionId && recording.value.phase in setOf(RecordingPhase.RECORDING, RecordingPhase.PAUSED,
                RecordingPhase.STARTING, RecordingPhase.STOPPING)) {
            mutableNotice.value = "请先停止当前会话，再删除记录。"; return@launch
        }
        runCatching { app.sessions.delete(id) }.onFailure { mutableNotice.value = "会话删除失败。" }
    } }
    fun renameSession(id: String, title: String) { viewModelScope.launch {
        runCatching { app.sessions.rename(id, title.trim().take(80).ifBlank { "未命名会话" }) }
            .onFailure { mutableNotice.value = "会话重命名失败。" }
    } }
    fun export(uri: Uri, sessionId: String, format: String) { viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                app.sessionRecords.export(sessionId, format, writer)
            } ?: error("output unavailable")
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
        val english: String = source, val japanese: String = source, val korean: String, val cantonese: String) {
        fun translated(language: SourceLanguage?) = when (language) {
            SourceLanguage.ZH -> target
            SourceLanguage.EN -> english
            SourceLanguage.JA -> japanese
            SourceLanguage.KO -> korean
            SourceLanguage.YUE -> cantonese
            else -> ""
        }
    }
    private val demoSamples = listOf(
        DemoSample(SourceLanguage.EN, "Shall we take a walk by the river after the meeting?", "会议结束后，我们去河边走走好吗？", "A", japanese = "会議の後、川沿いを散歩しませんか？",
            korean = "회의가 끝나면 강가를 산책할까요?", cantonese = "開完會之後，我哋去河邊行吓好唔好？"),
        DemoSample(SourceLanguage.JA, "いいですね。夕方は少し涼しくなりそうです。", "好呀，傍晚应该会凉快一些。", "B", english = "Sounds good. It should be cooler this evening.",
            korean = "좋아요. 저녁에는 좀 시원해질 것 같아요.", cantonese = "好呀，傍晚應該會涼啲。"),
        DemoSample(SourceLanguage.ZH, "那我们六点在楼下见，顺便一起吃晚饭。", "", "A", "Then let us meet downstairs at six and have dinner together.", "それでは六時に下で会って、一緒に夕食を食べましょう。",
            korean = "그럼 여섯 시에 아래층에서 만나서 같이 저녁을 먹어요.", cantonese = "咁我哋六點喺樓下見，順便一齊食晚飯。"),
        DemoSample(SourceLanguage.KO, "좋아요. 모퉁이에 조용한 식당이 있어요.", "好呀，拐角处有一家安静的餐厅。", "B", "Sounds good. There is a quiet restaurant around the corner.", "いいですね。角を曲がった所に静かなレストランがあります。",
            korean = "좋아요. 모퉁이에 조용한 식당이 있어요.", cantonese = "好呀，轉角有間好靜嘅餐廳。"),
        DemoSample(SourceLanguage.YUE, "使唔使訂位呀？", "需要预约吗？", "A", "Do we need a reservation?", "予約は必要ですか？",
            korean = "예약이 필요한가요?", cantonese = "使唔使訂位呀？"),
        DemoSample(SourceLanguage.ZH, "我先打电话问一下，稍后告诉你。", "", "B", "I will call and ask, then let you know.", "電話で確認して、後でお知らせします。",
            korean = "먼저 전화로 물어보고 나중에 알려 드릴게요.", cantonese = "我打個電話問吓先，遲啲話你知。"),
        DemoSample(SourceLanguage.EN, "Thank you. See you downstairs at six.", "谢谢，六点楼下见。", "A", japanese = "ありがとう。六時に下で会いましょう。",
            korean = "고마워요. 여섯 시에 아래층에서 만나요.", cantonese = "唔該晒，六點樓下見。"),
    )
}
