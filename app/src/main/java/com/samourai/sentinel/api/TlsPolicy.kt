package com.samourai.sentinel.api

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

        private fun relax(socket: java.net.Socket?): Boolean =
            shouldRelax((socket as? SSLSocket)?.handshakeSession?.peerHost)

        private fun relax(engine: SSLEngine?): Boolean =
            shouldRelax(engine?.peerHost)

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
            if (relax(socket)) return
            delegate.checkServerTrusted(chain, authType)
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
            if (relax(engine)) return
            delegate.checkServerTrusted(chain, authType)
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
