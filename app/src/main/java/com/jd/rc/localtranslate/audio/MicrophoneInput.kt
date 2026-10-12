package com.jd.rc.localtranslate.audio

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicReference

internal data class MicrophoneRoute(val id: Int, val label: String)
internal interface MicrophoneInput : AutoCloseable {
    val sampleRate: Int
    val route: MicrophoneRoute?
    fun start(): Boolean
    fun read(buffer: ShortArray): Int
}

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
internal fun openAndroidMicrophone(): MicrophoneInput = openMicrophone { AndroidMicrophone.create(it) }

internal fun openMicrophone(create: (Int) -> MicrophoneInput?): MicrophoneInput {
    for (rate in listOf(16_000, 48_000, 44_100, 32_000, 8_000)) {
        var candidate: MicrophoneInput? = null
        var accepted = false
        try {
            candidate = create(rate) ?: continue
            if (candidate.start() && candidate.sampleRate in 8_000..96_000) {
                accepted = true; return candidate
            }
        } catch (_: IllegalArgumentException) { /* Unsupported device format. */ }
        catch (_: IllegalStateException) { /* Initialized but unable to start this format. */ }
        finally { if (!accepted) candidate?.close() }
    }
    throw IllegalStateException("No supported microphone input format")
}

private class AndroidMicrophone(private val recorder: AudioRecord) : MicrophoneInput {
    private val currentRoute = AtomicReference<MicrophoneRoute?>()
    private val listener = AudioRouting.OnRoutingChangedListener { routing -> currentRoute.set(describe(routing.routedDevice)) }
    private var listening = false
    override val sampleRate get() = recorder.sampleRate
    override val route get() = currentRoute.get()
    override fun start(): Boolean {
        if (recorder.state != AudioRecord.STATE_INITIALIZED) return false
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
        recorder.addOnRoutingChangedListener(listener, null); listening = true
        currentRoute.set(describe(recorder.routedDevice))
        return true
    }
    override fun read(buffer: ShortArray) = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
    override fun close() {
        try {
            if (listening) { recorder.removeOnRoutingChangedListener(listener); listening = false }
            runCatching { recorder.stop() }
        } finally { recorder.release() }
    }
    companion object {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun create(rate: Int): MicrophoneInput? {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return null
            return AndroidMicrophone(AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, rate * 2 / 5)))
        }
        private fun describe(device: AudioDeviceInfo?): MicrophoneRoute? = device?.let {
            MicrophoneRoute(it.id, when (it.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 麦克风"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳麦"
                else -> "系统音频输入"
            })
        }
    }
}
