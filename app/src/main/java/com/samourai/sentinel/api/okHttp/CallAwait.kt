/**
 * Adopted from : https://github.com/gildor/kotlin-coroutines-okhttp
 */
@file:Suppress("RedundantVisibilityModifier")

package com.samourai.sentinel.api.okHttp


import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Suspend extension that allows suspend [Call] inside coroutine.
 *
 * [recordStack] enables track recording, so in case of exception stacktrace will contain call stacktrace, may be useful for debugging
 *      Not free! Creates exception on each request so disabled by default, but may be enabled using system properties:
 *
 *      ```
 *      System.setProperty(OKHTTP_STACK_RECORDER_PROPERTY, OKHTTP_STACK_RECORDER_ON)
 *      ```
 *      see [README.md](https://github.com/gildor/kotlin-coroutines-okhttp/blob/master/README.md#Debugging) with details about debugging using this feature
 *
 * @return Result of request or throw exception. The socket is already
 * released: see [releasingConnection]. Callers may still read the body.
 */


public suspend fun Call.await(recordStack: Boolean = isRecordStack): Response {
    val callStack = if (recordStack) {
        IOException().apply {
            // Remove unnecessary lines from stacktrace
            // This doesn't remove await$default, but better than nothing
            stackTrace = stackTrace.copyOfRange(1, stackTrace.size)
        }
    } else {
        null
    }
    // #293: release the connection before this returns. Discarded
    // responses (import xpub, non-2xx branches, a sibling request
    // abandoned by an early return) otherwise stay in the pool until
    // GC, and that GC path logs the raw Dojo URL (#304).
    val response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // Member resume(value, onCancellation): if the caller is
                // already cancelled, the response is not delivered and the
                // lambda closes it. A delivered response is released by
                // releasingConnection before await returns.
                continuation.resume(response) { _, _, _ ->
                    response.releaseConnectionQuietly()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                // Don't bother with resuming the continuation if it is already cancelled.
                if (continuation.isCancelled) return
                callStack?.initCause(e)
                continuation.resumeWithException(callStack ?: e)
            }
        })

        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (ex: Throwable) {
                //Ignore cancel exception
            }
        }
    }
    return response.releasingConnection()
}
