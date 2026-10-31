package com.kivan.jarvis.bench

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val APP = "com.kivan.jarvis.bench"

fun tabsIntent(tabs: Int) = Intent().apply {
    component = ComponentName(APP, "com.kivan.jarvis.tabs.TabsActivity")
    putExtra("tabs", tabs)
}

fun MacrobenchmarkScope.openWall(tabs: Int) {
    startActivityAndWait(tabsIntent(tabs))
    device.wait(Until.hasObject(By.descStartsWith("Tab 1, ")), 5_000)
    device.waitForIdle()
}

private fun MacrobenchmarkScope.firstTile() = device.wait(Until.findObject(By.descStartsWith("Tab 1, ")), 3_000)

/** Into a tab, through its neighbours, and out with a pinch. */
fun MacrobenchmarkScope.roundTrip() {
    firstTile().click()
    device.wait(Until.hasObject(By.desc("Mute")), 2_000)
    Thread.sleep(500)
    val w = device.displayWidth
    val h = device.displayHeight
    repeat(3) {
        device.swipe(w * 9 / 10, h / 2, w / 10, h / 2, 12)
        Thread.sleep(450)
    }
    repeat(3) {
        device.swipe(w / 10, h / 2, w * 9 / 10, h / 2, 12)
        Thread.sleep(450)
    }
    pinchOut()
    device.wait(Until.hasObject(By.descStartsWith("Tab 1, ")), 2_000)
    Thread.sleep(600)
}

/** Two fingers closing on the middle of the screen: from the tab to the wall. */
fun MacrobenchmarkScope.pinchOut() {
    device.findObject(By.pkg(APP).depth(0)).pinchClose(0.8f, 1400)
}

fun MacrobenchmarkScope.scrollTab() {
    firstTile().click()
    Thread.sleep(600)
    val list = device.wait(Until.findObject(By.scrollable(true)), 2_000)
    list?.setGestureMargin(device.displayHeight / 6)
    repeat(2) {
        list?.fling(Direction.UP, 5_000)
        Thread.sleep(300)
        list?.fling(Direction.DOWN, 5_000)
        Thread.sleep(300)
    }
    device.pressBack()
    Thread.sleep(600)
}

/** The keyboard comes up over 14 tiles and goes: the wall squeezes without moving a tile. */
fun MacrobenchmarkScope.keyboardSqueeze() {
    device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000).click()
    Thread.sleep(900)
    device.pressBack()
    Thread.sleep(900)
}
