package com.jd.rc.localtranslate

import android.Manifest
import android.os.Looper
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.data.*
import com.jd.rc.localtranslate.runtime.PerformanceMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class)
class LocalDataTest {
    @Test fun pairPreferenceAndCaptionTargetLanguageRoundTrip() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        app.settings.update(SettingsChange(translationMode = TranslationMode.ZH_JA))
        assertEquals(TranslationMode.ZH_JA, app.settings.settings.first().translationMode)
        val item = SubtitleItem("session", "segment", 0, 16000, sourceLanguage = SourceLanguage.ZH,
            sourceText = "早上好", targetText = "おはようございます", targetLanguage = SourceLanguage.JA)
        assertEquals(listOf(item), CaptionJson.decode(CaptionJson.encode(listOf(item))))
    }

    @Test fun mergedManifestKeepsMicrophoneButNoNetworkPermissions() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in permissions)
    }
    @Test fun captionJsonRoundTripPreservesLanguageRevisionAndUnknownSpeaker() {
        val captions = listOf(SubtitleItem("session", "segment", 16_000, 32_000, 3, SourceLanguage.JA,
            "こんにちは\n\"引用\"", "你好", null, CaptionStage.REVISED, TranslationStatus.COMPLETE, 2, 3))
        assertEquals(captions, CaptionJson.decode(CaptionJson.encode(captions)))
    }
    @Test fun storedSessionCanBeRenamedAndDeleted() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, SessionDatabase::class.java).build()
        try {
            val dao = db.sessions()
            val row = SavedSession("id", "演示", 1000, 18, true, "[]")
            dao.save(row)
            assertEquals(row, dao.observe().first().single())
            dao.rename("id", "新名字")
            assertEquals("新名字", dao.observe().first().single().title)
            dao.delete("id")
            assertTrue(dao.observe().first().isEmpty())
        } finally { db.close() }
    }
    @Test fun settingsPersistAcrossRepositoryInstances() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val expected = UserSettings(ThemeMode.DARK, false, 24f, false, true)
        SettingsRepository(app).update(expected)
        assertEquals(expected, SettingsRepository(app).settings.first())
    }
    @Test fun ecoModePersistsAlongsidePrivacyAndDisplaySettings() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val expected = UserSettings(theme = ThemeMode.DARK, keepSessions = false, performanceMode = PerformanceMode.ECO)
        app.settings.update(expected)
        assertEquals(expected, SettingsRepository(app).settings.first())
        assertFalse(app.settings.canKeepSessions())
    }
    @Test fun recordingRetentionGateFollowsPrivacySettingAndPersists() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        app.settings.update(UserSettings(keepSessions = true)); assertTrue(app.settings.canKeepSessions())
        app.settings.update(UserSettings(keepSessions = false)); assertFalse(app.settings.canKeepSessions())
        assertFalse(SettingsRepository(app).canKeepSessions())
        app.settings.update(UserSettings(keepSessions = true)); assertTrue(app.settings.canKeepSessions())
    }
    @Test fun concurrentFieldChangesCannotRestoreRecordingRetention() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        app.settings.update(UserSettings())
        coroutineScope {
            launch { app.settings.update(SettingsChange(keepSessions = false)) }
            launch { app.settings.update(SettingsChange(fontSize = 24f)) }
            launch { app.settings.update(SettingsChange(performanceMode = PerformanceMode.ECO)) }
            launch { app.settings.update(SettingsChange(theme = ThemeMode.DARK)) }
        }
        val expected = UserSettings(theme = ThemeMode.DARK, fontSize = 24f, keepSessions = false,
            performanceMode = PerformanceMode.ECO)
        assertEquals(expected, app.settings.settings.first())
        assertEquals(expected, SettingsRepository(app).settings.first())
        assertFalse(app.settings.canKeepSessions())
    }
    @Test fun displayUpdatesDoNotChangePrivacyIntentAndClampFontSize() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        app.settings.update(UserSettings(keepSessions = false))
        app.settings.update(SettingsChange(bilingual = false, fontSize = 90f, reduceTransparency = true))
        val current = app.settings.settings.first()
        assertFalse(current.keepSessions)
        assertFalse(app.settings.canKeepSessions())
        assertFalse(current.bilingual)
        assertTrue(current.reduceTransparency)
        assertEquals(28f, current.fontSize)
        app.settings.update(SettingsChange(keepSessions = true))
        assertTrue(app.settings.canKeepSessions())
    }
    @Test @LooperMode(LooperMode.Mode.PAUSED) fun privacyDisableProtectsBeforeUiFlowPublishes() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        runBlocking { app.settings.update(UserSettings()) }
        val vm = AppViewModel(app)
        val owner = ViewModelStore().apply { put("settings", vm) }
        try {
            vm.setSettings(SettingsChange(keepSessions = false))
            vm.setSettings(SettingsChange(performanceMode = PerformanceMode.ECO))
            vm.setSettings(SettingsChange(theme = ThemeMode.DARK))
            assertTrue(vm.settings.value.keepSessions)
            runBlocking {
                assertFalse(app.settings.canKeepSessions())
                withTimeout(5000) {
                    var current = app.settings.settings.first()
                    while (current.keepSessions || current.performanceMode != PerformanceMode.ECO || current.theme != ThemeMode.DARK) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(10)
                        current = app.settings.settings.first()
                    }
                    assertFalse(current.keepSessions)
                }
            }
        } finally { owner.clear() }
    }
}
