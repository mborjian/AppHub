package com.mimskydo.apphub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The address-or-search rule, as a table. This is the one piece of the browser
 * a driver meets on every single use, and its failures are quiet ones - a bare
 * word resolved as a host lands on a domain squat, a search with a dot in it
 * never finds anything. The rule lives in [Web.target] alone, so these pin the
 * rule and not a screen.
 */
class WebTargetTest {

    private fun target(typed: String): String = Web.target(typed)

    @Test
    fun `empty input goes nowhere`() {
        assertEquals("", target(""))
        assertEquals("", target("   "))
    }

    @Test
    fun `a scheme is obeyed, not rewritten`() {
        assertEquals("https://example.com", target("https://example.com"))
        assertEquals("http://192.168.1.1/", target("http://192.168.1.1/"))
        assertEquals("about:blank", target("about:blank"))
        assertEquals("data:text/html,hi", target("data:text/html,hi"))
    }

    @Test
    fun `a dotted word is a host`() {
        assertEquals("https://example.com", target("example.com"))
        assertEquals("https://weather.co.uk", target("weather.co.uk"))
        assertEquals("https://192.168.1.1", target("192.168.1.1"))
    }

    @Test
    fun `localhost is a host without a dot`() {
        assertEquals("https://localhost", target("localhost"))
        assertEquals("https://LOCALHOST", target("LOCALHOST"))
        assertEquals("https://localhost:8080/x", target("localhost:8080/x"))
    }

    @Test
    fun `words with spaces are a search`() {
        assertEquals(
            "${Web.SEARCH}dashcam%20firmware",
            target("dashcam firmware"),
        )
    }

    @Test
    fun `a bare word is a search, not a domain squat`() {
        assertEquals("${Web.SEARCH}dashcam", target("dashcam"))
        assertEquals("${Web.SEARCH}weather", target("weather"))
    }

    @Test
    fun `a trailing dot is not a host`() {
        assertEquals("${Web.SEARCH}example.", target("example."))
    }

    @Test
    fun `a one-letter suffix is not a host`() {
        assertEquals("${Web.SEARCH}a.b", target("a.b"))
    }

    @Test
    fun `paths and queries survive the https prefix`() {
        assertEquals("https://example.com/a/b?c=1", target("example.com/a/b?c=1"))
    }

    @Test
    fun `farsi text is percent-encoded as a search`() {
        val target = target("آب و هوا")
        assertEquals("${Web.SEARCH}%D8%A2%D8%A8%20%D9%88%20%D9%87%D9%88%D8%A7", target)
    }
}
