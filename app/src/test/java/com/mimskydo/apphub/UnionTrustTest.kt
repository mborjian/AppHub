package com.mimskydo.apphub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The shape of the fix for "Chain validation failed", tested without a device.
 *
 * [GithubTls.UnionTrust] is what a stale trust store meets: the platform's own
 * manager is asked first and keeps the last word whenever it gives one, and the
 * bundled anchors are only heard after the platform has refused. Both halves are
 * worth pinning - an app that quietly stopped using the platform's store would
 * be trusting *less* than the unit does, and one that asked the bundle first
 * would be trusting it more than it was ever asked to.
 */
class UnionTrustTest {

    /** refuses everything, like a store from before the CA existed */
    private class Refuses : X509TrustManager {
        var asked = 0
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("refused")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            asked++
            throw CertificateException("refused")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** accepts everything, and says it was asked */
    private class Accepts : X509TrustManager {
        var asked = 0
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            asked++
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            asked++
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `the bundled anchors answer only after the platform refuses`() {
        val system = Refuses()
        val bundled = Accepts()
        val union = GithubTls.UnionTrust(system, bundled)

        union.checkServerTrusted(emptyArray(), "RSA")

        assertEquals("the platform's store was not asked first", 1, system.asked)
        assertEquals("the bundled anchors were not tried after a refusal", 1, bundled.asked)
    }

    @Test
    fun `a platform that answers keeps the last word`() {
        val system = Accepts()
        val bundled = Refuses()
        val union = GithubTls.UnionTrust(system, bundled)

        union.checkServerTrusted(emptyArray(), "RSA")

        assertEquals(1, system.asked)
        assertEquals("the bundle must not be asked when the platform accepted", 0, bundled.asked)
    }

    @Test
    fun `a platform with no manager at all still has the anchors`() {
        val bundled = Accepts()
        val union = GithubTls.UnionTrust(null, bundled)

        union.checkServerTrusted(emptyArray(), "RSA")

        assertEquals(1, bundled.asked)
    }

    @Test
    fun `a chain both stores refuse is refused`() {
        val union = GithubTls.UnionTrust(Refuses(), Refuses())
        var refused = false
        try {
            union.checkServerTrusted(emptyArray(), "RSA")
        } catch (e: CertificateException) {
            refused = true
        }
        assertTrue("a chain neither store accepts has to be refused", refused)
    }
}
