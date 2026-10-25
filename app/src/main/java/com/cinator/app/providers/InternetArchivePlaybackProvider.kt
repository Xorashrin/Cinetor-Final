package com.cinator.app.providers

import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Independent public metadata API adapter, not an extension/plugin runtime.
 * The host supplies audited item identifiers, never user URLs or unrestricted searches.
 * Production must inject BoundedHttpClient for socket deadlines, byte limits, DNS and
 * redirect validation. TextHttpClient cannot enforce those transport policies itself.
 * A metadata declaration is not an Android playback/file-byte verification claim.
 */
class InternetArchivePlaybackProvider(
    info: ProviderInfo,
    allowlistedIdentifiers: Set<String>,
    private val http: TextHttpClient
) : PlaybackProvider {
    override val info: ProviderInfo
    private val identifiers: Set<String>

    init {
        interrupted()
        require(info.section == FeedSection.HOME) { "Archive movies belong only in Home" }
        require(info.id.isNotBlank() && info.id.length <= 128 && info.id.none { it.isISOControl() }) {
            "Invalid provider identity"
        }
        require(info.name.isNotBlank() && info.name.length <= 256 && info.name.none { it.isISOControl() }) {
            "Invalid provider name"
        }
        require(allowlistedIdentifiers.isNotEmpty() && allowlistedIdentifiers.size <= MAX_ITEMS) {
            "Archive allowlist must contain 1..$MAX_ITEMS reviewed identifiers"
        }
        allowlistedIdentifiers.forEach { require(safeSegment(it, 255)) { "Invalid Archive identifier" } }
        identifiers = allowlistedIdentifiers.toSortedSet().toSet()
        this.info = info.copy(capabilities = info.capabilities.copy(episodes = false,
            download = info.capabilities.download && info.capabilities.playback))
    }

    override fun catalog(query: String): List<MediaCard> {
        interrupted()
        require(query.length <= 1024 && query.none { it.isISOControl() }) { "Invalid Archive query" }
        if (!info.enabled || !info.capabilities.search) return emptyList()
        val needle = query.trim()
        return identifiers.mapNotNull { identifier ->
            interrupted()
            val data = load(identifier)
            val card = card(identifier, data) ?: return@mapNotNull null
            if (needle.isEmpty() || listOf(card.title, card.synopsis, card.year.orEmpty(),
                    card.genres.joinToString(" ")).any { it.contains(needle, ignoreCase = true) }) card else null
        }
    }

    override fun details(card: MediaCard): ProviderDetails {
        val identifier = selected(card)
        if (!info.enabled || !info.capabilities.details) return ProviderDetails(card.copy(streams = emptyList()))
        val current = requireNotNull(card(identifier, load(identifier))) { "Archive item no longer eligible" }
        return ProviderDetails(current)
    }

    override fun resolve(request: ResolveRequest): List<StreamSource> {
        val identifier = selected(request.card)
        require(request.episode == null) { "Archive movies have no episodes" }
        if (!info.enabled || !info.capabilities.playback) return emptyList()
        // Never trust embedded card.streams or cached rights/file declarations.
        val data = load(identifier)
        val current = card(identifier, data) ?: return emptyList()
        val files = data.optJSONArray("files") ?: return emptyList()
        require(files.length() <= MAX_FILES) { "Archive file count exceeds budget" }
        val named = linkedMapOf<String, JSONObject>()
        val duplicateNames = mutableSetOf<String>()
        for (index in 0 until files.length()) {
            interrupted()
            val file = files.optJSONObject(index) ?: continue
            val name = file.opt("name") as? String ?: continue
            if (!safeSegment(name, 512)) continue
            if (named.putIfAbsent(name, file) != null) duplicateNames += name
        }
        duplicateNames.forEach(named::remove)
        return named.entries.mapNotNull { (name, file) ->
            interrupted()
            if (!name.lowercase(Locale.ROOT).endsWith(".mp4") || forbidden(file) ||
                (file.opt("format") as? String)?.lowercase(Locale.ROOT) !in MP4_FORMATS ||
                size(file) !in 1..MAX_FILE_BYTES) return@mapNotNull null
            val duration = seconds(file.opt("length")) ?: return@mapNotNull null
            when (file.opt("source")) {
                "original" -> Unit
                "derivative" -> {
                    val parentName = file.opt("original") as? String ?: return@mapNotNull null
                    val parent = named[parentName] ?: return@mapNotNull null
                    if (parent.opt("source") != "original" || forbidden(parent) || size(parent) <= 0 ||
                        (parent.opt("format") as? String)?.lowercase(Locale.ROOT) !in VIDEO_FORMATS) return@mapNotNull null
                    val parentDuration = seconds(parent.opt("length")) ?: return@mapNotNull null
                    if (kotlin.math.abs(duration - parentDuration) > 2.0) return@mapNotNull null
                }
                else -> return@mapNotNull null
            }
            val height = integer(file.opt("height"))?.takeIf { it in 144..2160 }
            StreamSource(id = "${current.contentId}:${encode(name)}", label = "${current.title} • $name",
                url = "$ORIGIN/download/${encode(identifier)}/${encode(name)}", protocol = StreamProtocol.MP4,
                quality = height?.let { "${it}p" } ?: "Unspecified",
                isDownloadable = info.capabilities.download,
                providerId = info.id, contentId = current.contentId, section = FeedSection.HOME)
        }.take(MAX_STREAMS)
    }

    private fun selected(card: MediaCard): String {
        interrupted()
        require(card.providerId == info.id && card.section == FeedSection.HOME && card.kind == MediaKind.MOVIE &&
            card.id.startsWith(NAMESPACE) && card.contentId == card.id) { "Foreign Archive movie identity" }
        return card.id.removePrefix(NAMESPACE).also {
            require(it in identifiers) { "Archive item was not reviewed" }
        }
    }

    private fun load(identifier: String): JSONObject {
        interrupted()
        val url = "$ORIGIN/metadata/${encode(identifier)}"
        var body: String? = null
        for (attempt in 0 until 2) {
            interrupted()
            try {
                body = http.get(url)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Archive request interrupted")
            } catch (e: IOException) {
                interrupted()
                if (e is InterruptedIOException && e !is SocketTimeoutException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Archive request interrupted")
                }
                if (attempt == 1) throw IOException("Archive metadata API unavailable")
            }
        }
        interrupted()
        val raw = requireNotNull(body) { "Missing Archive response" }
        require(raw.length <= MAX_RESPONSE_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "Archive metadata exceeds size budget"
        }
        requireBoundedJsonDepth(raw)
        val data = try { JSONObject(raw) } catch (_: org.json.JSONException) {
            throw IllegalArgumentException("Malformed Archive metadata")
        }
        val meta = data.optJSONObject("metadata")
        require(meta?.opt("identifier") == identifier) { "Archive metadata identity mismatch" }
        require((data.optJSONArray("files")?.length() ?: 0) <= MAX_FILES) { "Archive file count exceeds budget" }
        interrupted()
        return data
    }

    private fun card(identifier: String, data: JSONObject): MediaCard? {
        interrupted()
        val meta = data.getJSONObject("metadata")
        if (meta.opt("mediatype") != "movies" || restricted(meta) || restricted(data)) return null
        val license = acceptedLicense(meta.opt("licenseurl")) ?: return null
        val title = textValues(meta.opt("title"), 1).singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val subjects = textValues(meta.opt("subject"), 256).flatMap { it.split(';') }.map { it.trim() }
        val classifications = listOf(title) + subjects + textValues(meta.opt("collection"), 256) +
            textValues(meta.opt("genre"), 256)
        if (classifications.any { EXCLUDED.containsMatchIn(it) }) return null
        val description = textValues(meta.opt("description"), 16).joinToString("\n")
        val creator = textValues(meta.opt("creator"), 64).joinToString("; ")
        val identity = "$NAMESPACE$identifier"
        return MediaCard(id = identity, contentId = identity, title = title, subtitle = info.name,
            synopsis = "$description\n\nTitle: $title\nCreator: ${creator.ifBlank { "Not supplied" }}\n" +
                "Source: $ORIGIN/details/${encode(identifier)}\nLicense: $license\n" +
                "Preserve source attribution and credits when sharing CC BY media.",
            section = FeedSection.HOME, providerId = info.id, providerName = info.name,
            year = textValues(meta.opt("date"), 1).singleOrNull()?.let { YEAR.find(it)?.value },
            genres = subjects.filter { it.isNotBlank() }, kind = MediaKind.MOVIE,
            externalUrl = "$ORIGIN/details/${encode(identifier)}")
    }

    companion object {
        private const val ORIGIN = "https://archive.org"
        private const val NAMESPACE = "archive:"
        private const val MAX_ITEMS = 32
        private const val MAX_FILES = 4096
        private const val MAX_STREAMS = 8
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024
        private val YEAR = Regex("\\b(?:18|19|20)\\d{2}\\b")
        private val EXCLUDED = Regex("\\b(?:anime|hentai|adult|xxx|porn(?:ography)?|erotic|k[- ]?drama|korean[- ]drama)\\b", RegexOption.IGNORE_CASE)
        private val MP4_FORMATS = setOf("mpeg4", "512kb mpeg4", "h.264", "h.264 ia", "mpeg-4")
        private val VIDEO_FORMATS = MP4_FORMATS + setOf("divx", "cinepack", "cinepak", "quicktime", "mpeg2", "mpeg-2", "matroska", "ogg video")

        private fun interrupted() {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Archive request interrupted")
        }
        private fun safeSegment(value: String, max: Int): Boolean = value.isNotBlank() &&
            value == value.trim() && value.length <= max && value != "." && value != ".." &&
            value.none { it.isISOControl() || it in "/\\%?#:" } && Charsets.UTF_8.newEncoder().canEncode(value)

        private fun encode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val b = byte.toInt() and 255
                if (b in 65..90 || b in 97..122 || b in 48..57 || b in setOf(45, 95, 46, 126)) append(b.toChar())
                else { append('%'); append("0123456789ABCDEF"[b ushr 4]); append("0123456789ABCDEF"[b and 15]) }
            }
        }
        private fun restricted(obj: JSONObject): Boolean = listOf("is_dark", "nodownload", "access-restricted-item",
            "restricted", "is_adult", "adult").any { key ->
            if (!obj.has(key) || obj.isNull(key)) false else obj.opt(key) !in listOf(false, "false", "0", 0)
        }
        private fun acceptedLicense(raw: Any?): String? {
            val value = (raw as? String)?.takeIf { it.length <= 512 && it == it.trim() } ?: return null
            val uri = try { URI(value) } catch (_: Exception) { return null }
            if (uri.scheme !in setOf("http", "https") || uri.host != "creativecommons.org" || uri.port != -1 ||
                uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val path = uri.rawPath.trimEnd('/')
            if (path !in setOf("/licenses/by/3.0", "/licenses/by/3.0/us", "/licenses/by/4.0",
                    "/publicdomain/zero/1.0", "/publicdomain/mark/1.0")) return null
            return "https://creativecommons.org$path/"
        }
        private fun textValues(raw: Any?, max: Int): List<String> = when (raw) {
            null, JSONObject.NULL -> emptyList()
            is String -> listOf(raw).also { require(raw.length <= 32768) { "Archive text exceeds budget" } }
            is JSONArray -> {
                require(raw.length() <= max) { "Archive metadata list exceeds budget" }
                (0 until raw.length()).mapNotNull { index ->
                    interrupted()
                    (raw.opt(index) as? String)?.also { require(it.length <= 32768) { "Archive text exceeds budget" } }
                }
            }
            else -> emptyList()
        }
        private fun integer(raw: Any?): Long? = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is String -> raw.takeIf { it.matches(Regex("[0-9]{1,19}")) }?.toLongOrNull()
            else -> null
        }
        private fun size(file: JSONObject) = integer(file.opt("size")) ?: -1L
        private fun seconds(raw: Any?): Double? {
            val value = when (raw) {
                is Number -> raw.toDouble()
                is String -> if (raw.matches(Regex("[0-9]{1,8}(?:\\.[0-9]{1,6})?"))) raw.toDoubleOrNull() else null
                else -> null
            }
            return value?.takeIf { it.isFinite() && it > 0 && it <= 86400 }
        }
        private fun requireBoundedJsonDepth(raw: String) {
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (c in raw) {
                if (quote != null) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == quote) quote = null
                } else when (c) {
                    '"', '\'' -> quote = c
                    '{', '[' -> require(++depth <= 16) { "Archive metadata nesting exceeds budget" }
                    '}', ']' -> require(--depth >= 0) { "Unbalanced Archive metadata" }
                }
            }
            require(depth == 0 && quote == null) { "Incomplete Archive metadata" }
        }

        private fun forbidden(file: JSONObject): Boolean = listOf("url", "externalurl", "external_url", "torrent",
            "btih", "magnet", "private", "drm", "licensekey").any { file.has(it) } || restricted(file)
    }
}
