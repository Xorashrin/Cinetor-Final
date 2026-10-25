package com.cinator.app.data

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.InetAddress

data class EmbeddedSource(
    val id: String,
    val name: String,
    val section: FeedSection,
    val engine: String,
    val endpoints: List<String>,
    val enabled: Boolean,
    val capabilityNote: String,
    val licenseNote: String? = null
)

data class EmbeddedCatalog(
    val sources: List<EmbeddedSource>,
    val schemaVersion: Int = 1,
    val bundleVersion: String = "0.3.0",
    val licenseNote: String? = null
)

object EmbeddedCatalogParser {
    fun parse(rawJson: String): EmbeddedCatalog {
        val root = JSONObject(rawJson)
        val schemaVersion = root.get("schemaVersion")
        require(schemaVersion is Int && schemaVersion == 1) { "Unsupported catalog schema" }
        val bundleVersion = root.getString("bundleVersion")
        require(bundleVersion.isNotBlank()) { "Missing bundle version" }
        val licenseNote = root.optString("licenseNote", "").takeIf { it.isNotBlank() }
        val sourcesArray = root.getJSONArray("sources")
        val sources = mutableListOf<EmbeddedSource>()
        val ids = mutableSetOf<String>()

        for (i in 0 until sourcesArray.length()) {
            val sObj = sourcesArray.getJSONObject(i)
            val id = sObj.getString("id")
            require(id.isNotBlank() && id == id.trim() && ids.add(id)) { "Empty or duplicate source id" }
            val name = sObj.getString("name")
            require(name.isNotBlank()) { "Empty source name" }
            val sectionStr = sObj.getString("section")
            val section = parseSection(sectionStr)
            val engine = sObj.getString("engine")
            val endpointsJson = sObj.getJSONArray("endpoints")
            val endpoints = mutableListOf<String>()
            if (endpointsJson != null) {
                for (j in 0 until endpointsJson.length()) {
                    val endpoint = endpointsJson.getString(j)
                    require(UrlPolicyValidator.isSafeHttpsUrl(endpoint)) { "Unsafe catalog endpoint" }
                    endpoints.add(endpoint)
                }
            }
            require(!sObj.has("enabled") || sObj.get("enabled") is Boolean) { "Invalid enabled flag" }
            val enabled = sObj.optBoolean("enabled", false)
            require(!enabled || engine in setOf("CINEMETA", "TVMAZE", "IPTV", "OPEN_VIDEO")) { "Unsupported enabled engine" }
            require(!enabled || endpoints.isNotEmpty()) { "Enabled source requires endpoints" }
            val capabilityNote = sObj.optString("capabilityNote", "")
            val sLicense = sObj.optString("licenseNote", "").takeIf { it.isNotBlank() }

            sources.add(
                EmbeddedSource(
                    id = id,
                    name = name,
                    section = section,
                    engine = engine,
                    endpoints = endpoints,
                    enabled = enabled,
                    capabilityNote = capabilityNote,
                    licenseNote = sLicense
                )
            )
        }

        return EmbeddedCatalog(
            sources = sources,
            schemaVersion = schemaVersion,
            bundleVersion = bundleVersion,
            licenseNote = licenseNote
        )
    }

    private fun parseSection(raw: String): FeedSection {
        return when (raw) {
            "HOME" -> FeedSection.HOME
            "ANIME" -> FeedSection.ANIME
            "KDRAMA" -> FeedSection.KDRAMA
            "LIVETV" -> FeedSection.LIVETV
            "ADULT" -> FeedSection.ADULT
            else -> throw IllegalArgumentException("Unknown catalog section")
        }
    }
}

interface TextHttpClient {
    fun get(url: String): String
}

