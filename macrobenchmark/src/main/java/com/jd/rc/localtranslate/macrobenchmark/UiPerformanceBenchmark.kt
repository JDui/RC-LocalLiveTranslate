package com.jd.rc.localtranslate.macrobenchmark

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class UiPerformanceBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Before fun requirePrimaryUser() {
        // Shell package/settings commands operate on the primary test user only.
        check(Process.myUserHandle() == UserHandle.getUserHandleForUid(0)) { "Run benchmarks in the primary device user only" }
    }

    @Test fun coldStartupWithoutModels() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { resetFixture(); pressHome() },
    ) { startActivityAndWait() }

    @Test fun completedDemoSubtitleScroll() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 3,
        setupBlock = { resetFixture(); prepareDemo(completed = true) },
    ) {
        val list = requireNotNull(device.findObjects(By.scrollable(true)).maxByOrNull {
            it.visibleBounds.let { bounds -> bounds.width().toLong() * bounds.height() }
        }) { "No subtitle viewport is available" }
        list.setGestureMargin(minOf(list.visibleBounds.width(), list.visibleBounds.height()) / 5)
        repeat(4) { list.fling(Direction.UP); device.waitForIdle(); list.fling(Direction.DOWN); device.waitForIdle() }
    }

    @Test fun rotationDuringDemo() {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val sensor = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        val rotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
        require(sensor in 0..1 && rotation in 0..3)
        try {
            benchmark.measureRepeated(
                packageName = TARGET,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.None(),
                iterations = 3,
                setupBlock = { resetFixture(); prepareDemo(completed = false) },
            ) {
                device.setOrientationLeft(); device.waitForIdle()
                check(device.wait(Until.hasObject(By.text("演示数据")), 5000))
                device.setOrientationNatural(); device.waitForIdle()
                check(device.wait(Until.hasObject(By.text("演示数据")), 5000))
            }
        } finally {
            val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.unfreezeRotation()
            device.executeShellCommand("settings put system user_rotation $rotation")
            device.executeShellCommand("settings put system accelerometer_rotation $sensor")
            check(Settings.System.getInt(resolver, Settings.System.USER_ROTATION) == rotation)
            check(Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION) == sensor)
        }
    }

    private fun MacrobenchmarkScope.resetFixture() {
        // Never parameterize this clear operation with the production package.
        check(packageName == TARGET && TARGET.endsWith(".benchmark"))
        val manager = InstrumentationRegistry.getInstrumentation().context.packageManager
        val info = manager.getApplicationInfo(TARGET, 0)
        check(info.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        check(device.executeShellCommand("pm clear $TARGET").trim() == "Success")
        check(manager.checkPermission(Manifest.permission.RECORD_AUDIO, TARGET) == PackageManager.PERMISSION_DENIED)
    }

    private fun MacrobenchmarkScope.prepareDemo(completed: Boolean) {
        startActivityAndWait()
        requireNotNull(device.wait(Until.findObject(By.text("演示")), 5000)).click()
        check(device.wait(Until.hasObject(By.text("演示数据")), 5000))
        requireNotNull(device.wait(Until.findObject(By.text("播放演示")), 5000)).click()
        check(device.wait(Until.hasObject(By.text("暂停")), 5000))
        if (completed) check(device.wait(Until.hasObject(By.text("播放演示")), 30_000))
        device.waitForIdle()
    }

    private companion object { const val TARGET = "com.jd.rc.localtranslate.benchmark" }
}
