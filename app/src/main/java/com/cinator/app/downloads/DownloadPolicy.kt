package com.cinator.app.downloads

import com.cinator.app.data.UrlPolicyValidator
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.ProviderRegistry
import com.cinator.app.domain.SourceClass
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource

/** Pure authorization/integrity rules for the foreground-only direct MP4 downloader. */
object DownloadPolicy {
    const val MAX_BYTES = 512L * 1024 * 1024
    const val MAX_REDIRECTS = 5
    const val MAX_DURATION_MS = 30L * 60 * 1000
    const val PROGRESS_INTERVAL_MS = 250L

    fun redirectTarget(currentUrl: String, location: String?, redirectsFollowed: Int): String {
        require(redirectsFollowed in 0 until MAX_REDIRECTS) { "Too many redirects" }
        require(UrlPolicyValidator.isSafeHttpsUrl(currentUrl)) { "Unsafe download URL" }
        require(!location.isNullOrBlank() && location == location.trim()) { "Missing or invalid redirect location" }
        val resolved = java.net.URI(currentUrl).resolve(location).toString()
        require(UrlPolicyValidator.isSafeHttpsUrl(resolved)) { "Unsafe redirect target" }
        return resolved
    }

    fun checkedByteCount(downloaded: Long, count: Int, expected: Long): Long {
        require(expected in 24L..MAX_BYTES && downloaded in 0L..expected && count >= 0 &&
            count.toLong() <= expected - downloaded) { "Response exceeds declared size or byte budget" }
        return downloaded + count
    }

    /** A conservative ISO-BMFF ftyp sanity check, not a full decoder or authenticity check. */
    fun verifyComplete(downloaded: Long, expected: Long, prefix: ByteArray) {
        require(expected in 24L..MAX_BYTES && downloaded == expected) { "Incomplete MP4 response" }
        require(prefix.size >= 24 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp") { "Invalid MP4 file signature" }
        val boxSize = prefix.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
        // Extended-size/size-to-EOF ftyp variants are intentionally unsupported.
        require(boxSize in 24L..expected && boxSize % 4 == 0L) { "Invalid MP4 ftyp box" }
    }

    fun checkElapsed(elapsedMs: Long) {
        require(elapsedMs in 0 until MAX_DURATION_MS) { "Foreground download time budget exceeded" }
    }

    fun progressDue(elapsedSinceProgressMs: Long): Boolean = elapsedSinceProgressMs >= PROGRESS_INTERVAL_MS

    /** Length-prefix components; preserve original URL/query bytes (including signed tokens). */
    fun targetKey(card: MediaCard, stream: StreamSource): String {
        val identity = listOf("download-v2", card.section.name, card.providerId, card.id, card.contentId,
            stream.section?.name, stream.providerId, stream.contentId, stream.id, stream.url)
            .joinToString("") { value -> value?.let { "${it.length}:$it" } ?: "-1:" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** No persisted strong validator: always GET from zero, never Range/If-Range or append.
     * Unknown-length/chunked responses are deliberately unsupported: EOF alone cannot prove completeness.
     */
    fun responseLength(code: Int, mime: String?, length: String?, encoding: String?, range: String?): Long {
        require(code == 200 && range == null) { "Expected a complete HTTP 200 response (resume unsupported)" }
        require(mime?.substringBefore(';')?.trim().equals("video/mp4", ignoreCase = true)) { "Expected video/mp4" }
        require(encoding == null || encoding.trim().equals("identity", ignoreCase = true)) { "Encoded downloads are unsupported" }
        require(length != null && length.matches(Regex("[0-9]+"))) { "A valid Content-Length is required" }
        val bytes = length.toLongOrNull()
        require(bytes != null && bytes in 24L..MAX_BYTES) { "MP4 size is outside the foreground download budget" }
        return bytes
    }

    /** A UI hint only. [rejectionReason] must still authorize against the selected card. */
    fun canDownloadStream(stream: StreamSource): Boolean {
        val provider = ProviderRegistry.allProviders.firstOrNull { it.id == stream.providerId }
        return stream.isDownloadable && !stream.isLive && stream.protocol == StreamProtocol.MP4 &&
            stream.id.isNotBlank() && stream.providerId.isNotBlank() &&
            !stream.contentId.isNullOrBlank() && stream.section != null && stream.section != FeedSection.LIVETV &&
            // This downloader does not implement authenticated/custom-header transport.
            stream.headers.isEmpty() && UrlPolicyValidator.isSafeHttpsUrl(stream.url) &&
            (provider == null || (provider.canDownload && provider.section == stream.section &&
                provider.sourceClass != SourceClass.EXTERNAL_LINK_ONLY))
    }

    fun rejectionReason(card: MediaCard, stream: StreamSource): String? = when {
        !canDownloadStream(stream) -> "Download not permitted or unsupported source"
        card.id.isBlank() || card.contentId.isNullOrBlank() -> "Missing media identity"
        card.section == FeedSection.LIVETV || card.kind == MediaKind.LIVE_TV -> "Live downloads are disabled"
        card.section != stream.section || card.contentId != stream.contentId ||
            card.providerId != stream.providerId -> "Source does not belong to this media"
        // Full value equality prevents a caller swapping the URL/flags under an existing id.
        card.streams.count { it.id == stream.id } != 1 || stream !in card.streams ->
            "Source is not uniquely declared on this media"
        else -> null
    }
}
