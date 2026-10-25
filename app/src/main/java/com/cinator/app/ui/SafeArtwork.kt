package com.cinator.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil.ImageLoader
import com.cinator.app.data.UrlPolicyValidator
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Process-wide artwork-only loader. Never shares provider cookies, headers or clients. */
object SafeArtwork {
    @Volatile private var instance: ImageLoader? = null

    fun imageLoader(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .okHttpClient { artworkHttpClient() }
            // Do not reuse Coil's default disk cache, which may contain unchecked downloads.
            .diskCache(null)
            .build().also { instance = it }
    }
}

@Composable
fun rememberSafeImageLoader(context: Context): ImageLoader {
    val applicationContext = context.applicationContext
    return remember(applicationContext) { SafeArtwork.imageLoader(applicationContext) }
}

internal fun artworkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY)
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            SafeArtworkPolicy.checkHost(hostname)
            // Return the checked InetAddress objects themselves: no second lookup by OkHttp.
            return SafeArtworkPolicy.checkedAddresses(hostname, Dns.SYSTEM.lookup(hostname))
        }
    })
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .addInterceptor(ArtworkTransport())
    .build()

private class ArtworkTransport : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var target = chain.request().url.toString()
        var redirects = 0
        while (true) {
            SafeArtworkPolicy.checkUrl(target)
            // Fresh GET + fixed headers deliberately discard all caller/provider credentials,
            // cookies, custom Host, Referer, request bodies and explicit Accept-Encoding.
            val request = Request.Builder().url(target).get()
                .headers(Headers.Builder().add("Accept", "image/*")
                    .add("User-Agent", "CinetorArtwork/0.3").build())
                .build()
            val response = chain.proceed(request)
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                target = response.use {
                    SafeArtworkPolicy.redirect(target, it.header("Location"), redirects)
                }
                redirects++
                continue
            }
            return response.use {
                val body = it.body ?: throw IOException("Artwork response has no body")
                // Application interceptor sees transparent gzip *after* decompression.
                // Drain completely before publishing, so oversized/truncated payloads never
                // reach Coil, even if a decoder would otherwise ignore trailing bytes.
                val bytes = SafeArtworkPolicy.readBody(body.byteStream(), body.contentLength())
                it.newBuilder().removeHeader("Content-Length")
                    .body(bytes.toResponseBody(body.contentType())).build()
            }
        }
    }
}

/** Pure policy helpers; keep transport decisions independently testable without Android. */
internal object SafeArtworkPolicy {
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_REDIRECTS = 4

    fun checkUrl(url: String) {
        if (!UrlPolicyValidator.isSafeHttpsUrl(url)) throw IOException("Unsafe artwork URL")
    }

    fun checkHost(host: String) {
        checkUrl("https://${if (':' in host) "[$host]" else host}/")
    }

    fun checkedAddresses(host: String, addresses: List<InetAddress>): List<InetAddress> {
        checkHost(host)
        if (addresses.isEmpty()) throw UnknownHostException("No artwork addresses")
        for (address in addresses) {
            val literal = address.hostAddress ?: throw UnknownHostException("Missing artwork address")
            try { checkHost(literal) } catch (_: IOException) {
                throw UnknownHostException("Non-public artwork address rejected")
            }
        }
        return addresses
    }

    fun redirect(current: String, location: String?, followed: Int): String {
        checkUrl(current)
        if (followed !in 0 until MAX_REDIRECTS) throw IOException("Too many artwork redirects")
        if (location.isNullOrBlank() || location != location.trim() || location.any { it.isISOControl() }) {
            throw IOException("Invalid artwork redirect")
        }
        val next = try { URI(current).resolve(URI(location)).toString() } catch (_: Exception) {
            throw IOException("Invalid artwork redirect")
        }
        checkUrl(next)
        return next
    }

    fun readBody(input: InputStream, contentLength: Long): ByteArray {
        if (contentLength > MAX_BYTES) throw IOException("Artwork exceeds 8 MiB")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_BYTES - total + 1).toInt())
            if (count == -1) break
            total += count
            if (total > MAX_BYTES) throw IOException("Artwork exceeds 8 MiB")
            output.write(buffer, 0, count)
        }
        if (contentLength >= 0 && total != contentLength) throw IOException("Truncated artwork")
        return output.toByteArray()
    }
}
