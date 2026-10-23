package com.samourai.sentinel.api

import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.api.okHttp.releaseConnectionQuietly
import com.samourai.sentinel.util.OkHttpLogRedaction
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * #293 / #304. The close tests run real calls through [await] (the Dojo
 * choke point: getWallet, getTx, getFees, import, broadcast) against
 * MockWebServer and read the connection pool. The redaction tests run
 * OkHttp's own `Platform.logCloseableLeak` — the method
 * `RealConnectionPool` calls — and a leaked call the pool itself
 * reports, and read the JUL record the Android bridge would print.
 */
class DojoResponseReleaseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success body stays readable and the socket is already idle`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val client = client()
        try {
            val response = runBlocking {
                client.newCall(request("/wallet")).await()
            }
            // Idle before the caller reads. Discarding the response must
            // not be what releases the Dojo connection.
            assertNoLeasedConnections(client)
            assertTrue(client.connectionPool.connectionCount() >= 1)
            assertEquals("""{"status":"ok"}""", response.body!!.string())
            assertNoLeasedConnections(client)
        } finally {
            client.release()
        }
    }

    @Test
    fun `http error body can be discarded without leasing the socket`() {
        // The shape of the entropy fetcher, fee fetch, and tx-hex calls:
        // non-2xx skips body.string(). The socket still has to come back.
        server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
        val client = client()
        try {
            val response = runBlocking {
                client.newCall(request("/auth/login")).await()
            }
            val parsed = if (response.isSuccessful) {
                response.body?.string()
            } else {
                null
            }
            assertEquals(null, parsed)
            assertEquals(401, response.code)
            assertNoLeasedConnections(client)
            assertTrue(client.connectionPool.connectionCount() >= 1)
            // The copy is still there if a caller does read the error.
            assertEquals("denied", response.body!!.string())
        } finally {
            client.release()
        }
    }

    @Test
    fun `a truncated body releases the socket before the error escapes`() {
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(64 * 1024))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        try {
            try {
                runBlocking { client.newCall(request("/tx/abc")).await() }
                fail("truncated body should fail the call")
            } catch (e: IOException) {
                // The read failed inside await, after the socket was released.
            }
            assertNoLeasedConnections(client)
        } finally {
            client.release()
        }
    }

    @Test
    fun `an unread execute stays leased until the body is closed`() {
        // Proves the pool assertion can see a leak. await() is what
        // changed; a raw execute that skips the body still holds the socket.
        server.enqueue(MockResponse().setBody("held"))
        val client = client()
        try {
            val response = client.newCall(request("/wallet")).execute()
            assertTrue(
                "unread body must lease the connection",
                client.connectionPool.connectionCount() > client.connectionPool.idleConnectionCount(),
            )
            response.close()
            assertNoLeasedConnections(client)
        } finally {
            client.release()
        }
    }

    @Test
    fun `quiet close releases an unread error body`() {
        // The cancellation hook on Call.await. Same close a non-2xx
        // response needs when the caller will never read it.
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        val client = client()
        try {
            val response = client.newCall(request("/fees")).execute()
            assertTrue(
                client.connectionPool.connectionCount() > client.connectionPool.idleConnectionCount(),
            )
            response.releaseConnectionQuietly()
            assertNoLeasedConnections(client)
            assertTrue(client.connectionPool.connectionCount() >= 1)
        } finally {
            client.release()
        }
    }

    @Test
    fun `platform leak warning redacts the onion and other url secrets`() {
        val xpub = "xpub" + "a".repeat(107)
        val message = "A connection to http://$ONION/?active=$xpub&at=sekrit was leaked. " +
            "Did you forget to close a response body?"
        val thrown = java.net.UnknownHostException("Unable to resolve host \"$ONION\"")
        val lines = captureOkHttpLogs {
            logCloseableLeak(message)
            platformLog(
                "Callback failure for http://$ONION/pushtx/?at=sekrit",
                thrown,
            )
        }
        assertTrue("logger must deliver the warning", lines.any { it.contains("was leaked") })
        lines.forEach { line ->
            assertFalse("raw onion in: $line", line.contains(ONION))
            assertFalse("raw .onion in: $line", line.contains(".onion"))
            assertFalse("raw xpub in: $line", line.contains(xpub))
            assertFalse("raw at token in: $line", line.contains("at=sekrit"))
        }
        assertTrue(lines.any { it.contains("ONION_REDACTED") && it.contains("was leaked") })
        assertTrue(lines.any { it.contains("XPUB_REDACTED") })
        assertTrue(lines.any { it.contains("at=REDACTED") })
        assertTrue(
            "the warning itself stays",
            lines.any { it.contains("Did you forget to close a response body?") },
        )
        assertTrue(
            "exception type stays diagnostic",
            lines.any { it.contains("UnknownHostException") },
        )
    }

    @Test
    fun `connection pool leak warning is redacted`() {
        server.enqueue(MockResponse().setBody("leaked-on-purpose"))
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        val lines = captureOkHttpLogs {
            try {
                val leaked = openWithoutClosing(client, onionRequest())
                awaitCollected(leaked)
                forcePoolCleanup(client)
            } finally {
                client.release()
            }
        }
        val warnings = lines.filter { it.contains("was leaked") }
        assertTrue(
            "pool should report the leaked call so the redaction is on the real path. saw: $lines",
            warnings.isNotEmpty(),
        )
        warnings.forEach { line ->
            assertFalse("raw onion in pool warning: $line", line.contains(ONION))
            assertFalse("raw .onion in pool warning: $line", line.contains(".onion"))
            assertTrue(line.contains("ONION_REDACTED"))
            assertTrue(line.contains("Did you forget to close a response body?"))
        }
    }

    private fun client(): OkHttpClient = OkHttpClient()

    private fun request(path: String): Request =
        Request.Builder().url(server.url(path)).build()

    private fun onionRequest(): Request {
        val url = server.url("/").newBuilder().host(ONION).build()
        return Request.Builder().url(url).build()
    }

    private fun assertNoLeasedConnections(client: OkHttpClient) {
        val pool = client.connectionPool
        assertEquals(
            "leased connections: total=${pool.connectionCount()} idle=${pool.idleConnectionCount()}",
            pool.connectionCount(),
            pool.idleConnectionCount(),
        )
    }

    /** Separate method so the unread response is not kept alive by the caller frame. */
    private fun openWithoutClosing(client: OkHttpClient, request: Request): WeakReference<Response> {
        val response = client.newCall(request).execute()
        checkNotNull(response.body)
        return WeakReference(response)
    }

    private fun awaitCollected(ref: WeakReference<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (ref.get() != null) {
            if (System.nanoTime() > deadline) {
                fail("leaked response was still reachable; the pool will not report it")
            }
            check(ByteArray(256 * 1024).isNotEmpty())
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        }
    }

    private fun forcePoolCleanup(client: OkHttpClient) {
        val pool = client.connectionPool
        val getter = pool.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 0 && method.returnType.name.contains("RealConnectionPool")
        } ?: error("ConnectionPool has no delegate getter: ${pool.javaClass.methods.map { it.name }}")
        getter.isAccessible = true
        val delegate = getter.invoke(pool)
        val cleanup = delegate.javaClass.methods.firstOrNull { method ->
            method.name == "cleanup" && method.parameterCount == 1
        } ?: error("RealConnectionPool.cleanup missing: ${delegate.javaClass.methods.map { it.name }}")
        cleanup.invoke(delegate, System.nanoTime())
    }

    /**
     * Records what the `okhttp3.OkHttpClient` JUL logger actually publishes.
     * That is the record AndroidLog forwards to logcat as `okhttp.OkHttpClient`.
     */
    private fun captureOkHttpLogs(block: () -> Unit): List<String> {
        OkHttpLogRedaction.install()
        val logger = Logger.getLogger(OkHttpClient::class.java.name)
        val previousLevel = logger.level
        val previousUseParent = logger.useParentHandlers
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.loggerName != OkHttpClient::class.java.name) return
                val thrown = record.thrown?.stackTraceToString().orEmpty()
                lines += record.message.orEmpty() + "\n" + thrown
            }

            override fun flush() {}
            override fun close() {}
        }
        logger.level = Level.ALL
        logger.useParentHandlers = false
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousUseParent
        }
        return lines.toList()
    }

    private fun platform(): Any {
        val cls = Class.forName("okhttp3.internal.platform.Platform")
        val get = cls.getDeclaredMethod("get")
        get.isAccessible = true
        return checkNotNull(get.invoke(null))
    }

    private fun logCloseableLeak(message: String) {
        val platform = platform()
        val method = platform.javaClass.methods.first { method ->
            method.name == "logCloseableLeak" && method.parameterCount == 2
        }
        method.invoke(platform, message, null)
    }

    /** Platform.WARN is 5. Same logger.log call logCloseableLeak makes. */
    private fun platformLog(message: String, thrown: Throwable) {
        val platform = platform()
        val method = platform.javaClass.methods.first { method ->
            method.name == "log" && method.parameterCount == 3
        }
        method.invoke(platform, message, 5, thrown)
    }

    private fun OkHttpClient.release() {
        dispatcher.executorService.shutdown()
        connectionPool.evictAll()
    }

    companion object {
        /** 56-char v3 onion. Matches the host [com.samourai.sentinel.util.LogRedaction] masks. */
        private const val ONION =
            "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion"
    }
}
