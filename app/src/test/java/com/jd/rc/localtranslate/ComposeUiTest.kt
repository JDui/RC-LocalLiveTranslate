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
import androidx.compose.ui.semantics.SemanticsProperties
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
import com.jd.rc.localtranslate.model.InstalledModel
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
    @Test fun missingModelDownloadAndCancellationAreReachableWithSmallProgressUnits() {
        val state = mutableStateOf(ModelState())
        var started = false; var cancelled = false
        compose.setContent { GlassTheme(UserSettings()) {
            ModelsScreen(state.value, {}, {}, { started = true }, { cancelled = true })
        } }
        compose.onNodeWithTag("download-models").performClick()
        compose.runOnIdle {
            assertTrue(started)
            state.value = ModelState(busy = true, downloading = true, downloadedBytes = 1024, downloadTotalBytes = 643854)
        }
        compose.onNodeWithText("1 KiB / 628 KiB").assertExists()
        compose.onNodeWithText("取消下载").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithTag("download-models").assertDoesNotExist()
    }
    @Test fun twoLanguageSelectorsDefaultToChineseAndOfferKoreanAndCantonese() {
        val settings = mutableStateOf(UserSettings())
        compose.setContent {
            GlassTheme(settings.value) {
                LiveScreen(LiveState(), RecordingState(), settings.value, {}, {}, {}, {}, changeMode = { settings.value = settings.value.copy(translationMode = it) })
            }
        }
        compose.onNodeWithContentDescription("第一语言").assertTextContains("自动识别").performClick()
        compose.onNodeWithText("韩语").performClick()
        compose.onNodeWithContentDescription("第一语言").assertTextContains("韩语")
        compose.onNodeWithContentDescription("第二语言").assertTextContains("中文")
        compose.runOnIdle { assertEquals(TranslationMode(SourceLanguage.KO, SourceLanguage.ZH), settings.value.translationMode) }
        compose.onNodeWithContentDescription("第二语言").performClick()
        compose.onNodeWithText("粤语").performClick()
        compose.onNodeWithContentDescription("第二语言").assertTextContains("粤语")
        compose.runOnIdle { assertEquals(TranslationMode(SourceLanguage.KO, SourceLanguage.YUE), settings.value.translationMode) }
        snapshot("five-language-selectors")
        compose.onNodeWithContentDescription("第一语言").performClick()
        compose.onNodeWithTag("language-option-yue").assertDoesNotExist()
        compose.onNodeWithTag("language-option-en").performClick()
        compose.onNodeWithContentDescription("第一语言").assertTextContains("English")
        compose.onNodeWithContentDescription("第二语言").performClick()
        compose.onNodeWithTag("language-option-en").assertDoesNotExist()
        compose.onNodeWithTag("language-option-ko").performClick()
    }

    @Test fun bothLanguageSelectorsStayLockedThroughoutSessionAndUnlockAfterStop() {
        val recording = mutableStateOf(RecordingState())
        compose.setContent { GlassTheme(UserSettings()) {
            LiveScreen(LiveState(), recording.value, UserSettings(), {}, {}, {}, {})
        } }
        for (phase in listOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED, RecordingPhase.STOPPING)) {
            compose.runOnIdle { recording.value = RecordingState(phase = phase) }
            compose.onNodeWithContentDescription("第一语言").assertIsNotEnabled()
            compose.onNodeWithContentDescription("第二语言").assertIsNotEnabled()
        }
        compose.runOnIdle { recording.value = RecordingState() }
        compose.onNodeWithContentDescription("第一语言").assertIsEnabled()
        compose.onNodeWithContentDescription("第二语言").assertIsEnabled()
    }

    @Test fun openingMenuThenStartingSessionDismissesLanguageOptions() {
        val recording = mutableStateOf(RecordingState())
        compose.setContent { GlassTheme(UserSettings()) {
            LiveScreen(LiveState(), recording.value, UserSettings(), {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("第一语言").performClick()
        compose.onNodeWithTag("language-option-ko").assertIsDisplayed()
        compose.runOnIdle { recording.value = RecordingState(phase = RecordingPhase.STARTING) }
        compose.onNodeWithTag("language-option-ko").assertDoesNotExist()
        compose.onNodeWithContentDescription("第一语言").assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun languageSelectorsFitNarrowScreenWithDoubleFontScale() {
        val settings = mutableStateOf(UserSettings())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                GlassTheme(settings.value) {
                    Box(Modifier.fillMaxSize()) {
                        GlassBackdrop(Modifier.matchParentSize())
                        LiveScreen(LiveState(), RecordingState(), settings.value, {}, {}, {}, {},
                            changeMode = { settings.value = settings.value.copy(translationMode = it) })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("第一语言").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        for (language in SourceLanguage.supported.filter { it != SourceLanguage.ZH }) compose.onNodeWithTag("language-option-${language.code}").assertIsDisplayed()
        compose.onNodeWithTag("language-option-ko").performClick()
        compose.onNodeWithContentDescription("第二语言").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("language-option-yue").performClick()
        val left = compose.onNodeWithContentDescription("第一语言").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithContentDescription("第二语言").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= right.left)
        assertTrue(left.left >= 0f && right.right <= 320f)
        snapshot("five-language-narrow-large-font")
    }
    private val savedTitle = SessionTitle.create(1_791_520_000_000, "上海")
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
                                    Page.LIVE -> LiveScreen(LiveState("demo", captions, true, paused = true, elapsedSeconds = 12), RecordingState(), settings, {}, {}, {}, {})
                                    Page.MODELS -> ModelsScreen(ModelState(), {}, {})
                                    Page.SESSIONS -> SessionsScreen(listOf(SavedSession("demo", savedTitle, 1_791_520_000_000,
                                        18, true, CaptionJson.encode(captions))), settings, { _, _ -> }, {}, { _, _, _ -> }, loadPage = ::fixturePage)
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
        assertTrue("Screenshot must include drawn content", colors.size > 1)
        assertTrue("Rendered UI must contain text", compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().isNotEmpty())
        File("build/ui-screenshots").mkdirs()
        File("build/ui-screenshots/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun appendedCaptionsFollowUntilUserReadsHistory() {
        val rows = mutableStateOf((0 until 12).map { captions[0].copy(segmentId = "$it", sourceText = "Row $it", targetText = "译文 $it") })
        compose.setContent {
            GlassTheme(UserSettings()) {
                FoldAppShell(emptyList(), Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.LIVE, it) {} }) {
                    LiveScreen(LiveState("follow", rows.value, demo = true), RecordingState(), UserSettings(), {}, {}, {}, {})
                }
            }
        }
        compose.runOnIdle { rows.value = rows.value + captions[0].copy(segmentId = "next", sourceText = "Newest", targetText = "最新译文") }
        compose.onNodeWithText("最新译文").assertIsDisplayed()
        compose.onNodeWithContentDescription("回到最新字幕").assertDoesNotExist()
        compose.onNodeWithTag("live-captions").performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription("回到最新字幕").assertIsDisplayed()
        compose.runOnIdle { rows.value = rows.value + captions[0].copy(segmentId = "later", sourceText = "After drag", targetText = "恢复后译文") }
        compose.onAllNodesWithText("恢复后译文").assertCountEquals(0)
        compose.onNodeWithContentDescription("回到最新字幕").performClick()
        compose.onNodeWithText("恢复后译文").assertIsDisplayed()
        compose.onNodeWithContentDescription("回到最新字幕").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") fun largeFontKeepsCaptionAreaAndStartVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                GlassTheme(UserSettings()) {
                    FoldAppShell(emptyList(), Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.LIVE, it) {} }) {
                        LiveScreen(LiveState(), RecordingState(), UserSettings(), {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("暂无字幕").assertIsDisplayed()
        compose.onNodeWithText("开始拾音").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("live-content").fetchSemanticsNode().boundsInRoot.height >= 96f)
    }
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") fun compactLiveKeepsMostHeightForSubtitles() {
        compose.setContent {
            GlassTheme(UserSettings()) {
                FoldAppShell(emptyList(), Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.LIVE, it) {} }) {
                    LiveScreen(LiveState("compact", captions, paused = true), RecordingState(phase = RecordingPhase.PAUSED),
                        UserSettings(fontSize = 8f), {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithContentDescription("切换为仅译文").assertDoesNotExist()
        compose.onNodeWithText("麦克风").assertDoesNotExist()
        compose.onNodeWithText("演示").assertDoesNotExist()
        compose.onNodeWithText("模型 4/4").assertDoesNotExist()
        compose.onNodeWithText("继续").assertIsDisplayed()
        compose.onNodeWithContentDescription("停止").assertIsDisplayed()
        val content = compose.onNodeWithTag("live-content").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("live-toolbar").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("live-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(content.height >= 450f)
        assertTrue(toolbar.height <= 52f)
        assertTrue(controls.height <= 64f)
        snapshot("live-compact-font-8")
    }
    @Test fun modelDetailsKeepTechnicalMetadataOutOfList() {
        val state = ModelState(installed = listOf(InstalledModel("sensevoice", "fixture-version", "fixture-license", "https://example.org/model", 1024)))
        compose.setContent { GlassTheme(UserSettings()) { ModelsScreen(state, {}, {}) } }
        compose.onNodeWithText("https://example.org/model").assertDoesNotExist()
        compose.onNodeWithText("1 KiB").assertIsDisplayed()
        compose.onNodeWithContentDescription("SenseVoice Small详情").performClick()
        compose.onNodeWithText("版本：fixture-version").assertIsDisplayed()
        compose.onNodeWithText("许可：fixture-license").assertIsDisplayed()
        compose.onNodeWithText("https://example.org/model").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("https://example.org/model").assertDoesNotExist()
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
        val title = compose.onNodeWithTag("live-toolbar").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right < 450); assertTrue(controls.left > 450)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun renderTabletopFold() {
        render("live-tabletop-fold", folds = listOf(WindowFold(IntRect(0, 450, 900, 450), FoldAxis.HORIZONTAL, true, false)))
        assertTrue(compose.onNodeWithTag("live-toolbar").fetchSemanticsNode().boundsInRoot.bottom < 450)
        assertTrue(compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot.top > 450)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun renderDualScreenSettings() {
        render("settings-dual-screen", Page.SETTINGS, folds = listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)))
        assertTrue(compose.onNodeWithText("字幕字号").fetchSemanticsNode().boundsInRoot.right < 440)
        assertTrue(compose.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot.left > 460)
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun sessionDialogStaysInsideBookPane() {
        render("session-book-fold", Page.SESSIONS, folds = listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)))
        compose.onNodeWithContentDescription("打开会话 $savedTitle").performClick()
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
                    LiveScreen(LiveState("unchanged-session", history, true, paused = true), RecordingState(), UserSettings(), {}, {}, {}, {})
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
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun expandedDockKeepsSettingsAtBottomAcrossPagesAndChildren() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        runBlocking { app.settings.update(UserSettings()) }
        val vm = AppViewModel(app)
        compose.setContent { TranslateApp(vm) }
        val tags = listOf("app-dock", "dock-live", "dock-sessions", "dock-settings")
        fun positions() = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val initial = positions()
        assertTrue(initial[0].right <= 96f)
        assertTrue(initial[3].top > 800f)
        assertTrue(initial[1].bottom < initial[2].top)
        assertTrue(initial[2].bottom < initial[3].top)
        for (page in listOf("sessions", "settings", "live", "settings")) {
            compose.onNodeWithTag("dock-$page").performClick()
            assertEquals(initial, positions())
        }
        compose.onNodeWithContentDescription("离线模型").performClick()
        assertEquals(initial, positions())
        snapshot("dock-expanded-model-settings")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithContentDescription("运行与诊断").performScrollTo().performClick()
        assertEquals(initial, positions())
    }

    @Test fun compactDockKeepsItsBoundsAcrossAllPages() {
        val selected = mutableStateOf(Page.LIVE)
        compose.setContent { GlassTheme(UserSettings()) {
            FoldAppShell(emptyList(), Modifier.fillMaxSize(), navigation = {
                GlassNavigation(selected.value, it) { page -> selected.value = page }
            }) { Box(Modifier.fillMaxSize()) }
        } }
        val initial = compose.onNodeWithTag("app-dock").fetchSemanticsNode().boundsInRoot
        assertTrue(initial.top > 800f)
        for (page in listOf("sessions", "settings", "live")) {
            compose.onNodeWithTag("dock-$page").performClick()
            assertEquals(initial, compose.onNodeWithTag("app-dock").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun navigationAndSettingsControlsAreInteractive() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        runBlocking { app.settings.update(UserSettings()) }
        val vm = AppViewModel(app)
        compose.setContent { TranslateApp(vm) }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).assertCountEquals(3)
        compose.onNodeWithContentDescription("离线模型").assertDoesNotExist()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("双语字幕").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !vm.settings.value.bilingual }
        compose.onNodeWithText("降低透明度").performScrollTo().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); vm.settings.value.reduceTransparency }
        compose.onNodeWithContentDescription("离线模型").performScrollTo().performClick()
        compose.onNodeWithText("SenseVoice Small").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithContentDescription("即时字幕").performClick()
        compose.onNodeWithText("开始拾音").assertIsDisplayed()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("demo-mode").performScrollTo().performClick()
        compose.onNodeWithContentDescription("即时字幕").performClick()
        compose.onNodeWithText("演示数据").assertIsDisplayed()
        assertTrue(vm.live.value.demo)
        assertEquals(com.jd.rc.localtranslate.audio.RecordingPhase.IDLE, vm.recording.value.phase)
    }
    @Test fun sessionRequiresTwoSeparateLeftSwipesAndRightSwipeClosesDelete() {
        val session = SavedSession("swipe", savedTitle, 1_791_520_000_000, 18, true, CaptionJson.encode(captions))
        val records = mutableStateOf(listOf(session))
        var deletes = 0
        compose.setContent { GlassTheme(UserSettings()) {
            SessionsScreen(records.value, UserSettings(), { _, _ -> }, { id ->
                deletes++; records.value = records.value.filter { it.id != id }
            }, { _, _, _ -> }, loadPage = ::fixturePage)
        } }
        compose.onNodeWithTag("swipe-session-swipe").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText("再滑一次").assertIsDisplayed()
        snapshot("session-swipe-revealed")
        compose.onNodeWithTag("swipe-session-swipe").performTouchInput { swipeRight() }
        compose.onNodeWithText("再滑一次").assertDoesNotExist()
        compose.onNodeWithTag("swipe-session-swipe").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithTag("swipe-session-swipe").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, deletes) }
        compose.onNodeWithText("还没有对话记录").assertIsDisplayed()
    }

    @Test fun settingsChildrenRestoreAndReturnWithModelsAsFirstEntry() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GlassTheme(UserSettings()) { SettingsScreen(UserSettings(), RecordingState(), {}) } }
        compose.onNodeWithContentDescription("离线模型").assertIsDisplayed().performClick()
        compose.onNodeWithText("SenseVoice Small").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("SenseVoice Small").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithContentDescription("运行与诊断").performScrollTo().performClick()
        compose.onNodeWithText("压力监测").assertExists()
        compose.onNodeWithContentDescription("返回设置").performScrollTo().performClick()
        compose.onNodeWithContentDescription("离线模型").performScrollTo().assertIsDisplayed()
    }

    @Test fun historicalSessionOpensAndOffersAllExportFormats() {
        render("session-list-interaction", Page.SESSIONS)
        compose.onNodeWithContentDescription("打开会话 $savedTitle").performClick()
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
        compose.onNodeWithContentDescription("运行与诊断").performScrollTo().performClick()
        compose.onNodeWithText("严重").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithText("严重").fetchSemanticsNode().boundsInRoot.right < 440)
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
        compose.onNodeWithContentDescription("运行与诊断").performScrollTo().performClick()
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
                        LiveScreen(LiveState("session", captions, running = true), RecordingState(phase = RecordingPhase.RECORDING), UserSettings(), {}, {}, {}, {}, processing = ConversationState(
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
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun flatLiveUsesFullWidthForCaptionsAndReachableControls() {
        render("live-expanded")
        val history = compose.onNodeWithTag("live-captions").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("live-companion").assertDoesNotExist()
        val resume = compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot
        assertTrue(history.width >= 760f)
        assertTrue(history.bottom <= resume.top)
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
                    SessionsScreen(longSessions(), UserSettings(), { _, _ -> }, {}, { _, _, _ -> }, loadPage = ::fixturePage)
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
    @Test fun sessionCaptionPaginationPreservesSelectionAndLoadsOnlyRequestedPage() {
        val rows = (0 until 405).map { captions[0].copy(sessionId = "paged", segmentId = "page-$it", sourceText = "Page line $it", targetText = "") }
        val requested = mutableListOf<Int>()
        val record = SavedSession("paged", "Paged record", 1, 405, false, "[]")
        compose.setContent {
            GlassTheme(UserSettings()) {
                FoldAppShell(emptyList(), Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.SESSIONS, it) {} }) {
                    SessionsScreen(listOf(record), UserSettings(), { _, _ -> }, {}, { _, _, _ -> }, loadPage = { _, page ->
                        requested += page
                        kotlinx.coroutines.flow.flowOf(com.jd.rc.localtranslate.data.SessionCaptionPage(rows.drop(page * 200).take(200), rows.size > (page + 1) * 200))
                    })
                }
            }
        }
        compose.onNodeWithContentDescription("打开会话 Paged record").performClick()
        compose.onNodeWithText("Page line 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页字幕").performClick()
        compose.onNodeWithText("Page line 200").assertIsDisplayed()
        compose.onNodeWithText("Page line 0").assertDoesNotExist()
        compose.onNodeWithContentDescription("下一页字幕").performClick()
        compose.onNodeWithText("Page line 400").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页字幕").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一页字幕").performClick()
        compose.onNodeWithText("Page line 200").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(0, 1, 2, 1), requested) }
        snapshot("sessions-paged-captions")
    }
    @Test @Config(qualifiers = "w900dp-h900dp-mdpi") fun metadataPaginationKeepsOpenedRecordOnOtherListPage() {
        val page = mutableStateOf(0)
        val records = (0 until 51).map { SavedSession("list-$it", "List record $it", it.toLong(), 1, false, "[]") }
        compose.setContent {
            GlassTheme(UserSettings()) {
                FoldAppShell(listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)), Modifier.fillMaxSize(), navigation = { GlassNavigation(Page.SESSIONS, it) {} }) {
                    SessionsScreen(records.drop(page.value * 50).take(50), UserSettings(), { _, _ -> }, {}, { _, _, _ -> },
                        page.value, 51, { page.value = it }, loadPage = { record, _ ->
                            kotlinx.coroutines.flow.flowOf(com.jd.rc.localtranslate.data.SessionCaptionPage(listOf(captions[0].copy(segmentId = record.id, sourceText = "Opened " + record.id)), false))
                        })
                }
            }
        }
        compose.onNodeWithContentDescription("打开会话 List record 1").performClick()
        compose.onNodeWithText("Opened list-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页记录").performClick()
        compose.onNodeWithContentDescription("打开会话 List record 50").assertIsDisplayed()
        compose.onNodeWithText("Opened list-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页记录").assertIsNotEnabled()
        snapshot("sessions-paged-metadata")
    }
    private fun fixturePage(session: SavedSession, page: Int): kotlinx.coroutines.flow.Flow<com.jd.rc.localtranslate.data.SessionCaptionPage> {
        val items = CaptionJson.decode(session.captionsJson)
        return kotlinx.coroutines.flow.flowOf(com.jd.rc.localtranslate.data.SessionCaptionPage(items.drop(page * 200).take(200), items.size > (page + 1) * 200))
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
                        SessionsScreen(longSessions(), UserSettings(), { _, _ -> }, {}, { _, _, _ -> }, loadPage = ::fixturePage)
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
                    SessionsScreen(longSessions(), UserSettings(), { _, _ -> }, {}, { _, _, _ -> }, loadPage = ::fixturePage)
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
                    SessionsScreen(loaded.value, UserSettings(), { _, _ -> }, {}, { _, _, _ -> }, loadPage = ::fixturePage)
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
                    LiveScreen(LiveState("same-session", captions, demo = false, running = true), RecordingState(phase = RecordingPhase.RECORDING, sampleOffset = 192_000), UserSettings(), {}, {}, {}, { commands += it })
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
