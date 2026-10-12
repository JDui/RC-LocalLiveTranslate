package com.jd.rc.localtranslate

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class, qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ActivityLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForCaptions() = compose.waitUntil(5000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithTag("session-captions").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun productionActivityRecreationRetainsNavigationSelectionAndReading() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        val captions = (0 until 40).map { index ->
            SubtitleItem("lifecycle-record", "$index", index * 16_000L, (index + 1) * 16_000L, 1,
                SourceLanguage.EN, "Lifecycle line $index", "", "A", CaptionStage.FINAL, TranslationStatus.UNAVAILABLE)
        }
        runBlocking {
            app.sessions.save(SavedSession("lifecycle-record", "Lifecycle record", 1, 40, false, CaptionJson.encode(captions)))
        }
        compose.onNodeWithContentDescription("对话记录").performClick()
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithContentDescription("打开会话 Lifecycle record").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("打开会话 Lifecycle record").performClick()
        waitForCaptions()
        compose.onNodeWithTag("session-captions").performScrollToIndex(15)
        compose.onNodeWithText("Lifecycle line 15").assertIsDisplayed()
        val position = compose.onNodeWithText("Lifecycle line 15").fetchSemanticsNode().boundsInRoot.top
        val previous = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertNotSame(previous, compose.activity)
        waitForCaptions()
        compose.onNodeWithText("Lifecycle line 15").assertIsDisplayed()
        assertEquals(position, compose.onNodeWithText("Lifecycle line 15").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription("返回记录列表").performClick()
        compose.onNodeWithContentDescription("打开会话 Lifecycle record").assertIsDisplayed()
    }
}
