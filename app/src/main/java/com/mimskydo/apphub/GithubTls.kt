package com.mimskydo.apphub

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The trust the update path stands on when the unit's own store has gone stale.
 *
 * A head unit's trust store is a snapshot of the year it shipped: the unit is
 * Android 9, and GitHub has since moved to certificate authorities that did not
 * exist then (a Sectigo E46 chain for github.com, a Let's Encrypt "YR" chain for
 * the release asset hosts). The platform's answer to a chain its store cannot
 * build is `SSLHandshakeException: Chain validation failed`, which is the wall
 * this file exists to take down - the update screen could not reach GitHub at
 * all, whatever the network was doing.
 *
 * The fix is additive, never subtractive. The platform's own trust manager is
 * asked first, exactly as before; `res/raw/github_roots.pem` is the second
 * voice, and it is only heard when the first one refuses. That file holds the
 * self-signed roots the two chains terminate at, each checked against the live
 * chains when it was added:
 *
 *  * `Sectigo Public Server Authentication Root E46` - github.com and
 *    api.github.com;
 *  * `ISRG Root X1` and `Root YR` - the asset hosts, whose chains are
 *    cross-signed by X1 and rooted in the YR generation.
 *
 * A bundled root is a trust decision, so it has to be the real one: each was
 * verified to be self-signed and to validate the live chain end to end with
 * `openssl verify -CAfile`. Nothing else is trusted, no check is skipped, and
 * when GitHub rotates to a CA that is not here the file gets another anchor -
 * which is exactly the maintenance this app's two-year-old store cannot do for
 * itself.
 */
object GithubTls {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"

    /**
     * The factory the update path puts on its connections, or null when there is
     * nothing to add - a bundle that cannot be read must leave the platform's
     * own behaviour alone rather than break the network for everyone.
     */
    internal fun socketFactory(context: Context): SSLSocketFactory? = try {
        val bundled = bundledTrust(context)
        if (bundled == null) {
            null
        } else {
            val managers = arrayOf<TrustManager>(UnionTrust(systemTrust(), bundled))
            SSLContext.getInstance("TLS").apply { init(null, managers, null) }.socketFactory
        }
    } catch (t: Throwable) {
        Log.i(TAG, "the bundled update anchors are unusable: $t")
        null
    }

    /**
     * Every certificate in a PEM bundle. Pure on purpose: the bundled anchors are
     * a file in the repository, and a unit test reads that same file and holds
     * its contents to what this parser says they are.
     */
    internal fun certificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>()
        var rest = pem
        while (true) {
            val begin = rest.indexOf(BEGIN)
            if (begin < 0) break
            val end = rest.indexOf(END, begin)
            if (end < 0) break
            val der = Base64.getMimeDecoder().decode(rest.substring(begin + BEGIN.length, end))
            out += factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            rest = rest.substring(end + END.length)
        }
        return out
    }

    /** The platform's own manager, or null on a platform that has none. */
    private fun systemTrust(): X509TrustManager? = try {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (t: Throwable) {
        null
    }

    /** The bundled anchors as a manager, or null when the file gave none. */
    private fun bundledTrust(context: Context): X509TrustManager? = try {
        val pem = context.resources.openRawResource(R.raw.github_roots)
            .use(InputStream::readBytes)
            .decodeToString()
        val certificates = certificates(pem)
        if (certificates.isEmpty()) {
            null
        } else {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            certificates.forEachIndexed { index, certificate ->
                store.setCertificateEntry("anchor-$index", certificate)
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }
    } catch (t: Throwable) {
        Log.i(TAG, "the bundled update anchors could not be read: $t")
        null
    }

    /**
     * Two stores, one answer: the platform's first, the bundle's only where the
     * platform refused. A server that chains to a CA this app was shipped with
     * is exactly as trustworthy as one the unit already knew, because both are
     * public roots speaking for the same hosts - and the APK that comes back
     * still has to carry the pinned release certificate before anything is
     * installed.
     */
    internal class UnionTrust(
        private val system: X509TrustManager?,
        private val bundled: X509TrustManager,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            (system ?: bundled).checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val first = system
            if (first == null) {
                bundled.checkServerTrusted(chain, authType)
                return
            }
            try {
                first.checkServerTrusted(chain, authType)
            } catch (e: Exception) {
                // the one line that says why an update that used to fail now
                // works: an old store and a chain it cannot build
                Log.i(TAG, "the platform refused the update host's chain, trying the bundled anchors: $e")
                bundled.checkServerTrusted(chain, authType)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            (system?.acceptedIssuers ?: emptyArray()) + bundled.acceptedIssuers
    }
}
