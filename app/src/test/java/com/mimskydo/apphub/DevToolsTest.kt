package com.mimskydo.apphub

import org.junit.Assert.assertEquals
import org.junit.Test

class DevToolsTest {

    @Test
    fun `skew under a minute is seconds`() {
        assertEquals("+0s", DevTools.skewText(0L))
        assertEquals("+5s", DevTools.skewText(5_000L))
        assertEquals("-5s", DevTools.skewText(-5_000L))
        assertEquals("+59s", DevTools.skewText(59_000L))
    }

    @Test
    fun `skew under an hour is minutes`() {
        assertEquals("+1m", DevTools.skewText(60_000L))
        assertEquals("-59m", DevTools.skewText(-(59 * 60_000L)))
    }

    @Test
    fun `skew under a day keeps hours and minutes`() {
        assertEquals("+1h 0m", DevTools.skewText(3_600_000L))
        assertEquals("-2h 30m", DevTools.skewText(-(2 * 3600 + 30 * 60) * 1_000L))
    }

    @Test
    fun `days are what a car with a flat clock shows`() {
        assertEquals("+3d 4h", DevTools.skewText((3 * 86_400 + 4 * 3600) * 1_000L))
        assertEquals("-1d 0h", DevTools.skewText(-86_400_000L))
    }
}