class BoundedHttpClient(
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 12000,
    private val maxSizeBytes: Int = 2 * 1024 * 1024,
    private val maxRedirects: Int = 5,
    private val totalTimeoutMs: Int = 20000,
    private val resolveHost: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    // Trusted transport seam for tests; production always uses the configured OkHttp client.
    private val callFactory: (okhttp3.OkHttpClient) -> okhttp3.Call.Factory = { it }
) : TextHttpClient {

    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && totalTimeoutMs > 0 && maxSizeBytes > 0 && maxRedirects in 0..10)
    }

    private val transport = okhttp3.OkHttpClient.Builder()
        // A proxy could resolve the destination itself, bypassing the validated DNS results.
        .proxy(java.net.Proxy.NO_PROXY)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = resolveHost(hostname).toList()
                if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
                    throw SecurityException("Unsafe DNS result")
                }
                // These exact addresses become OkHttp's socket routes, not a preflight check.
                return addresses
            }
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(connectTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(totalTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        // Keep OkHttp's default certificate chain and hostname verification.
        .build()

    override fun get(url: String): String {
        validateUrl(url)
        val deadline = System.nanoTime() + totalTimeoutMs.toLong() * 1_000_000
        val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()
        val task = try {
            workers.submit<String> { fetchBounded(url, deadline, activeCall) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            throw IOException("Network worker capacity exhausted")
        }
        try {
            return task.get(remainingMs(deadline).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            throw java.net.SocketTimeoutException("Network request deadline exceeded")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Network request interrupted")
        } catch (e: java.util.concurrent.ExecutionException) {
            if (e.cause is SecurityException) throw SecurityException("Unsafe network target")
            // Do not attach underlying exceptions: providers may include credential-bearing URLs.
            throw IOException("Network request failed")
        } finally {
            activeCall.getAndSet(null)?.cancel()
            task.cancel(true)
            workers.remove(task as Runnable)
        }
    }

    private fun fetchBounded(
        initialUrl: String,
        deadline: Long,
        activeCall: java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>
    ): String {
        var currentUrl = initialUrl
        val factory = callFactory(transport)
        for (hop in 0..maxRedirects) {
            remainingMs(deadline)
            val uri = URI(validateUrl(currentUrl))
            val request = okhttp3.Request.Builder().url(currentUrl)
                .header("User-Agent", "Cinetor/0.3.0")
                .header("Accept", "application/json, text/plain, application/x-mpegurl")
                .header("Accept-Encoding", "identity")
                .build()
            val call = factory.newCall(request)
            activeCall.set(call)
            try {
                call.timeout().deadlineNanoTime(deadline)
                remainingMs(deadline)
                call.execute().use { response ->
                    remainingMs(deadline)
                    if (response.code in 300..399) {
                        if (hop == maxRedirects) throw IOException("Redirect budget exceeded")
                        val location = response.header("Location") ?: throw IOException("Missing redirect location")
                        currentUrl = uri.resolve(location).toString()
                        validateUrl(currentUrl)
                    } else {
                        if (response.code !in 200..299) throw IOException("HTTP request failed")
                        val body = response.body ?: throw IOException("Missing response body")
                        if (body.contentLength() > maxSizeBytes) throw IOException("Response size budget exceeded")
                        return body.byteStream().use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                remainingMs(deadline)
                                val read = input.read(buffer)
                                remainingMs(deadline)
                                if (read < 0) break
                                if (read > maxSizeBytes - out.size()) throw IOException("Response size budget exceeded")
                                out.write(buffer, 0, read)
                            }
                            out.toString(Charsets.UTF_8.name())
                        }
                    }
                }
            } finally {
                call.cancel()
                activeCall.compareAndSet(call, null)
            }
        }
        throw IOException("Redirect budget exceeded")
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val literal = address.hostAddress ?: return false
        val host = if (literal.contains(':')) "[$literal]" else literal
        return UrlPolicyValidator.isSafeHttpsUrl("https://$host/")
    }

    private fun remainingMs(deadline: Long): Int {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 || Thread.currentThread().isInterrupted) throw java.net.SocketTimeoutException("Network deadline exceeded")
        return ((remaining + 999_999) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        // A stuck platform DNS resolver cannot create unbounded threads or queues.
        private val workers = java.util.concurrent.ThreadPoolExecutor(
            4, 4, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(16),
            java.util.concurrent.ThreadFactory { work -> Thread(work, "catalog-http").apply { isDaemon = true } }
        ).apply { allowCoreThreadTimeOut(true) }
    }

    fun validateUrl(rawUrl: String): String {
        if (!UrlPolicyValidator.isSafeHttpsUrl(rawUrl)) throw SecurityException("Unsafe HTTPS target")
        return rawUrl
    }
}

data class CatalogLoadResult(
    val items: List<MediaCard>,
    val failures: List<String>,
    val sourceCount: Int
)
