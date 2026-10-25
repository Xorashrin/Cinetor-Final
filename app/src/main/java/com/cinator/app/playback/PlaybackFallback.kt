package com.cinator.app.playback

import com.cinator.app.data.UrlPolicyValidator
import com.cinator.app.domain.StreamSource
import java.net.URI
import java.util.Locale
import java.io.File
import java.net.InetAddress

fun playbackRedirectUrl(baseUrl: String, location: String): String? = runCatching {
    if (!UrlPolicyValidator.isSafeHttpsUrl(baseUrl) || location.isBlank() || location != location.trim() ||
        location.any { it.isISOControl() }) return null
    URI(baseUrl).resolve(URI(location)).toString().takeIf(UrlPolicyValidator::isSafeHttpsUrl)
}.getOrNull()

/** Reuse the public-address policy after DNS, before opening a socket. */
fun isPublicPlaybackAddress(address: InetAddress): Boolean {
    val literal = address.hostAddress ?: return false
    val authority = if (literal.contains(':')) "[$literal]" else literal
    return UrlPolicyValidator.isSafeHttpsUrl("https://$authority/")
}

private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private val reservedHeaders = setOf(
    "host", "connection", "content-length", "transfer-encoding", "range", "accept-encoding",
    "proxy-authorization", "proxy-connection", "upgrade", "te", "trailer"
)

fun safePlaybackHeaders(headers: Map<String, String>): Boolean =
    headers.size <= 24 && headers.entries.sumOf { it.key.length + it.value.length } <= 16_384 &&
        headers.keys.map { it.lowercase(Locale.ROOT) }.distinct().size == headers.size &&
        headers.all { (name, value) ->
            name.length in 1..128 && headerName.matches(name) &&
                name.lowercase(Locale.ROOT) !in reservedHeaders && value.length <= 4096 &&
                value.all { it.code in 0x20..0x7e }
        }

/** Keep user intent separate from isPlaying and lifecycle-induced pauses. */
class PlaybackIntent {
    private var requested = true
    private var stopped = false
    val shouldPlay: Boolean get() = requested && !stopped

    fun toggle(playWhenReady: Boolean) { requested = !playWhenReady }
    fun observePlayWhenReady(playWhenReady: Boolean) {
        if (!stopped) requested = playWhenReady
    }
    fun onStop() { stopped = true }
    fun onStart() { stopped = false }
}

/** Accept file paths, not arbitrary content/file URIs or paths escaping via symlinks. */
fun localPlaybackFile(downloadDirectory: File, path: String): File? = runCatching {
    if (path.isBlank() || path.any { it.isISOControl() }) return null
    val requested = File(path)
    if (!requested.isAbsolute) return null
    val root = downloadDirectory.canonicalFile
    val file = requested.canonicalFile
    file.takeIf { root.isDirectory && it.isFile && it.canRead() && it.toPath().startsWith(root.toPath()) }
}.getOrNull()

/** Unknown identities and live windows must never share a saved VOD position. */
fun playbackResumeKey(stream: StreamSource): String? {
    val content = stream.contentId?.takeIf { it.isNotBlank() } ?: return null
    val section = stream.section ?: return null
    if (stream.isLive) return null
    return "resume:v2:${section.name}:${content.length}:$content"
}

/** A single playback session; [maxAttempts] includes the initial source. */
class PlaybackFallback(
    primary: StreamSource,
    fallbackSources: List<StreamSource> = emptyList(),
    maxAttempts: Int = MAX_ATTEMPTS
) {
    init { require(maxAttempts > 0) { "At least one source attempt is required" } }

    private val candidates = (sequenceOf(primary) + fallbackSources.asSequence().filter {
        !primary.contentId.isNullOrBlank() && primary.section != null &&
            it.contentId == primary.contentId && it.section == primary.section && it.isLive == primary.isLive
    }).filter { UrlPolicyValidator.isSafeHttpsUrl(it.url) && safePlaybackHeaders(it.headers) }
        .distinctBy { urlIdentity(it.url) }.take(maxAttempts.coerceAtMost(MAX_ATTEMPTS)).toList()
    private var cursor = 0

    fun next(): StreamSource? = candidates.getOrNull(cursor)?.also { cursor++ }

    companion object {
        const val MAX_ATTEMPTS = 3

        // This is a deduplication key only; never rewrite a signed playback URL.
        private fun urlIdentity(url: String): String {
            val uri = URI(url)
            return "https://${uri.host.lowercase(Locale.ROOT)}" +
                uri.rawPath.orEmpty().ifEmpty { "/" } +
                (uri.rawQuery?.let { "?$it" } ?: "")
        }
    }
}
