package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.SentinelTorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Fetches the community Dojo directory (Dojo Bay) over the app's embedded Tor proxy.
 * Callers are responsible for making sure Tor is bootstrapped first
 * (see [SentinelTorManager]) - fetchDirectory() itself only reads the current proxy.
 */
object CommunityDojoRepository {

    suspend fun fetchDirectory(maxWaitMs: Long = 120_000L): List<CommunityDojoNode> = withContext(Dispatchers.IO) {
        // Tor's STATE observer can report ON slightly before its LISTENERS
        // observer delivers the SOCKS proxy. On a cold bootstrap that window
        // surfaced as an instant "Tor is not connected" error on fresh
        // installs. Poll briefly for the proxy before giving up.
        val proxy = awaitProxy(maxWaitMs)
            ?: throw IllegalStateException("Tor is not connected")

        val client = OkHttpClient.Builder()
            .proxy(proxy)
            // Dojo Bay probes every listed node over Tor before answering, so the
            // request can take a while to build a response - same 120s allowance
            // used elsewhere in the app for onion requests (see ApiService).
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(DojoBayConstants.DIRECTORY_JSON_URL)
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string()
        if (!response.isSuccessful || body.isNullOrBlank()) {
            throw IllegalStateException("DojoBay.org returned HTTP ${response.code}")
        }

        val directory = fromJSON<CommunityDojoDirectory>(body)
            ?: throw IllegalStateException("Could not parse the DojoBay.org directory")

        directory.nodes.orEmpty()
    }

    /**
     * Polls briefly for the Tor SOCKS proxy with a bounded wait. STATE can
     * report ON before LISTENERS delivers the proxy object. Returns null
     * if the proxy never appears within [maxWaitMs] - caller reports it.
     */
    private suspend fun awaitProxy(maxWaitMs: Long): Proxy? {
        var waited = 0L
        var proxy = SentinelTorManager.getProxy()
        while (proxy == null && waited < maxWaitMs) {
            delay(250)
            waited += 250
            proxy = SentinelTorManager.getProxy()
        }
        return proxy
    }
}
