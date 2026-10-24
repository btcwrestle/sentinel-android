package com.samourai.sentinel.api.okHttp

import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * #293: OkHttp leases the connection until the network body is closed.
 * Dojo calls share [Call.await], and several of them never read the
 * body (a discarded `importXpub` response, a non-2xx branch, a sibling
 * wallet fetch abandoned when one pubkey fails). The pool then logs
 * the full URL at GC time (#304).
 *
 * Read the network body here and hand back an in-memory copy, so the
 * socket is released before any caller — success, HTTP error, or a
 * caller that ignores the body — can forget it. Callers still read
 * [Response.body] the way they did; closing the copy does not touch
 * the socket, which is already back in the pool.
 */
internal fun Response.releasingConnection(): Response {
    val network = body ?: return this
    val type = network.contentType()
    val bytes = try {
        network.bytes()
    } catch (e: Exception) {
        try {
            network.close()
        } catch (closeError: Exception) {
            e.addSuppressed(closeError)
        }
        throw e
    }
    return newBuilder().body(bytes.toResponseBody(type)).build()
}

/** Cancellation path: drop the socket, the caller will not read the body. */
internal fun Response.releaseConnectionQuietly() {
    try {
        body?.close()
    } catch (_: Exception) {
    }
}
