package com.jd.rc.localtranslate

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.VersionInfo
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
    @Test fun hyJniLoadsAndRejectsMissingModelWithoutDownloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val translator = com.jd.rc.localtranslate.inference.HyTranslator(java.io.File(context.filesDir, "missing.gguf"))
        try {
            var rejected = false
            try { translator.translate("Hello") } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
        } finally { translator.close(); translator.close() }
    }
}