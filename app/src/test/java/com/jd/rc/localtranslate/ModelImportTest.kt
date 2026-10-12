package com.jd.rc.localtranslate

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.model.*
import com.jd.rc.localtranslate.model.HyModelProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class)
class ModelImportTest {
    private val context get() = ApplicationProvider.getApplicationContext<TranslateApplication>()
    private fun archive(name: String, checksum: String, version: String = "fixture-1", id: String = "sensevoice",
        paths: List<String> = listOf("weights.bin")): Uri {
        val file = File(context.cacheDir, name)
        val manifest = JSONObject().apply {
            put("id", id); put("version", version); put("license", "test-only")
            put("sourceURL", "https://example.org/test-fixture"); put("abi", "arm64-v8a")
            put("files", JSONArray().apply { paths.forEach { path -> put(JSONObject().apply {
                put("path", path); put("size", 3); put("sha256", checksum)
            }) } })
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            paths.forEach { zip.putNextEntry(ZipEntry(it)); zip.write("abc".toByteArray()); zip.closeEntry() }
        }
        return Uri.fromFile(file)
    }
    @Test fun verifiedImportSurvivesFailedReplacementAndCanBeDeleted() = runBlocking {
        val repository = ModelRepository(context)
        repository.import(archive("valid.zip", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "good"))
        assertEquals("good", repository.state.value.installed.single().version)
        repository.import(archive("bad.zip", "0".repeat(64), "bad"))
        assertEquals("good", repository.state.value.installed.single().version)
        assertTrue(repository.state.value.message!!.contains("校验失败"))
        assertEquals("abc", File(context.filesDir, "models/sensevoice/weights.bin").readText())
        repository.delete("sensevoice")
        assertTrue(repository.state.value.installed.isEmpty())
    }
    @Test fun zipTraversalIsRejectedBeforeWritingOutsideStage() = runBlocking {
        val file = File(context.cacheDir, "traversal.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../../escaped.txt")); zip.write("bad".toByteArray()); zip.closeEntry()
        }
        val repository = ModelRepository(context)
        repository.import(Uri.fromFile(file))
        assertTrue(repository.state.value.message!!.contains("非法路径"))
        assertFalse(File(context.filesDir, "escaped.txt").exists())
    }
    @Test fun unsafePathsAreRejected() {
        listOf("../a", "/absolute", "C:/a", "folder\\a", "./a").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { safeModelFile(context.filesDir, path) }
        }
    }
    @Test fun leasePreventsDeletionAndReplacementUntilNativeUseEnds() = runBlocking {
        val checksum = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val repository = ModelRepository(context)
        repository.import(archive("asr.zip", checksum, paths = listOf("model.onnx", "tokens.txt")))
        repository.import(archive("vad.zip", checksum, id = "silero-vad", paths = listOf("silero_vad.onnx")))
        repository.import(archive("speaker.zip", checksum, id = "speaker", paths = listOf("speaker.onnx")))
        val lease = repository.acquire()
        assertTrue(repository.state.value.inUse)
        assertTrue(runCatching { repository.delete("speaker") }.isFailure)
        repository.import(archive("replacement.zip", checksum, "new", paths = listOf("model.onnx", "tokens.txt")))
        assertEquals("fixture-1", repository.state.value.installed.first { it.id == "sensevoice" }.version)
        assertTrue(repository.state.value.message!!.contains("正在使用"))
        lease.close(); lease.close()
        assertFalse(repository.state.value.inUse)
        repository.delete("speaker")
        assertFalse(repository.state.value.installed.any { it.id == "speaker" })
    }
    @Test fun verifiedBytesWithoutRuntimeFilesCannotAcquireEngineLease() = runBlocking {
        val repository = ModelRepository(context)
        repository.import(archive("incomplete.zip", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertTrue(runCatching { repository.acquire() }.isFailure)
        assertFalse(repository.state.value.inUse)
    }
    @Test fun hyProfileRejectsUnregisteredWeightsAndQuantization() {
        val manifest = JSONObject().put("version", HyModelProfile.VERSION).put("license", "Apache-2.0")
            .put("quantization", "STQ1_0").put("files", JSONArray().put(JSONObject().put("path", "model.gguf")
                .put("size", HyModelProfile.SIZE).put("sha256", HyModelProfile.SHA256)))
        HyModelProfile.validate(manifest)
        manifest.put("quantization", "Q4_K")
        assertThrows(IllegalArgumentException::class.java) { HyModelProfile.validate(manifest) }
        manifest.put("quantization", "STQ1_0")
        manifest.getJSONArray("files").getJSONObject(0).put("sha256", "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { HyModelProfile.validate(manifest) }
    }
}