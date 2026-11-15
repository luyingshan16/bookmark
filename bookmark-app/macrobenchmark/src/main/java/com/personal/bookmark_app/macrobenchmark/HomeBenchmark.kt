package com.personal.bookmark_app.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeBenchmark {
    @get:Rule
    val benchmark = MacrobenchmarkRule()

    @Test
    fun coldStartToFolders() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
        checkNotNull(device.wait(Until.findObject(folderList), 15_000)) {
            "Sign in to the benchmark app with an account that has folders."
        }
    }

    @Test
    @OptIn(ExperimentalMetricApi::class)
    fun scrollFolders() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingGfxInfoMetric()),
        iterations = 5,
        setupBlock = {
            killProcess()
            startActivityAndWait()
            checkNotNull(device.wait(Until.findObject(folderList), 15_000)) {
                "Sign in to the benchmark app with an account that has folders."
            }
        }
    ) {
        val list = device.findObject(folderList)
        list.setGestureMargin(device.displayWidth / 5)
        list.swipe(Direction.UP, 0.7f)
        list.swipe(Direction.DOWN, 0.7f)
        device.waitForIdle()
    }

    private val folderList = By.res("home_folder_list")

    private companion object {
        const val TARGET_PACKAGE = "com.personal.bookmark_app.benchmark"
    }
}
