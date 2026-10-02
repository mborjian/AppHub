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

object GithubTls {

    private const val TAG = "AppHub"

    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"

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

    private fun systemTrust(): X509TrustManager? = try {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (t: Throwable) {
        null
    }

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

    internal class UnionTrust(
        private val system: X509TrustManager?,
        private val bundled: X509TrustManager,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            (system ?: bundled).checkClientTrusted(chain, authType)
        }

        // the platform store answers first; the bundled roots are only for chains it refuses (its trust store predates them)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val first = system
            if (first == null) {
                bundled.checkServerTrusted(chain, authType)
                return
            }
            try {
                first.checkServerTrusted(chain, authType)
            } catch (e: Exception) {
                Log.i(TAG, "the platform refused the update host's chain, trying the bundled anchors: $e")
                try {
                    bundled.checkServerTrusted(chain, authType)
                    Log.i(TAG, "the bundled anchors answered for the update host's chain")
                } catch (refused: Exception) {
                    Log.i(TAG, "the bundled anchors refused too: $refused")
                    throw refused
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            (system?.acceptedIssuers ?: emptyArray()) + bundled.acceptedIssuers
    }
}
