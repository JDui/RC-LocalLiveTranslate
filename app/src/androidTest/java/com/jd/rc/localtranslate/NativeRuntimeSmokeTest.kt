package com.jd.rc.localtranslate

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.VersionInfo
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.FoxletConfig
import io.github.yinvoker.foxlet.ModelRetention
import io.github.yinvoker.foxlet.TranslationConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRuntimeSmokeTest {
    @Test fun pinnedSherpaJniLoadsWithoutNetworkPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        assertEquals("1.13.8", VersionInfo.version)
        assertEquals("1.28.2", VersionInfo.onnxruntimeVersion)
    }
    @Test fun foxletCanCreateReleaseAndRecreateWithoutModelsOrDownloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(2) {
            val client = Foxlet.create(context, FoxletConfig(translation = TranslationConfig(retention = ModelRetention.AfterRequest)))
            try { assertEquals(0, client.translator.getState().loadedModelCount) } finally { client.shutdown() }
        }
    }
}
