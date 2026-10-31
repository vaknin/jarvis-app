package com.kivan.jarvis.bench

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class Measure {
    @get:Rule val rule = MacrobenchmarkRule()

    // Frames, split: the app's own work, and the drawing, which is software on the emulator.
    private val frames = listOf(
        FrameTimingMetric(),
        TraceSectionMetric("Choreographer#doFrame%", TraceSectionMetric.Mode.Average),
        TraceSectionMetric("DrawFrame%", TraceSectionMetric.Mode.Average),
    )

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        APP,
        listOf(StartupTimingMetric()),
        mode,
        StartupMode.COLD,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait(tabsIntent(9))
    }

    @Test fun startupNoProfile() = startup(CompilationMode.None())

    @Test fun startupProfile() = startup(CompilationMode.Partial())

    @Test fun roundTrip() = rule.measureRepeated(
        APP,
        frames,
        CompilationMode.Partial(),
        iterations = 4,
        setupBlock = { openWall(9) },
    ) { roundTrip() }

    @Test fun scroll() = rule.measureRepeated(
        APP,
        frames,
        CompilationMode.Partial(),
        iterations = 4,
        setupBlock = { openWall(9) },
    ) { scrollTab() }

    @Test fun keyboard() = rule.measureRepeated(
        APP,
        frames,
        CompilationMode.Partial(),
        iterations = 4,
        setupBlock = { openWall(14) },
    ) { keyboardSqueeze() }
}
