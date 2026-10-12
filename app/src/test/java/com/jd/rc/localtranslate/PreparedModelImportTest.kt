package com.jd.rc.localtranslate

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.inference.resolveTranslationModel
import com.jd.rc.localtranslate.model.ModelBundle
import com.jd.rc.localtranslate.model.ModelRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Explicitly opt in with -PverifyPreparedModels=true. Missing real packages are failures, not skips.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class)
class PreparedModelImportTest {
    @Test fun allFiveRealPackagesInstallAndMandatorySpeechLeaseProtectsThem() = runBlocking {
        val root = File(requireNotNull(System.getProperty("preparedModelsRoot")))
        val speech = JSONObject(File(root, "scripts/speech-models.lock.json").readText()).getJSONArray("models")
        val translation = JSONObject(File(root, "scripts/translation-models.lock.json").readText()).getJSONArray("models")
        val expected = (0 until speech.length()).map { speech.getJSONObject(it) to "speech" } +
            (0 until translation.length()).map { translation.getJSONObject(it) to "mozilla" }
        val context = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val repository = ModelRepository(context)
        for ((model, family) in expected) {
            val id = model.getString("id")
            val version = model.getString("version")
            val archive = File(root, ".tools/models/$family/packages/$id-$version.zip")
            assertTrue("Prepare real model package first: $archive", archive.isFile)
            repository.import(Uri.fromFile(archive))
            assertEquals("模型文件已校验。", repository.state.value.message)
            val installed = repository.state.value.installed.single { it.id == id }
            assertEquals(version, installed.version)
            assertEquals(model.getString("license"), installed.license)
        }
        val lease = repository.acquire()
        try {
            assertTrue(repository.state.value.inUse)
            assertEquals(setOf("sensevoice", "silero-vad", "speaker", "en-zh", "ja-en"), lease.bundles.keys)
            for (index in 0 until speech.length()) {
                val model = speech.getJSONObject(index)
                val bundle = lease.bundles.getValue(model.getString("id"))
                val files = model.getJSONArray("files")
                for (fileIndex in 0 until files.length()) {
                    val spec = files.getJSONObject(fileIndex)
                    val name = spec.getString("name")
                    assertEquals(spec.getLong("size"), bundle.required(name).length())
                    assertEquals(spec.getString("sha256"), bundle.hashes.getValue(name))
                }
                val source = JSONObject(bundle.required("SOURCE.json").readText())
                assertEquals(model.getString("authors"), source.getString("authors"))
                assertEquals(model.getString("redistributionStatus"), source.getString("redistributionStatus"))
                assertEquals(model.getJSONObject("download").getString("digestEvidence"),
                    source.getJSONObject("download").getString("digestEvidence"))
            }
            assertTrue(lease.bundles.getValue("sensevoice").required("LICENSE.FunASR.txt").readText()
                .contains("Version: 1.1"))
            assertTrue(runCatching { repository.delete("speaker") }.isFailure)
            val asr = (0 until speech.length()).map { speech.getJSONObject(it) }.single { it.getString("id") == "sensevoice" }
            val version = asr.getString("version")
            val archive = File(root, ".tools/models/speech/packages/sensevoice-$version.zip")
            repository.import(Uri.fromFile(archive))
            assertTrue(repository.state.value.message!!.contains("正在使用"))
            assertEquals(version, repository.state.value.installed.single { it.id == "sensevoice" }.version)
        } finally {
            lease.close()
            lease.close()
        }
        assertFalse(repository.state.value.inUse)
        for ((model, _) in expected) { repository.delete(model.getString("id")) }
        assertTrue(repository.state.value.installed.isEmpty())
    }

    @Test fun mozillaPackagesImportWithUpstreamIdentityAndResolveFoxletFiles() = runBlocking {
        val root = File(requireNotNull(System.getProperty("preparedModelsRoot")))
        val lock = JSONObject(File(root, "scripts/translation-models.lock.json").readText())
        val models = lock.getJSONArray("models")
        val context = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val repository = ModelRepository(context)
        for (index in 0 until models.length()) {
            val expected = models.getJSONObject(index)
            val id = expected.getString("id")
            val version = expected.getString("version")
            val archive = File(root, ".tools/models/mozilla/packages/$id-$version.zip")
            assertTrue("Prepare real model package first: $archive", archive.isFile)
            repository.import(Uri.fromFile(archive))
            assertEquals("模型文件已校验。", repository.state.value.message)
            val installed = repository.state.value.installed.single { it.id == id }
            assertEquals(version, installed.version)
            assertEquals("MPL-2.0", installed.license)
            assertEquals(lock.getString("catalogSource"), installed.source)
            val directory = File(context.filesDir, "models/$id")
            val manifest = JSONObject(File(directory, "manifest.json").readText())
            val specs = manifest.getJSONArray("files")
            val files = linkedMapOf<String, File>()
            val hashes = linkedMapOf<String, String>()
            for (fileIndex in 0 until specs.length()) {
                val spec = specs.getJSONObject(fileIndex)
                val name = spec.getString("path")
                files[name] = File(directory, name)
                hashes[name] = spec.getString("sha256")
            }
            val upstream = expected.getJSONArray("files")
            val upstreamNames = (0 until upstream.length()).map { fileIndex ->
                val spec = upstream.getJSONObject(fileIndex)
                val name = spec.getString("name")
                assertEquals(spec.getLong("size"), files.getValue(name).length())
                assertEquals(spec.getString("sha256"), hashes.getValue(name))
                name
            }.toSet()
            val notice = JSONObject(files.getValue("SOURCE.json").readText())
            assertEquals(id, notice.getString("modelId"))
            assertEquals(version, notice.getString("version"))
            assertEquals(lock.getString("licenseEvidence"), notice.getString("licenseEvidence"))
            assertTrue(files.getValue("LICENSE.mozilla.txt").readText().startsWith("Mozilla Public License Version 2.0"))
            val resolved = resolveTranslationModel(ModelBundle(id, directory, files, hashes),
                expected.getString("source"), expected.getString("target"))
            assertEquals(upstreamNames, resolved.files.expectedSha256.keys)
            assertEquals(id == "ja-en", resolved.files.srcVocab == resolved.files.trgVocab)
            repository.delete(id)
            assertFalse(repository.state.value.installed.any { it.id == id })
        }
    }
}
