package com.jd.rc.localtranslate.audio

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.jd.rc.localtranslate.MainActivity
import com.jd.rc.localtranslate.R
import com.jd.rc.localtranslate.TranslateApplication
import com.jd.rc.localtranslate.data.CaptionJson
import com.jd.rc.localtranslate.data.SavedSession
import com.jd.rc.localtranslate.inference.ConversationStore
import com.jd.rc.localtranslate.inference.NativeEngines
import com.jd.rc.localtranslate.inference.RealtimePipeline
import com.jd.rc.localtranslate.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlin.math.sqrt

enum class RecordingPhase { IDLE, STARTING, RECORDING, PAUSED, STOPPING, ERROR }
data class RecordingState(val phase: RecordingPhase = RecordingPhase.IDLE, val level: Float = 0f,
    val sampleOffset: Long = 0, val droppedFrames: Long = 0, val error: String? = null,
    val inputSampleRate: Int = 0, val inputDevice: String? = null, val recoveries: Int = 0)
data class AudioFrame(val sessionId: String, val sampleOffset: Long, val sampleRate: Int,
    val pcm: ShortArray, val monotonicTime: Long, val discontinuity: Boolean = false)

class RecordingController(private val context: Context) {
    internal val mutable = MutableStateFlow(RecordingState())
    val state = mutable.asStateFlow()
    val conversation = ConversationStore()
    internal val mutablePerformance = MutableStateFlow(RuntimePerformance())
    val performance = mutablePerformance.asStateFlow()
    internal val mutableDiagnostics = MutableStateFlow<RunDiagnostics?>(null)
    val diagnostics = mutableDiagnostics.asStateFlow()
    internal val mutableProcessSample = MutableStateFlow<ProcessSample?>(null)
    val processSample = mutableProcessSample.asStateFlow()
    internal val runtimeMutex = Mutex()
    fun command(action: String) {
        try {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == RecordingService.START) {
                if (mutable.value.phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.STOPPING)) return
                mutable.value = mutable.value.copy(phase = RecordingPhase.STARTING, error = null)
                context.startForegroundService(intent)
            } else {
                if (action == RecordingService.STOP && mutable.value.phase !in setOf(RecordingPhase.IDLE, RecordingPhase.ERROR)) {
                    mutable.update { it.copy(phase = RecordingPhase.STOPPING, level = 0f) }
                }
                context.startService(intent)
            }
        } catch (_: RuntimeException) {
            mutable.value = RecordingState(RecordingPhase.ERROR, error = "无法启动麦克风服务，请回到应用并检查权限。")
        }
    }
}

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<String>(Channel.CONFLATED)
    private var captureJob: Job? = null
    private var commandJob: Job? = null
    private var pressureMonitor: RuntimePressureMonitor? = null
    @Volatile private var diagnostics: RunDiagnostics? = null
    private val sessionPerformance = MutableStateFlow(RuntimePerformance())
    private val controller get() = (application as TranslateApplication).recording
    private var offset = 0L
    private var dropped = 0L
    private var recoveries = 0
    private var sessionId = ""
    private var pipeline: RealtimePipeline? = null
    private var pendingEngines: NativeEngines? = null
    private var runtimeLocked = false
    @Volatile private var stopRequested = false
    private val app get() = application as TranslateApplication

    override fun onCreate() {
        super.onCreate()
        pressureMonitor = RuntimePressureMonitor(this, scope, app.settings.settings) { runtime ->
            sessionPerformance.value = runtime
            controller.mutablePerformance.value = runtime
            if (runtime.policy.stop && runtimeLocked && !stopRequested) {
                controller.conversation.warn(runtime.policy.status.orEmpty())
                stopRequested = true; captureJob?.cancel(); commands.trySend(STOP)
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                diagnostics?.let { run ->
                    val sample = sampleProcess(this@RecordingService, run, sessionPerformance.value)
                    ensureActive(); run.hardware(sample); controller.mutableProcessSample.value = sample
                }
                delay(5000)
            }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "麦克风采集", NotificationManager.IMPORTANCE_LOW))
        commandJob = scope.launch { for (action in commands) when (action) {
            START -> startCapture()
            PAUSE -> pauseCapture()
            STOP -> {
                captureJob?.cancelAndJoin(); captureJob = null
                controller.mutable.update { it.copy(phase = RecordingPhase.STOPPING, level = 0f) }
                releasePipeline(save = true)
                controller.mutable.value = RecordingState()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: STOP
        if (stopRequested && action != STOP) return START_NOT_STICKY
        if (action == STOP) {
            stopRequested = true; captureJob?.cancel()
            controller.mutable.update { it.copy(phase = RecordingPhase.STOPPING, level = 0f) }
        }
        if (action == START) {
            try {
                if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(1, notification(false))
            } catch (_: RuntimeException) {
                controller.mutable.value = RecordingState(RecordingPhase.ERROR, error = "麦克风前台服务未获授权。")
                stopSelf(); return START_NOT_STICKY
            }
        }
        commands.trySend(action)
        return START_NOT_STICKY
    }
    private suspend fun startCapture() {
        if (captureJob?.isActive == true) return
        if (sessionPerformance.value.policy.stop) {
            failStart(sessionPerformance.value.policy.status ?: "系统压力过高。"); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            controller.mutable.value = RecordingState(RecordingPhase.ERROR, error = "麦克风权限未开启。")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        if (pipeline == null) {
            try {
                controller.runtimeMutex.lock(); runtimeLocked = true
                if (stopRequested) return
                if (sessionPerformance.value.policy.stop) {
                    failStart(sessionPerformance.value.policy.status ?: "系统压力过高。"); return
                }
                val lease = app.models.acquire()
                val engines = try {
                    val run = androidDiagnostics(this, RunSource.ANDROID_MICROPHONE, lease, app.models.state.value.installed)
                    diagnostics = run; controller.mutableDiagnostics.value = run; controller.mutableProcessSample.value = null
                    run.measureSuspending(InferenceStage.MODEL_INIT) { NativeEngines.create(this, lease) }
                } catch (error: Throwable) { lease.close(); throw error }
                pendingEngines = engines
                offset = 0; dropped = 0; recoveries = 0; sessionId = java.util.UUID.randomUUID().toString()
                controller.conversation.begin(sessionId, app.settings.settings.first().translationMode)
                pipeline = RealtimePipeline(sessionId, engines, controller.conversation, scope, sessionPerformance, diagnostics!!) {
                    stopRequested = true; captureJob?.cancel(); commands.trySend(STOP)
                }
                pendingEngines = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failStart(error.message ?: "模型无法加载。"); return }
            catch (_: LinkageError) { failStart("原生库无法加载，请使用 ARM64 设备。"); return }
            catch (_: OutOfMemoryError) { failStart("可用内存不足，请关闭其他应用后再试。"); return }
        }
        if (stopRequested) return
        controller.mutable.value = RecordingState(RecordingPhase.RECORDING, sampleOffset = offset,
            droppedFrames = dropped, recoveries = recoveries)
        captureJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { capture() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) {
                failCapture("麦克风权限已被撤销，请在应用权限中重新开启。")
            }
            catch (_: Exception) {
                failCapture("无法继续采集音频。麦克风可能被占用、输入失效，或设备不支持可用格式。")
            }
            catch (_: LinkageError) { failCapture("音频处理组件无法加载，麦克风采集已停止。") }
            catch (_: OutOfMemoryError) { failCapture("可用内存不足，麦克风采集已停止。") }
        }
        getSystemService(NotificationManager::class.java).notify(1, notification(false))
    }
    private suspend fun pauseCapture() {
        captureJob?.cancelAndJoin(); captureJob = null
        pipeline?.pause()
        if (controller.mutable.value.phase == RecordingPhase.RECORDING) {
            controller.mutable.update { it.copy(phase = RecordingPhase.PAUSED, level = 0f) }
            getSystemService(NotificationManager::class.java).notify(1, notification(true))
        }
    }
    private suspend fun capture() = coroutineScope {
        val frontend = CaptureFrontend(sessionId, emit = { frame -> pipeline?.offer(frame) == true }, diagnostics = diagnostics)
        val clock = CaptureSampleClock(offset)
        val recoveryBudget = MicrophoneRecoveryBudget()
        var recoveryStarted: Long? = null
        var lastUpdate = 0L
        try {
            while (isActive) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("Microphone permission revoked")
                }
                val microphone = openAndroidMicrophone()
                try {
                    ensureActive()
                    diagnostics?.prepareCapture(microphone.sampleRate)
                    val missing = recoveryStarted?.let { (SystemClock.elapsedRealtimeNanos() - it) * INFERENCE_SAMPLE_RATE / 1_000_000_000 } ?: 0
                    clock.begin(microphone.sampleRate, missing); offset = clock.outputOffset
                    recoveryStarted = null
                    var route = microphone.route
                    controller.mutable.update { it.copy(inputSampleRate = microphone.sampleRate,
                        inputDevice = route?.label, recoveries = recoveries) }
                    if (microphone.sampleRate != INFERENCE_SAMPLE_RATE) controller.conversation.warn("麦克风输入为 ${microphone.sampleRate} Hz，已在本地重采样至 16 kHz。")
                    val buffer = ShortArray(microphone.sampleRate / 50)
                    while (isActive) {
                        val nextRoute = microphone.route
                        if (nextRoute != route) {
                            if (route != null) {
                                clock.begin(microphone.sampleRate)
                                controller.conversation.warn("麦克风输入已切换，语段在切换处重新分段。")
                            }
                            route = nextRoute
                            controller.mutable.update { it.copy(inputDevice = nextRoute?.label) }
                        }
                        val count = microphone.read(buffer)
                        val now = SystemClock.elapsedRealtime()
                        if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                            check(recoveryBudget.allow(now)) { "Microphone repeatedly disconnected" }
                            recoveries++; recoveryStarted = SystemClock.elapsedRealtimeNanos()
                            controller.conversation.warn("麦克风输入失效，正在重新打开；恢复期间缺失的音频不会补写。")
                            break
                        }
                        check(count in 0..buffer.size) { "AudioRecord read failed: $count" }
                        if (count == 0) { delay(5); continue }
                        frontend.offer(clock.frame(buffer.copyOf(count), now))
                        offset = clock.outputOffset
                        if (now - lastUpdate >= 100) {
                            controller.conversation.elapsed(offset)
                            var energy = 0.0
                            for (i in 0 until count) { val sample = buffer[i] / 32768.0; energy += sample * sample }
                            controller.mutable.update { it.copy(sampleOffset = offset, droppedFrames = dropped + frontend.droppedFrames,
                                level = (sqrt(energy / count) * 5).toFloat().coerceIn(0f, 1f), recoveries = recoveries) }
                            lastUpdate = now
                        }
                    }
                } finally { microphone.close() }
            }
        } finally {
            try { frontend.finish() }
            finally {
                dropped += frontend.droppedFrames
                controller.conversation.elapsed(offset)
                controller.mutable.update { it.copy(sampleOffset = offset, droppedFrames = dropped, level = 0f,
                    inputDevice = null, recoveries = recoveries) }
            }
        }
    }
    private suspend fun failCapture(message: String) {
        controller.mutable.update { it.copy(phase = RecordingPhase.ERROR, level = 0f, error = message) }
        releasePipeline(save = true)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private suspend fun failStart(message: String) {
        releasePipeline(save = false)
        controller.mutable.value = RecordingState(RecordingPhase.ERROR, error = "无法启动本地推理：$message")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private suspend fun releasePipeline(save: Boolean) = withContext(NonCancellable) {
        val current = pipeline
        val pending = pendingEngines
        pipeline = null
        pendingEngines = null
        try {
            if (current != null) current.finish() else pending?.close()
            val state = controller.conversation.state.value
            if (save && state.sessionId == sessionId && state.captions.isNotEmpty() && app.settings.canKeepSessions()) {
                runCatching { app.sessions.save(SavedSession(state.sessionId, "本地对话", state.createdAt,
                    offset / 16_000, false, CaptionJson.encode(state.captions))) }
                    .onFailure { controller.conversation.warn("会话未能保存，请检查可用存储。") }
            }
        } finally {
            diagnostics?.end()
            if (runtimeLocked) { runtimeLocked = false; controller.runtimeMutex.unlock() }
        }
    }
    private fun notification(paused: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("RC 本地同传")
            .setContentText(if (paused) "采集已暂停" else "麦克风采集中 · 完全本地推理")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }
    override fun onDestroy() {
        runCatching { pressureMonitor?.close() }; pressureMonitor = null
        controller.mutablePerformance.update { it.copy(active = false) }
        val capture = captureJob
        commands.close(); scope.cancel()
        if (pipeline != null || pendingEngines != null || runtimeLocked) {
            if (controller.mutable.value.phase != RecordingPhase.ERROR) controller.mutable.update { it.copy(phase = RecordingPhase.STOPPING) }
            app.workScope.launch {
                capture?.join(); commandJob?.join(); releasePipeline(save = true)
                if (controller.mutable.value.phase != RecordingPhase.ERROR) controller.mutable.value = RecordingState()
            }
        } else if (controller.mutable.value.phase != RecordingPhase.ERROR) controller.mutable.value = RecordingState()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "record.start"
        const val PAUSE = "record.pause"
        const val STOP = "record.stop"
        private const val CHANNEL = "microphone"
    }
}
