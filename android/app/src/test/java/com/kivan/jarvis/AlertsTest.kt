package com.kivan.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertsTest {
    /** `jarvis notify` knows these words: it tells Aviv what to turn on (jarvis cli/src/notify.rs). */
    @Test fun aSettingIsNamed() {
        assertEquals("app notifications off", Notifier.off(false, 4, "Jarvis"))
        assertEquals("app notifications off", Notifier.off(false, 0, "Jarvis"))
        assertEquals("Jarvis channel off", Notifier.off(true, 0, "Jarvis"))
        assertEquals("Tracked tasks channel off", Notifier.off(true, 0, "Tracked tasks"))
        assertNull(Notifier.off(true, 4, "Jarvis"))
        // A channel Android doesn't have yet: the notify goes ahead.
        assertNull(Notifier.off(true, null, "Jarvis"))
    }

    @Test fun aThrowIsNamed() {
        assertEquals("threw IllegalStateException: no", Alerts.threw(IllegalStateException("no")))
        assertEquals("threw OutOfMemoryError", Alerts.threw(OutOfMemoryError()))
    }
}
