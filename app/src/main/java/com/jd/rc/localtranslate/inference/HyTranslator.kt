package com.jd.rc.localtranslate.inference

import com.jd.rc.localtranslate.core.SourceLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal interface HyApi {
    fun create(path: ByteArray): Long
    fun generate(handle: Long, text: ByteArray, target: ByteArray): ByteArray
    fun release(handle: Long)
}
internal object HyNative : HyApi {
    init { System.loadLibrary("rc_hymt") }
    external override fun create(path: ByteArray): Long
    external override fun generate(handle: Long, text: ByteArray, target: ByteArray): ByteArray
    external override fun release(handle: Long)
}
internal class HyTranslator(private val model: File, private val api: HyApi = HyNative) {
    private val mutex = Mutex()
    private var handle = 0L
    private var closed = false
    suspend fun translate(text: String, target: SourceLanguage = SourceLanguage.ZH): String = withContext(Dispatchers.IO) {
        require(target != SourceLanguage.UNKNOWN)
        require('\u0000' !in text && "<|" !in text && "<｜" !in text && text.toByteArray(Charsets.UTF_8).size <= 4096)
        mutex.withLock {
            check(!closed)
            if (text.isBlank()) return@withLock ""
            currentCoroutineContext().ensureActive()
            if (handle == 0L) handle = api.create(model.path.toByteArray(Charsets.UTF_8)).also { check(it != 0L) }
            val result = api.generate(handle, text.toByteArray(Charsets.UTF_8), target.name.lowercase().toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8).trim()
            currentCoroutineContext().ensureActive()
            check(result.isNotBlank() && "<think>" !in result && "</think>" !in result && "<｜" !in result && "<|" !in result) {
                "Hy-MT2 returned an incomplete translation"
            }
            result
        }
    }
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                closed = true
                val current = handle; handle = 0L
                if (current != 0L) api.release(current)
            }
        }
    }
}
