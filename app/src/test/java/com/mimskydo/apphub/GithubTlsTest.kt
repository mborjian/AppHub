package com.mimskydo.apphub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The update path's trust, held to what it claims.
 *
 * `res/raw/github_roots.pem` is a trust decision shipped inside the app, so it
 * gets the treatment a trust decision deserves: the bundle is parsed from the
 * same file the build packages, every certificate in it has to be self-signed
 * (a trust store accepts nothing else as an anchor on every platform), it has to
 * still be valid, and it has to be the two authorities GitHub's chains actually
 * end at today.
 *
 * The second half is the other half of the same door: which addresses the update
 * path will talk to at all. The APK and its checksum come out of the release
 * API's JSON, so the hosts are pinned here rather than trusted from the answer,
 * and a redirect is only followed inside GitHub.
 */
class GithubTlsTest {

    /** the file the build packages, read from the module the tests run in */
    private val pem: String = File("src/main/res/raw/github_roots.pem").readText()

    // ------------------------------------------------------------ the anchors

    @Test
    fun `the bundle carries three anchors`() {
        assertEquals(3, GithubTls.certificates(pem).size)
    }

    @Test
    fun `every anchor is self-signed`() {
        for (certificate in GithubTls.certificates(pem)) {
            assertEquals(
                "an anchor that is not self-signed is not an anchor",
                certificate.subjectX500Principal,
                certificate.issuerX500Principal,
            )
        }
    }

    @Test
    fun `every anchor is still valid`() {
        val now = System.currentTimeMillis()
        for (certificate in GithubTls.certificates(pem)) {
            assertTrue(
                "the bundle carries an expired anchor: ${certificate.subjectX500Principal.name}",
                certificate.notAfter.time > now,
            )
        }
    }

    @Test
    fun `the anchors are the authorities GitHub's chains end at`() {
        val names = GithubTls.certificates(pem).map { it.subjectX500Principal.name }
        assertTrue(
            "the Sectigo root that signs github.com is missing",
            names.any { it.contains("Sectigo Public Server Authentication Root E46") },
        )
        assertTrue(
            "the ISRG root the asset hosts chain to is missing",
            names.any { it.contains("CN=Root YR") },
        )
        assertTrue(
            "the cross-signing ISRG root is missing",
            names.any { it.contains("CN=ISRG Root X1") },
        )
    }

    // --------------------------------------------------------------- the hosts

    @Test
    fun `the update hosts are the addresses GitHub serves releases from`() {
        assertTrue(Updater.isGithub("https://api.github.com/repos/mborjian/AppHub/releases/latest"))
        assertTrue(Updater.isGithub("https://github.com/mborjian/AppHub/releases/download/v1.0.0/AppHub.apk"))
        assertTrue(Updater.isGithub("https://release-assets.githubusercontent.com/github-production-release-asset/1/2"))
        assertTrue(Updater.isGithub("https://objects.githubusercontent.com/x"))
    }

    @Test
    fun `a lookalike host is not one of them`() {
        assertFalse(Updater.isGithub("https://github.com.evil.example/AppHub.apk"))
        assertFalse(Updater.isGithub("https://notgithubusercontent.com/AppHub.apk"))
        assertFalse(Updater.isGithub("https://githubusercontent.com.evil.example/AppHub.apk"))
    }

    @Test
    fun `and neither is plain http`() {
        assertFalse(Updater.isGithub("http://github.com/AppHub.apk"))
        assertFalse(Updater.isGithub("http://api.github.com/repos/x/y"))
    }

    @Test
    fun `a redirect is followed inside GitHub, relative or not`() {
        assertEquals(
            "https://release-assets.githubusercontent.com/a?b=c",
            Updater.redirectTarget(
                "https://github.com/mborjian/AppHub/releases/download/v1.0.0/AppHub.apk",
                "https://release-assets.githubusercontent.com/a?b=c",
            ),
        )
        assertEquals(
            "https://github.com/elsewhere",
            Updater.redirectTarget("https://github.com/x/y", "/elsewhere"),
        )
    }

    @Test
    fun `a redirect out of GitHub is refused`() {
        assertNull(Updater.redirectTarget("https://github.com/x", "https://evil.example/AppHub.apk"))
        assertNull(Updater.redirectTarget("https://github.com/x", "http://github.com/x"))
        assertNull(Updater.redirectTarget("https://github.com/x", "file:///etc/passwd"))
    }
}
