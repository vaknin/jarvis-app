package com.kivan.jarvis.bench

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What Aviv does first and most, so ART has it compiled before the first launch feels slow. */
@RunWith(AndroidJUnit4::class)
class Profile {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(APP, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        openWall(14)
        openWall(9)
        roundTrip()
        scrollTab()
    }
}
