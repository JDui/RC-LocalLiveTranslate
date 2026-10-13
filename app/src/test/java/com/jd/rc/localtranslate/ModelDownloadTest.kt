package com.jd.rc.localtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.model.ModelDownloads
import com.jd.rc.localtranslate.model.copyVerified
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelDownloadTest {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun target(): File = File.createTempFile("download-test-", ".bin").apply { deleteOnExit() }

    @Test fun catalogUsesFixedIdentityAndPreservesLicenseTemplates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = ModelDownloads(context).catalog()
        assertEquals(setOf("sensevoice", "silero-vad", "speaker", "hy-mt2"), catalog.map { it.id }.toSet())
        for (model in catalog) {
            val manifest = JSONObject(context.assets.open("model-downloads/${model.id}/manifest.json").bufferedReader().use { it.readText() })
            assertEquals(model.id, manifest.getString("id"))
            val specs = manifest.getJSONArray("files")
            for (i in 0 until specs.length()) {
                val spec = specs.getJSONObject(i)
                val remote = model.files.find { it.path == spec.getString("path") }
                if (remote != null) {
                    assertTrue(remote.url.startsWith("https://"))
                    assertEquals(spec.getLong("size"), remote.size)
                    assertEquals(spec.getString("sha256"), remote.sha256)
                } else {
                    val bytes = context.assets.open("model-downloads/${model.id}/${spec.getString("path")}").use { it.readBytes() }
                    assertEquals(spec.getLong("size"), bytes.size.toLong())
                    assertEquals(spec.getString("sha256"), digest(bytes))
                }
            }
        }
        assertTrue(catalog.first { it.id == "sensevoice" }.files.all { "2365baeacb507f821a0c8120fcee3d484dba7a07" in it.url })
        assertTrue(catalog.first { it.id == "hy-mt2" }.files.single().url.contains("9df5c824a00a744fb0512a29c640466f4d97dfb0"))
    }

    @Test fun verifiedTransferReportsBytesAndRetainsExactFile() = runBlocking {
        val bytes = ByteArray(140_000) { (it % 251).toByte() }; val file = target(); var progress = 0L
        try {
            copyVerified(ByteArrayInputStream(bytes), file, bytes.size.toLong(), digest(bytes)) { progress += it }
            assertEquals(bytes.size.toLong(), progress); assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun corruptOrTruncatedOrOversizedTransferRemovesPartialFile() = runBlocking {
        val bytes = "registered model".toByteArray()
        for ((input, expectedSize, expectedHash) in listOf(
            Triple(bytes, bytes.size.toLong(), "0".repeat(64)),
            Triple(bytes.copyOf(3), bytes.size.toLong(), digest(bytes)),
            Triple(bytes, 3L, digest(bytes)))) {
            val file = target()
            try {
                var rejected = false
                try { copyVerified(ByteArrayInputStream(input), file, expectedSize, expectedHash) {} }
                catch (_: IllegalArgumentException) { rejected = true }
                assertTrue(rejected); assertFalse(file.exists())
            } finally { file.delete() }
        }
    }

    @Test fun cancellationRemovesPartialFileBeforeNextChunk() = runBlocking {
        val bytes = ByteArray(200_000); val file = target(); var cancelled = false
        val job = launch {
            try { copyVerified(ByteArrayInputStream(bytes), file, bytes.size.toLong(), digest(bytes)) { cancel() } }
            catch (_: CancellationException) { cancelled = true }
        }
        job.join()
        assertTrue(cancelled); assertFalse(file.exists())
    }
}
