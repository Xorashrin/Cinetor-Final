package com.cinator.app

import com.cinator.app.data.BoundedHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Timeout
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

class BackendNetworkingRegressionTest {
    private val publicAddress = InetAddress.getByName("8.8.8.8")

    private class FixtureCall(
        private val request: Request,
        private val client: OkHttpClient,
        private val respond: (Request) -> Response
    ) : Call {
        @Volatile var cancelled = false
        private var executed = false
        private val budget = Timeout()
        override fun request() = request
        override fun timeout() = budget
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun cancel() { cancelled = true }
        override fun clone(): Call = FixtureCall(request, client, respond)
        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()
        override fun execute(): Response {
            executed = true
            budget.throwIfReached()
            client.dns.lookup(request.url.host)
            budget.throwIfReached()
            return respond(request)
        }
    }

    private fun fixtures(respond: (Request) -> Response): (OkHttpClient) -> Call.Factory = { client ->
        Call.Factory { request -> FixtureCall(request, client, respond) }
    }

    private fun response(request: Request, body: InputStream = "ok".byteInputStream(), status: Int = 200,
                         location: String? = null, length: Long = -1): Response {
        val responseBody = object : ResponseBody() {
            private val buffered = body.source().buffer()
            override fun contentType() = null
            override fun contentLength() = length
            override fun source() = buffered
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            .body(responseBody).apply { if (location != null) header("Location", location) }.build()
    }

    /** Real OkHttp routing, stopped immediately before any network traffic. */
    private class RecordingSockets(private val connect: (InetSocketAddress) -> Unit) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                this@RecordingSockets.connect(endpoint as InetSocketAddress)
                throw IOException("Stopped before network I/O")
            }
        }
        override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
    }

    @Test fun transportUsesTheValidatedDnsAnswerInsteadOfResolvingAgain() {
        var lookups = 0
        val resolver: (String) -> List<InetAddress> = {
            listOf(if (++lookups == 1) publicAddress else InetAddress.getByName("127.0.0.1"))
        }
        var connectedAddress: InetAddress? = null
        val client = BoundedHttpClient(resolveHost = resolver, callFactory = { configured ->
            configured.newBuilder().socketFactory(RecordingSockets { connectedAddress = it.address }).build()
        })
        try { client.get("https://public.example.com/"); fail("Socket fixture should stop transport") } catch (_: IOException) { }
        assertEquals(publicAddress, connectedAddress)
        assertEquals(1, lookups)
    }

    @Test fun resolvedPrivateAddressesAreRejectedBeforeOpeningConnection() {
        var opens = 0
        val client = BoundedHttpClient(resolveHost = { listOf(publicAddress, InetAddress.getByName("127.0.0.1")) },
            callFactory = { configured -> configured.newBuilder().socketFactory(RecordingSockets { opens++ }).build() })
        try { client.get("https://public.example.com/"); fail("Private DNS result accepted") } catch (_: SecurityException) { }
        assertEquals(0, opens)
    }

    @Test fun transportKeepsDefaultTlsAndCannotFollowRedirectsOrUseProxyDns() {
        val defaults = OkHttpClient()
        val client = BoundedHttpClient(resolveHost = { listOf(publicAddress) }, callFactory = { configured ->
            assertSame(defaults.hostnameVerifier, configured.hostnameVerifier)
            assertEquals(defaults.sslSocketFactory.javaClass, configured.sslSocketFactory.javaClass)
            assertNotNull(configured.x509TrustManager)
            assertFalse(configured.followRedirects)
            assertFalse(configured.followSslRedirects)
            assertFalse(configured.retryOnConnectionFailure)
            assertEquals(java.net.Proxy.NO_PROXY, configured.proxy)
            assertEquals(8000, configured.connectTimeoutMillis)
            assertEquals(12000, configured.readTimeoutMillis)
            assertEquals(20000, configured.callTimeoutMillis)
            fixtures { request ->
                assertEquals("public.example.com", request.url.host)
                response(request)
            }(configured)
        })
        assertEquals("ok", client.get("https://public.example.com/"))
    }

    @Test fun absoluteDeadlineStopsTrickleReadsAndSlowDns() {
        val trickle = object : InputStream() {
            var reads = 0
            override fun read(): Int { Thread.sleep(30); return if (++reads <= 15) 65 else -1 }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                val value = read(); if (value < 0) return -1; buffer[off] = value.toByte(); return 1
            }
        }
        var active: FixtureCall? = null
        val client = BoundedHttpClient(totalTimeoutMs = 100, resolveHost = { listOf(publicAddress) },
            callFactory = { configured -> Call.Factory { request ->
                FixtureCall(request, configured) { response(it, trickle) }.also { active = it }
            } })
        val start = System.nanoTime()
        try { client.get("https://public.example.com/"); fail("Trickle exceeded total deadline") } catch (_: IOException) { }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 500)
        assertTrue(active!!.cancelled)
        val dns = BoundedHttpClient(totalTimeoutMs = 100, resolveHost = { Thread.sleep(1000); listOf(publicAddress) })
        val dnsStart = System.nanoTime()
        try { dns.get("https://public.example.com/"); fail("DNS exceeded total deadline") } catch (_: IOException) { }
        assertTrue((System.nanoTime() - dnsStart) / 1_000_000 < 500)
    }

    @Test fun byteBudgetRedirectPolicyAndTransportErrorsAreBoundedAndSanitized() {
        val oversized = BoundedHttpClient(maxSizeBytes = 3, resolveHost = { listOf(publicAddress) },
            callFactory = fixtures { response(it, "1234".byteInputStream()) })
        try { oversized.get("https://public.example.com/?token=secret"); fail("Size budget ignored") }
        catch (e: IOException) { assertFalse(e.stackTraceToString().contains("secret")) }
        var opens = 0
        val redirect = BoundedHttpClient(resolveHost = { listOf(publicAddress) },
            callFactory = fixtures { opens++; response(it, status = 302, location = "https://[::1]/?token=secret") })
        try { redirect.get("https://public.example.com/"); fail("Unsafe redirect accepted") }
        catch (e: SecurityException) { assertFalse(e.stackTraceToString().contains("secret")) }
        assertEquals(1, opens)
        val broken = BoundedHttpClient(resolveHost = { listOf(publicAddress) },
            callFactory = fixtures { throw IOException("https://password:secret@example.com") })
        try { broken.get("https://public.example.com/"); fail("Expected transport failure") }
        catch (e: IOException) { assertFalse(e.stackTraceToString().contains("secret")) }
    }

    @Test fun redirectsRevalidateDnsAndShareTheOriginalDeadline() {
        var replies = 0
        val lookedUp = mutableListOf<String>()
        val privateRedirect = BoundedHttpClient(resolveHost = { host ->
            lookedUp.add(host)
            listOf(if (host == "public.example.com") publicAddress else InetAddress.getByName("10.0.0.1"))
        }, callFactory = fixtures { replies++; response(it, status = 302, location = "https://other.example.com/") })
        try { privateRedirect.get("https://public.example.com/"); fail("Private redirect DNS accepted") } catch (_: SecurityException) { }
        assertEquals(listOf("public.example.com", "other.example.com"), lookedUp)
        assertEquals(1, replies)

        val deadlines = mutableListOf<Long>()
        val redirect = BoundedHttpClient(resolveHost = { listOf(publicAddress) }, callFactory = { configured ->
            Call.Factory { request ->
                lateinit var call: FixtureCall
                call = FixtureCall(request, configured) {
                    deadlines.add(call.timeout().deadlineNanoTime())
                    if (it.url.encodedPath == "/") response(it, status = 302, location = "/final") else response(it)
                }
                call
            }
        })
        assertEquals("ok", redirect.get("https://public.example.com/"))
        assertEquals(2, deadlines.size)
        assertEquals(deadlines.first(), deadlines.last())
    }

    @Test fun declaredSizeAndRedirectBudgetsCloseResponseBodies() {
        var closed = false
        val unreadable = object : InputStream() {
            override fun read(): Int = throw AssertionError("Declared oversized body must not be read")
            override fun close() { closed = true }
        }
        val oversized = BoundedHttpClient(maxSizeBytes = 3, resolveHost = { listOf(publicAddress) },
            callFactory = fixtures { response(it, unreadable, length = 4) })
        try { oversized.get("https://public.example.com/"); fail("Declared size ignored") } catch (_: IOException) { }
        assertTrue(closed)
        var hops = 0
        val loop = BoundedHttpClient(maxRedirects = 2, resolveHost = { listOf(publicAddress) },
            callFactory = fixtures { hops++; response(it, status = 302, location = "/loop") })
        try { loop.get("https://public.example.com/"); fail("Redirect budget ignored") } catch (_: IOException) { }
        assertEquals(3, hops)
    }

    @Test fun urlPolicyRejectsIpv6AliasesUnexpectedPortsAndNeverLeaksCredentials() {
        val client = BoundedHttpClient()
        for (url in listOf("https://[::1]/", "https://[fd00::1]/", "https://[fe80::1]/", "https://sub.localhost/",
            "https://localhost./", "https://example.com:8443/", "https://example.com/a%0ab", " https://example.com/")) {
            try { client.validateUrl(url); fail("Unsafe target was accepted") } catch (_: SecurityException) { }
        }
        try { client.get("https://user:secret@example.com/path?token=private"); fail("Credentials accepted") }
        catch (e: Exception) {
            assertFalse(e.stackTraceToString().contains("secret"))
            assertFalse(e.stackTraceToString().contains("private"))
        }
    }
}
