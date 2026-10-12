package com.jd.rc.localtranslate

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.model.ModelRepository
import com.jd.rc.localtranslate.model.HyModelProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class)
class PreparedModelImportTest {
    @Test fun realSpeechAndHyPackagesInstallAndLeaseProtectsWeights() = runBlocking {
        val root = File(requireNotNull(System.getProperty("preparedModelsRoot")))
        val context = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val repository = ModelRepository(context)
        val speech = JSONObject(File(root, "scripts/speech-models.lock.json").readText()).getJSONArray("models")
        for (index in 0 until speech.length()) {
            val model = speech.getJSONObject(index)
            val archive = File(root, ".tools/models/speech/packages/${model.getString("id")}-${model.getString("version")}.zip")
            assertTrue(archive.isFile)
            repository.import(Uri.fromFile(archive))
            assertEquals("模型文件已校验。", repository.state.value.message)
        }
        val archive = File(root, ".tools/models/hymt/packages/hy-mt2-${HyModelProfile.VERSION}.zip")
        assertTrue(archive.isFile)
        repository.import(Uri.fromFile(archive))
        assertEquals("模型文件已校验。", repository.state.value.message)
        val lease = repository.acquire()
        try {
            assertEquals(setOf("sensevoice", "silero-vad", "speaker", "hy-mt2"), lease.bundles.keys)
            assertEquals(HyModelProfile.SIZE, lease.bundles.getValue("hy-mt2").required("model.gguf").length())
            assertEquals(HyModelProfile.SHA256, lease.bundles.getValue("hy-mt2").hashes.getValue("model.gguf"))
            assertTrue(runCatching { repository.delete("hy-mt2") }.isFailure)
            repository.import(Uri.fromFile(archive))
            assertTrue(repository.state.value.message!!.contains("正在使用"))
        } finally { lease.close(); lease.close() }
        for (id in listOf("sensevoice", "silero-vad", "speaker", "hy-mt2")) repository.delete(id)
        assertTrue(repository.state.value.installed.isEmpty())
    }
}
