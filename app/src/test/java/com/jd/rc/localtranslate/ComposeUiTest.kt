package com.jd.rc.localtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.audio.RecordingState
import com.jd.rc.localtranslate.audio.RecordingPhase
import com.jd.rc.localtranslate.audio.RecordingService
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.data.*
import com.jd.rc.localtranslate.model.ModelState
import com.jd.rc.localtranslate.inference.ConversationState
import com.jd.rc.localtranslate.runtime.*
import com.jd.rc.localtranslate.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import kotlinx.coroutines.runBlocking
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class, qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ComposeUiTest {
    @get:Rule(order = 0) val windowLayout = WindowLayoutInfoPublisherRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun pairSelectorChangesSettingAndChineseTranslationIsVisible() {
        val settings = mutableStateOf(UserSettings())
        compose.setContent {
            GlassTheme(settings.value) {
                LiveScreen(LiveState(), RecordingState(), settings.value, 0, {}, {}, {}, {}, {}, {}, {},
                    changeMode = { settings.value = settings.value.copy(translationMode = it) })
            }
        }
        compose.onNodeWithText("语言：译中文").performClick()
        compose.onNodeWithText("中日").performClick()
        compose.onNodeWithText("语言：中日").assertExists()
        compose.runOnIdle { assertEquals(TranslationMode.ZH_JA, settings.value.translationMode) }
    }
    private val captions = listOf(
        SubtitleItem("demo", "1", 0, 56_000, 1, SourceLanguage.EN,
            "Shall we take a walk by the river after the meeting?", "会议结束后，我们去河边走走好吗？", "A", CaptionStage.FINAL, TranslationStatus.COMPLETE),
        SubtitleItem("demo", "2", 64_000, 120_000, 1, SourceLanguage.JA,
            "いいですね。夕方は少し涼しくなりそうです。", "好呀，傍晚应该会凉快一些。", "B", CaptionStage.FINAL, TranslationStatus.COMPLETE),
        SubtitleItem("demo", "3", 128_000, 184_000, 1, SourceLanguage.ZH,
            "那我们六点在楼下见，顺便一起吃晚饭。", "", "A", CaptionStage.FINAL, TranslationStatus.NOT_REQUIRED),
    )
    private fun render(name: String, page: Page = Page.LIVE, dark: Boolean = false, largeText: Boolean = false,
        folds: List<WindowFold> = emptyList()) {
        val settings = UserSettings(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 2f else 1f)) {
                GlassTheme(settings) {
                    Box(Modifier.fillMaxSize()) {
                        GlassBackdrop(Modifier.matchParentSize())
                        FoldAppShell(folds, Modifier.fillMaxSize().safeDrawingPadding(), navigation = { GlassNavigation(page, it) {} }) {
                                when (page) {
                                    Page.LIVE -> LiveScreen(LiveState("demo", captions, true, paused = true, elapsedSeconds = 12),
                                        RecordingState(), settings, 0, {}, {}, {}, {}, {}, {}, {})
                                    Page.MODELS -> ModelsScreen(ModelState(), {}, {})
                                    Page.SESSIONS -> SessionsScreen(listOf(SavedSession("demo", "多语言对话 · 演示", 1_791_520_000_000,
                                        18, true, CaptionJson.encode(captions))), settings, { _, _ -> }, {}, { _, _, _ -> })
                                    Page.SETTINGS -> SettingsScreen(settings, RecordingState(), {})
                                }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        snapshot(name)
    }
    private fun snapshot(name: String, includeDialog: Boolean = false) {
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                view.draw(canvas)
                if (includeDialog) {
                    val dialog = requireNotNull(ShadowDialog.getLatestDialog()).window?.decorView
                    requireNotNull(dialog).draw(canvas)
                }
            }
        }
        assertTrue("Screenshot must be nonblank", bitmap.width > 300 && bitmap.height > 300)
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 7) for (x in 0 until bitmap.width step 7) colors.add(bitmap.getPixel(x, y))
        assertTrue("Rendered UI must contain content", colors.size > 100)
        File("build/ui-screenshots").mkdirs()
        File("build/ui-screenshots/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun renderLightPhone() = render("live-light")
    @Test fun renderDarkPhone() = render("live-dark", dark = true)
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") fun renderSmallPhone() = render("live-small")
    @Test @Config(qualifiers = "w915dp-h412dp-land-mdpi") fun renderLandscape() = render("live-landscape")
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun renderTablet() = render("live-tablet")
    @Test fun renderLargeFont() = render("live-font-200", largeText = true)
    @Test fun renderModels() = render("models", Page.MODELS)
    @Test fun renderSettings() = render("settings", Page.SETTINGS)
    @Test fun renderSessions() = render("sessions", Page.SESSIONS)
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun renderBookFold() {
        render("live-book-fold", folds = listOf(WindowFold(IntRect(450, 0, 450, 900), FoldAxis.VERTICAL, true, false)))
        val title = compose.onNode(hasText("即时字幕") and !hasContentDescription("即时字幕")).fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right < 450); assertTrue(controls.left > 450)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun renderTabletopFold() {
        render("live-tabletop-fold", folds = listOf(WindowFold(IntRect(0, 450, 900, 450), FoldAxis.HORIZONTAL, true, false)))
        assertTrue(compose.onNode(hasText("即时字幕") and !hasContentDescription("即时字幕")).fetchSemanticsNode().boundsInRoot.bottom < 450)
        assertTrue(compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot.top > 450)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun renderDualScreenSettings() {
        render("settings-dual-screen", Page.SETTINGS, folds = listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)))
        assertTrue(compose.onNodeWithText("字幕字号").fetchSemanticsNode().boundsInRoot.right < 440)
        assertTrue(compose.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot.left > 460)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun sessionDialogStaysInsideBookPane() {
        render("session-book-fold", Page.SESSIONS, folds = listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)))
        compose.onNodeWithContentDescription("打开会话 多语言对话 · 演示").performClick()
        compose.onNodeWithContentDescription("重命名").performClick()
        val dialog = compose.onNodeWithText("重命名会话").fetchSemanticsNode().boundsInRoot
        assertTrue(dialog.right < 440)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("导出").performClick()
        compose.onNodeWithText("SRT 字幕").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("JSON").fetchSemanticsNode().boundsInRoot.right < 440)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun systemPostureUpdatesPreserveCaptionReadingPosition() {
        val history = (0 until 40).map { captions[0].copy(segmentId = "$it", sourceText = "Line $it", targetText = "") }
        val observed = mutableStateOf<List<WindowFold>>(emptyList())
        compose.setContent {
            val folds = rememberWindowFolds()
            androidx.compose.runtime.SideEffect { observed.value = folds }
            GlassTheme(UserSettings()) {
                FoldAppShell(folds, Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.LIVE, it) {} }) {
                    LiveScreen(LiveState("unchanged-session", history, true, paused = true), RecordingState(), UserSettings(), 0,
                        {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("live-captions").performScrollToIndex(5)
        compose.onNodeWithText("Line 5").assertIsDisplayed()
        val feature = object : FoldingFeature {
            override val bounds = Rect(450, 0, 450, 900)
            override val orientation = FoldingFeature.Orientation.VERTICAL
            override val state = FoldingFeature.State.HALF_OPENED
            override val occlusionType = FoldingFeature.OcclusionType.NONE
            override val isSeparating = true
        }
        windowLayout.overrideWindowLayoutInfo(WindowLayoutInfo(listOf(feature)))
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); observed.value.isNotEmpty() }
        compose.onNodeWithText("Line 5").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot.left > 450)
        windowLayout.overrideWindowLayoutInfo(WindowLayoutInfo(emptyList()))
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); observed.value.isEmpty() }
        compose.onNodeWithText("Line 5").assertIsDisplayed()
    }
    @Test fun navigationAndSettingsControlsAreInteractive() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        runBlocking { app.settings.update(UserSettings()) }
        val vm = AppViewModel(app)
        compose.setContent { TranslateApp(vm) }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("双语字幕").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !vm.settings.value.bilingual }
        compose.onNodeWithText("降低透明度").performScrollTo().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); vm.settings.value.reduceTransparency }
        compose.onNodeWithContentDescription("离线模型").performClick()
        compose.onNodeWithText("SenseVoice Small").assertIsDisplayed()
        compose.onNodeWithContentDescription("即时字幕").performClick()
        compose.onNodeWithText("开始拾音").assertIsDisplayed()
        compose.onNodeWithText("演示").performClick()
        compose.onNodeWithText("演示数据").assertIsDisplayed()
        assertTrue(vm.live.value.demo)
        assertEquals(com.jd.rc.localtranslate.audio.RecordingPhase.IDLE, vm.recording.value.phase)
    }
    @Test fun historicalSessionOpensAndOffersAllExportFormats() {
        render("session-list-interaction", Page.SESSIONS)
        compose.onNodeWithContentDescription("打开会话 多语言对话 · 演示").performClick()
        compose.onNodeWithText("会议结束后，我们去河边走走好吗？", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("导出").performClick()
        compose.onNodeWithText("纯文本").assertIsDisplayed()
        compose.onNodeWithText("SRT 字幕").assertIsDisplayed()
        compose.onNodeWithText("JSON").assertIsDisplayed()
    }
    @Test fun licenseAndSourceNoticesAreAvailableOffline() {
        render("settings-license-entry", Page.SETTINGS)
        compose.onNodeWithText("开源许可").performScrollTo().performClick()
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("hymt/SOURCE.txt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("hymt/SOURCE.txt").performClick()
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasText("github.com/ggml-org/llama.cpp", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("关闭").performClick()
    }
    @Test fun settingsReportsInstalledPackageVersion() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        assertTrue(version.isNotBlank())
        render("settings-installed-version", Page.SETTINGS)
        compose.onNodeWithText(version).performScrollTo().assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun performanceControlsAndDiagnosticsStayInFoldPane() {
        val settings = mutableStateOf(UserSettings(keepSessions = false))
        compose.setContent {
            GlassTheme(settings.value) {
                FoldAppShell(listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)),
                    Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.SETTINGS, it) {} }) {
                    SettingsScreen(settings.value, RecordingState(), { settings.value = it.applyTo(settings.value) }, runtime = RuntimePerformance(
                        active = true, snapshot = PressureSnapshot(thermalStatus = 3, availableBytes = 256L * 1024 * 1024)))
                }
            }
        }
        compose.onNodeWithText("省电").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PerformanceMode.ECO, settings.value.performanceMode); assertFalse(settings.value.keepSessions) }
        compose.onNodeWithText("严重").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithText("严重").fetchSemanticsNode().boundsInRoot.left > 460)
        assertTrue(compose.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot.left > 460)
        snapshot("settings-runtime-fold")
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun diagnosticExportDialogAvoidsHinge() {
        compose.setContent {
            GlassTheme(UserSettings()) {
                FoldAppShell(listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)),
                    Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.SETTINGS, it) {} }) {
                    SettingsScreen(UserSettings(), RecordingState(), {}, hasDiagnostics = true,
                        processSample = ProcessSample(pssBytes = 128L * 1024 * 1024, rssBytes = 256L * 1024 * 1024))
                }
            }
        }
        compose.onNodeWithContentDescription("导出运行诊断").performScrollTo().performClick()
        compose.onNodeWithText("JSON").assertIsDisplayed(); compose.onNodeWithText("CSV").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("JSON").fetchSemanticsNode().boundsInRoot.right < 440)
        snapshot("diagnostic-export-fold", includeDialog = true)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("JSON").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") fun pressureStatusLeavesStopReachableAtLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                GlassTheme(UserSettings()) {
                    FoldAppShell(emptyList(), Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.LIVE, it) {} }) {
                        LiveScreen(LiveState("session", captions, running = true), RecordingState(phase = RecordingPhase.RECORDING),
                            UserSettings(), 5, {}, {}, {}, {}, {}, {}, {}, processing = ConversationState(
                                performanceStatus = "原文优先：临时字幕、翻译与声纹暂停。"))
                    }
                }
            }
        }
        compose.onNodeWithTag("performance-status").assertIsDisplayed()
        compose.onNodeWithContentDescription("停止").assertIsDisplayed()
        compose.onNodeWithText("暂停").assertIsDisplayed()
        val status = compose.onNodeWithTag("performance-status").fetchSemanticsNode().boundsInRoot
        val stop = compose.onNodeWithContentDescription("停止").fetchSemanticsNode().boundsInRoot
        assertTrue(status.bottom <= stop.top)
        snapshot("live-pressure-font-200")
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun flatExpandedSettingsUsesBothColumns() {
        render("settings-expanded", Page.SETTINGS)
        compose.onNodeWithText("双语字幕").assertIsDisplayed()
        compose.onNodeWithText("省电").assertIsDisplayed()
        val display = compose.onNodeWithText("双语字幕").fetchSemanticsNode().boundsInRoot
        val performance = compose.onNodeWithText("省电").fetchSemanticsNode().boundsInRoot
        assertTrue(display.right < performance.left)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun bookModelsShowSpeechAndTranslationInSeparatePanes() {
        render("models-book-expanded", Page.MODELS, folds = listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)))
        compose.onNodeWithText("SenseVoice Small").assertIsDisplayed()
        compose.onNodeWithText("Hy-MT2 1.8B · 1.25-bit").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("SenseVoice Small").fetchSemanticsNode().boundsInRoot.right < 440)
        assertTrue(compose.onNodeWithText("Hy-MT2 1.8B · 1.25-bit").fetchSemanticsNode().boundsInRoot.left > 460)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun flatLiveHasTranslationCompanionAndReachableControls() {
        render("live-expanded")
        val history = compose.onNodeWithTag("live-captions").fetchSemanticsNode().boundsInRoot
        val companion = compose.onNodeWithTag("live-companion").fetchSemanticsNode().boundsInRoot
        val resume = compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot
        assertTrue(history.right <= companion.left)
        assertTrue(companion.bottom <= resume.top)
        compose.onNodeWithText("继续").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun tabletopSettingsKeepControlsBelowHinge() {
        render("settings-tabletop-expanded", Page.SETTINGS, dark = true,
            folds = listOf(WindowFold(IntRect(0, 440, 900, 460), FoldAxis.HORIZONTAL, true, true)))
        compose.onNodeWithText("双语字幕").assertIsDisplayed()
        compose.onNodeWithText("省电").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("双语字幕").fetchSemanticsNode().boundsInRoot.bottom < 440)
        assertTrue(compose.onNodeWithText("省电").fetchSemanticsNode().boundsInRoot.top > 460)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun tabletopModelsKeepImportsWithinTheirPanes() {
        render("models-tabletop", Page.MODELS,
            folds = listOf(WindowFold(IntRect(0, 440, 900, 460), FoldAxis.HORIZONTAL, true, true)))
        compose.onNodeWithText("SenseVoice Small").assertIsDisplayed()
        compose.onNodeWithText("Hy-MT2 1.8B · 1.25-bit").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("SenseVoice Small").fetchSemanticsNode().boundsInRoot.bottom < 440)
        assertTrue(compose.onNodeWithText("Hy-MT2 1.8B · 1.25-bit").fetchSemanticsNode().boundsInRoot.top > 460)
        compose.onNodeWithContentDescription("导入模型包").assertIsDisplayed()
        assertTrue(compose.onNodeWithContentDescription("导入模型包").fetchSemanticsNode().boundsInRoot.bottom < 440)
        compose.onNodeWithText("从本地导入").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithText("从本地导入").fetchSemanticsNode().boundsInRoot.top > 460)
        snapshot("models-tabletop-import")
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun tabletopSessionSelectionKeepsReadingAndActionsBelowHinge() {
        compose.setContent {
            GlassTheme(UserSettings()) {
                FoldAppShell(listOf(WindowFold(IntRect(0, 440, 900, 460), FoldAxis.HORIZONTAL, true, true)),
                    Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.SESSIONS, it) {} }) {
                    SessionsScreen(longSessions(), UserSettings(), { _, _ -> }, {}, { _, _, _ -> })
                }
            }
        }
        compose.onNodeWithContentDescription("打开会话 Record 2").performClick().assertIsSelected()
        compose.onNodeWithTag("session-captions").performScrollToIndex(5)
        compose.onNodeWithText("Record 2 Line 5").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("sessions-list").fetchSemanticsNode().boundsInRoot.bottom < 440)
        assertTrue(compose.onNodeWithTag("session-captions").fetchSemanticsNode().boundsInRoot.top > 460)
        compose.onNodeWithContentDescription("导出").assertIsDisplayed()
        snapshot("sessions-tabletop-selected")
    }
    private fun longSessions() = (1..2).map { number ->
        val rows = (0 until 40).map { captions[0].copy(segmentId = "$number-$it", sourceText = "Record $number Line $it", targetText = "") }
        SavedSession("record-$number", "Record $number", number.toLong(), 160, false, CaptionJson.encode(rows))
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun sessionSelectionAndReadingSurviveFoldAndResize() {
        val width = mutableStateOf(900.dp)
        val folds = mutableStateOf(listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)))
        compose.setContent {
            GlassTheme(UserSettings()) {
                Box(Modifier.width(width.value).fillMaxHeight()) {
                    FoldAppShell(folds.value, Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.SESSIONS, it) {} }) {
                        SessionsScreen(longSessions(), UserSettings(), { _, _ -> }, {}, { _, _, _ -> })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("打开会话 Record 2").performClick().assertIsSelected()
        compose.onNodeWithTag("session-captions").performScrollToIndex(5)
        compose.onNodeWithText("Record 2 Line 5").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("sessions-list").fetchSemanticsNode().boundsInRoot.right < 440)
        assertTrue(compose.onNodeWithTag("session-captions").fetchSemanticsNode().boundsInRoot.left > 460)
        snapshot("sessions-expanded-selected")
        compose.runOnIdle { width.value = 412.dp; folds.value = emptyList() }
        compose.onNodeWithText("Record 2 Line 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回记录列表").assertIsDisplayed()
        snapshot("sessions-compact-selected")
        compose.runOnIdle { width.value = 900.dp }
        compose.onNodeWithText("Record 2 Line 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开会话 Record 2").assertIsSelected()
        compose.onNodeWithContentDescription("返回记录列表").assertDoesNotExist()
    }
    @Test fun sessionSelectionAndScrollRestoreAfterSavedStateRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            GlassTheme(UserSettings()) {
                FoldAppShell(emptyList(), Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.SESSIONS, it) {} }) {
                    SessionsScreen(longSessions(), UserSettings(), { _, _ -> }, {}, { _, _, _ -> })
                }
            }
        }
        compose.onNodeWithContentDescription("打开会话 Record 2").performClick()
        compose.onNodeWithTag("session-captions").performScrollToIndex(5)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Record 2 Line 5").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("打开会话 Record 2").assertIsDisplayed()
        compose.onNodeWithTag("session-captions").assertDoesNotExist()
    }
    @Test fun restoredReadingPositionSurvivesDelayedSessionLoad() {
        val restoration = StateRestorationTester(compose)
        var delayLoad = false
        var rows: MutableState<List<SavedSession>>? = null
        restoration.setContent {
            // Change only the next composition's initial data, after saving the old state.
            val loaded = remember { mutableStateOf(if (delayLoad) emptyList() else longSessions()) }
            SideEffect { rows = loaded }
            GlassTheme(UserSettings()) {
                FoldAppShell(emptyList(), Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.SESSIONS, it) {} }) {
                    SessionsScreen(loaded.value, UserSettings(), { _, _ -> }, {}, { _, _, _ -> })
                }
            }
        }
        compose.onNodeWithContentDescription("打开会话 Record 2").performClick()
        compose.onNodeWithTag("session-captions").performScrollToIndex(15)
        compose.onNodeWithText("Record 2 Line 15").assertIsDisplayed()
        val position = compose.onNodeWithText("Record 2 Line 15").fetchSemanticsNode().boundsInRoot.top
        delayLoad = true
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("session-captions").assertDoesNotExist()
        compose.runOnIdle { requireNotNull(rows).value = longSessions() }
        compose.onNodeWithText("Record 2 Line 15").assertIsDisplayed()
        assertEquals(position, compose.onNodeWithText("Record 2 Line 15").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun largeFontFoldSettingsKeepPrivacyAndPerformanceIndependent() {
        val settings = mutableStateOf(UserSettings(keepSessions = false))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                GlassTheme(settings.value) {
                    FoldAppShell(listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)), Modifier.fillMaxSize(),
                        navigation = { GlassNavigation(Page.SETTINGS, it) {} }) {
                        SettingsScreen(settings.value, RecordingState(), { settings.value = it.applyTo(settings.value) })
                    }
                }
            }
        }
        compose.onNodeWithText("省电").performClick()
        compose.runOnIdle { assertEquals(PerformanceMode.ECO, settings.value.performanceMode); assertFalse(settings.value.keepSessions) }
        compose.onNodeWithText("保留对话记录").performScrollTo().assertIsDisplayed()
        val privacy = compose.onNodeWithText("保留对话记录").fetchSemanticsNode().boundsInRoot
        assertTrue(privacy.right < 440)
        snapshot("settings-book-font-200")
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun postureChangesDoNotRepeatRecordingCommands() {
        val folds = mutableStateOf(emptyList<WindowFold>())
        val commands = mutableListOf<String>()
        compose.setContent {
            GlassTheme(UserSettings()) {
                FoldAppShell(folds.value, Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.LIVE, it) {} }) {
                    LiveScreen(LiveState("same-session", captions, demo = false, running = true),
                        RecordingState(phase = RecordingPhase.RECORDING, sampleOffset = 192_000), UserSettings(), 5,
                        {}, {}, {}, {}, { commands += it }, {}, {})
                }
            }
        }
        compose.onNodeWithText("暂停").performClick()
        compose.runOnIdle { folds.value = listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)) }
        compose.onNodeWithContentDescription("停止").assertIsDisplayed()
        compose.runOnIdle { folds.value = listOf(WindowFold(IntRect(0, 440, 900, 460), FoldAxis.HORIZONTAL, true, true)) }
        compose.onNodeWithText("暂停").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(RecordingService.PAUSE), commands) }
        compose.onNodeWithContentDescription("停止").performClick()
        compose.runOnIdle { assertEquals(listOf(RecordingService.PAUSE, RecordingService.STOP), commands) }
    }
}
