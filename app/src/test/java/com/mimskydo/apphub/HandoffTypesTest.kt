package com.mimskydo.apphub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HandoffTypesTest {

    private val images = listOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
    )

    private val music = listOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mid",
    )

    private val video = listOf(
        "mp4", "m4v", "mkv", "avi", "mov", "3gp", "webm", "wmv", "ts",
    )

    @Test
    fun `every image extension leaves as an image type`() {
        images.forEach { ext ->
            val type = Handoff.type("dashcam-capture.$ext")
            assertTrue(
                "picture .$ext went out as '$type', which no viewer claims",
                type.startsWith("image/"),
            )
        }
    }

    @Test
    fun `every music extension leaves as an audio type`() {
        music.forEach { ext ->
            val type = Handoff.type("set.$ext")
            assertTrue(
                "audio .$ext went out as '$type', which no player claims",
                type.startsWith("audio/"),
            )
        }
    }

    @Test
    fun `every video extension leaves as a video type`() {
        video.forEach { ext ->
            val type = Handoff.type("trip.$ext")
            assertTrue(
                "video .$ext went out as '$type', which no player claims",
                type.startsWith("video/"),
            )
        }
    }

    @Test
    fun `an apk is offered as an installable package`() {
        assertEquals(
            "application/vnd.android.package-archive",
            Handoff.type("hub-update.apk"),
        )
    }

    @Test
    fun `bundles are archives, not packages`() {
        assertEquals("application/zip", Handoff.type("bundle.apks"))
        assertEquals("application/zip", Handoff.type("bundle.apkm"))
        assertEquals("application/zip", Handoff.type("bundle.xapk"))
    }

    @Test
    fun `a page is offered as html, not as text`() {
        assertEquals("text/html", Handoff.type("manual.htm"))
        assertEquals("text/html", Handoff.type("manual.html"))
    }

    @Test
    fun `the unknown is honest - octet-stream`() {
        assertEquals("application/octet-stream", Handoff.type("firmware.bin"))
        assertEquals("application/octet-stream", Handoff.type("no-extension"))
        assertEquals("application/octet-stream", Handoff.type(null))
    }

    @Test
    fun `the case of the extension does not matter`() {
        assertEquals(Handoff.type("photo.JPG"), Handoff.type("photo.jpg"))
        assertEquals(Handoff.type("FILM.Mp4"), Handoff.type("FILM.mp4"))
    }

    @Test
    fun `every extension the icons draw a kind for leaves under its family type`() {
        (images.map { it to "image/" } + music.map { it to "audio/" } + video.map { it to "video/" })
            .forEach { (ext, family) ->
                val file = File("unit-test.$ext")
                assertTrue(
                    ".$ext is not drawn as a kind any more - the icon table moved",
                    Files.kindOf(file) != FileKind.OTHER,
                )
                val type = Handoff.type(file.name)
                assertTrue(
                    ".$ext is drawn as a kind but leaves as '$type', which nothing claims",
                    type.startsWith(family),
                )
            }
    }
}
