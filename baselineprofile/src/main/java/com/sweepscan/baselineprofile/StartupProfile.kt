package com.sweepscan.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The release app's package (the target is a release-type build, without the .debug suffix). */
private val TARGET: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "com.sweepscan"

/** Records the code a cold start runs (home screen, and the introduction on a first run). */
@RunWith(AndroidJUnit4::class)
class StartupProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // A second start after the introduction was seen: the home screen everyone opens daily.
        device.pressBack()
        device.waitForIdle()
    }
}

/** Cold start time with no ahead-of-time code vs. with the baseline profile. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithProfile() = startup(CompilationMode.Partial())

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 8,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}
