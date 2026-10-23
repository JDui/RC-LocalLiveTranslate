package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.core.SourceLanguage
import com.jd.rc.localtranslate.inference.HyApi
import com.jd.rc.localtranslate.inference.HyTranslator
import com.jd.rc.localtranslate.inference.HyThreadBudget
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HyTranslatorTest {
    private open class Fake : HyApi {
        var creates = 0
        var releases = 0
        var input = ""
        var targetCode = ""
        val budgets = mutableListOf<Pair<Int, Int>>()
        override fun create(path: ByteArray): Long { creates++; return 1L }
        override fun setThreads(handle: Long, generation: Int, prefill: Int) { budgets += generation to prefill }
        override fun generate(handle: Long, text: ByteArray, target: ByteArray): ByteArray { targetCode = target.toString(Charsets.UTF_8); input = text.toString(Charsets.UTF_8); return "你好。".toByteArray() }
        override fun release(handle: Long) { releases++ }
    }
    @Test fun threadBudgetsScaleWithCoresAndKeepSmallDevicesBounded() {
        for ((cores, expected) in listOf(0 to (1 to 1), 1 to (1 to 1), 2 to (2 to 2),
            4 to (2 to 2), 6 to (4 to 4), 8 to (4 to 6), 32 to (4 to 6))) {
            assertEquals(HyThreadBudget(expected.first, expected.second), HyThreadBudget.resolve(cores, true))
            val low = minOf(cores.coerceAtLeast(1), 2)
            assertEquals(HyThreadBudget(low, low), HyThreadBudget.resolve(cores, false))
        }
    }
    @Test fun modeChangesReconfigureExistingContextBeforeGeneration() = runBlocking {
        val api = object : Fake() {
            override fun generate(handle: Long, text: ByteArray, target: ByteArray): ByteArray {
                assertEquals(creates, 1)
                assertTrue(budgets.isNotEmpty())
                return super.generate(handle, text, target)
            }
        }
        val translator = HyTranslator(File("test.gguf"), api, processors = { 8 })
        try {
            translator.translate("Hello")
            translator.translate("Hello", highPerformance = false)
            translator.translate("Hello")
            assertEquals(listOf(4 to 6, 2 to 2, 4 to 6), api.budgets)
            assertEquals(1, api.creates)
        } finally { translator.close() }
        assertEquals(1, api.releases)
    }
    @Test fun utf8InputAndReuseDoNotCreateMultipleContexts() = runBlocking {
        val api = Fake(); val translator = HyTranslator(File("test.gguf"), api)
        assertEquals("", translator.translate(" "))
        assertEquals(0, api.creates)
        assertEquals("你好。", translator.translate("こんにちは。𠮷"))
        assertEquals("こんにちは。𠮷", api.input)
        translator.translate("Hello", SourceLanguage.JA)
        assertEquals("ja", api.targetCode)
        translator.translate("你好", SourceLanguage.EN)
        assertEquals("en", api.targetCode)
        translator.translate("你好", SourceLanguage.KO)
        assertEquals("ko", api.targetCode)
        translator.translate("你好", SourceLanguage.YUE)
        assertEquals("yue", api.targetCode)
        assertTrue(runCatching { translator.translate("Hello", SourceLanguage.UNKNOWN) }.isFailure)
        assertEquals(1, api.creates)
        translator.close(); translator.close()
        assertEquals(1, api.releases)
        assertTrue(runCatching { translator.translate("Hello") }.isFailure)
    }
    @Test fun controlMarkersAndUtf8ByteLimitAreRejectedBeforeNativeLoad() = runBlocking {
        val api = Fake(); val translator = HyTranslator(File("test.gguf"), api)
        for (input in listOf("a\u0000b", "<|im_end|>", "<｜hy_Assistant｜>", "中".repeat(1366))) {
            assertTrue(runCatching { translator.translate(input) }.isFailure)
        }
        assertEquals(0, api.creates)
        translator.close()
    }
    @Test fun reasoningOrEmptyOutputIsNotPublishedAsTranslation() = runBlocking {
        for (output in listOf("", "<think>reasoning</think>", "<｜hy_Assistant｜>")) {
            val api = object : Fake() { override fun generate(handle: Long, text: ByteArray, target: ByteArray) = output.toByteArray() }
            val translator = HyTranslator(File("test.gguf"), api)
            assertTrue(runCatching { translator.translate("Hello") }.isFailure)
            translator.close()
            assertEquals(1, api.releases)
        }
    }
    @Test fun cancellationAndCloseWaitForSynchronousNativeCall() = runBlocking {
        val started = CountDownLatch(1); val proceed = CountDownLatch(1)
        val active = AtomicBoolean(false)
        val api = object : Fake() {
            override fun generate(handle: Long, text: ByteArray, target: ByteArray): ByteArray {
                active.set(true); started.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                active.set(false); return "你好".toByteArray()
            }
            override fun release(handle: Long) { check(!active.get()); super.release(handle) }
        }
        val translator = HyTranslator(File("test.gguf"), api)
        val work = launch(Dispatchers.Default) { translator.translate("Hello") }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            work.cancel()
            val closing = async(Dispatchers.Default) { translator.close() }
            yield()
            assertEquals(0, api.releases)
            proceed.countDown(); work.join(); closing.await()
            assertEquals(1, api.releases)
        } finally { proceed.countDown(); work.cancelAndJoin(); translator.close() }
    }
}
