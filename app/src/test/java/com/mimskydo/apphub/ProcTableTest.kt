package com.mimskydo.apphub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcTableTest {

    private val google = listOf("com.google.android.gsf", "com.google.android.gms")

    @Test
    fun `a process is named after its package`() {
        assertEquals(
            "com.spotify.music",
            ProcTable.packageOf("com.spotify.music", listOf("com.spotify.music")),
        )
    }

    @Test
    fun `a named subprocess still belongs to its package`() {
        assertEquals(
            "com.spotify.music",
            ProcTable.packageOf("com.spotify.music:player", listOf("com.spotify.music")),
        )
        assertEquals(
            "com.google.android.gms",
            ProcTable.packageOf("com.google.android.gms:sandboxed_process0", listOf("com.google.android.gms")),
        )
    }

    @Test
    fun `a dotted process name picks the longest package that fits`() {
        assertEquals("com.google.android.gms", ProcTable.packageOf("com.google.android.gms.persistent", google))
        assertEquals("com.google.android.gsf", ProcTable.packageOf("com.google.android.gsf", google))
    }

    @Test
    fun `a process nobody owns is nobody's app`() {
        assertNull(ProcTable.packageOf("kworker/u8:2", google))
        assertNull(ProcTable.packageOf("surfaceflinger", listOf("com.spotify.music")))
        assertNull(ProcTable.packageOf("com.android.settings", emptyList()))
    }

    @Test
    fun `a name that merely starts the same is not a match`() {
        assertNull(ProcTable.packageOf("com.spotify.musicpro", listOf("com.spotify.music")))
        assertNull(ProcTable.packageOf("com.google.android.gmsx", google))
    }

    @Test
    fun `the foreground band is the framework's own`() {
        assertTrue(ProcTable.isForeground(0))
        assertTrue(ProcTable.isForeground(100))
        assertTrue(ProcTable.isForeground(200))
        assertFalse(ProcTable.isForeground(500))
        assertFalse(ProcTable.isForeground(900))
        assertFalse(ProcTable.isForeground(null))
    }
}
