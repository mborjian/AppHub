package com.mimskydo.apphub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The agreement between the two extension tables, checked the way the local
 * working contract asks for: every extension the file manager draws as a kind
 * that has a family type must be handed out under that type by [Handoff.type]
 * - a picture that leaves as `application/octet-stream` goes nowhere, because
 * no receiving app claims a picture under that name.
 *
 * The two lists are deliberately *not* the same list - the icons draw the kinds
 * a driver recognises, the MIME table names what opens a door - and this is the
 * seam between them, pinned so either table can change without the other
 * drifting.
 */
class HandoffTypesTest {

    /** extensions the icons draw as IMAGE, and the type each must leave under */
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
        // an .apkm or .xapk is a container with APKs inside: the file manager
        // draws it as an archive and the hand-off must not offer it to the
        // platform's installer
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
        // the real seam, both tables live: for every extension the file manager
        // draws as image, music or video, the hand-off must name a type of that
        // same family - never the honest-for-nothing octet-stream, which no
        // viewer or player claims for a picture or a film
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
