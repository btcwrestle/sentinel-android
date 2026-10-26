package com.samourai.sentinel.api

import android.net.http.X509TrustManagerExtensions
import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * #229: host-scoped TLS policy.
 *
 * Before this, Tor mode installed a trust-all TrustManager plus a
 * hostname verifier that accepted everything - for EVERY host this
 * app talked to, not just .onion. Any malicious Tor exit could spoof
 * clearnet HTTPS (exchange rates above all, which flow into fiat-entered
 * send amounts).
 *
 * This policy keeps the platform's normal verification for every
 * clearnet host - over Tor too, which is exactly the point: an exit
 * node cannot forge a valid certificate. Relaxation applies ONLY to
 * .onion peers, and the decision is made per connection (the handshake
 * knows the peer host), never per client build - so a single shared
 * client can serve an onion Dojo and clearnet exchanges safely at once.
 *
 * Plain-http onion endpoints negotiate no TLS at all and are unaffected.
 *
 * Peer host null/absent (engine created without a peer) fails CLOSED:
 * strict default verification.
 *
 * The strict path MUST delegate through the hostname-aware platform
 * entry, never the host-blind one. This app ships a per-domain
 * network security config (res/xml/network_security_config.xml - the
 * .onion cleartext exception), and with per-domain configs Android's
 * default trust manager throws from its host-blind
 * checkServerTrusted(chain, authType):
 * "Domain specific configurations require that hostname aware
 * checkServerTrusted(X509Certificate[], String, String) is used".
 * #230 shipped the host-blind delegation, so every clearnet HTTPS
 * handshake over Tor died in the TLS handshake. The exchange-rate
 * fetches are the app's only regular clearnet HTTPS traffic, so fiat
 * starved silently. X509TrustManagerExtensions wraps the same
 * platform trust manager with the hostname-aware entry (public since
 * API 17; minSdk 24 covers it) and selects the per-domain trust
 * anchors correctly.
 */
object TlsPolicy {

    /**
     * True only for .onion hosts, case-insensitive. TLS is only ever
     * negotiated on https, so the scheme is implied by the call site.
     * Substring matches like URL.contains("onion") are forbidden here -
     * "https://example.com/onion" must never qualify (the old WebUtil bug).
     */
    fun shouldRelax(peerHost: String?): Boolean {
        if (peerHost == null) return false
        return peerHost.lowercase().endsWith(".onion")
    }

    /** The platform default trust manager (system + user CA store). */
    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Delegates everything to the system default, except .onion peers,
     * where self-signed/admin certs are tolerated (onion services get
     * their crypto from Tor itself; the certificate cannot add meaning).
     */
    class OnionScopedTrustManager : X509ExtendedTrustManager() {

        private val delegate: X509TrustManager = systemTrustManager()

        /**
         * Hostname-aware entry over [delegate] (see the object KDoc for
         * why the host-blind entry is unusable under this app's
         * per-domain network security config). The constructor only
         * accepts the platform's TrustManagerImpl; on anything else we
         * keep the host-blind fallback below - correct on installs
         * without per-domain configs, fail-closed on those with them.
         */
        private val hostnameAware: X509TrustManagerExtensions? = try {
            X509TrustManagerExtensions(delegate)
        } catch (e: IllegalArgumentException) {
            null
        }

        /**
         * One host string per connection feeds both the relax decision
         * and the strict verification below - a split between "the host
         * that relaxed" and "the host that got verified" would be a
         * wedge.
         */
        private fun peerHost(socket: java.net.Socket?): String? =
            (socket as? SSLSocket)?.handshakeSession?.peerHost

        private fun relax(socket: java.net.Socket?): Boolean =
            shouldRelax(peerHost(socket))

        private fun relax(engine: SSLEngine?): Boolean =
            shouldRelax(engine?.peerHost)

        /**
         * Strict verification for every non-onion peer: hostname-aware
         * while a peer host is known. The host-blind entry is only used
         * when there is no name to check against (fail closed under
         * per-domain configs, exactly as before) or when the platform
         * wrapper was not constructible.
         */
        private fun verifyStrict(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String,
            peerHost: String?
        ) {
            val aware = hostnameAware
            if (aware != null && peerHost != null) {
                aware.checkServerTrusted(chain, authType, peerHost)
            } else {
                delegate.checkServerTrusted(chain, authType)
            }
        }

        override fun checkClientTrusted(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String
        ) = delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String
        ) = delegate.checkServerTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
            delegate.acceptedIssuers

        override fun checkClientTrusted(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String,
            socket: java.net.Socket
        ) {
            if (relax(socket)) return
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String,
            socket: java.net.Socket
        ) {
            val host = peerHost(socket)
            if (shouldRelax(host)) return
            verifyStrict(chain, authType, host)
        }

        override fun checkClientTrusted(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String,
            engine: SSLEngine
        ) {
            if (relax(engine)) return
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(
            chain: Array<java.security.cert.X509Certificate>,
            authType: String,
            engine: SSLEngine
        ) {
            val host = engine?.peerHost
            if (shouldRelax(host)) return
            verifyStrict(chain, authType, host)
        }
    }

    /**
     * Onion hosts are accepted (their certificate is decorative); every
     * other host gets the platform's normal strict hostname verification.
     * Delegation goes through the public API
     * (HttpsURLConnection.getDefaultHostnameVerifier), NOT OkHttp
     * internals - the first draft used
     * okhttp3.internal.tls.OkHostnameVerifier, which does not resolve
     * against this project's OkHttp. A null session fails closed:
     * nothing to verify the name against means no.
     */
    val hostnameVerifier: HostnameVerifier = HostnameVerifier { host, session ->
        if (shouldRelax(host)) {
            true
        } else if (session == null) {
            false
        } else {
            platformHostnameVerifier.verify(host, session)
        }
    }

    private val platformHostnameVerifier: HostnameVerifier by lazy {
        HttpsURLConnection.getDefaultHostnameVerifier()
    }

    /** Installs the scoped policy on a client builder. */
    fun install(builder: OkHttpClient.Builder) {
        val trustManager = OnionScopedTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier(hostnameVerifier)
    }
}
