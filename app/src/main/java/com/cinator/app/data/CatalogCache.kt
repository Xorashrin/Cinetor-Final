package com.cinator.app.data

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Disposable, section-scoped metadata snapshots. Call from an IO dispatcher. */
class CatalogCache(private val rootDirectory: File) {
    fun read(section: FeedSection): List<MediaCard> {
        return try {
            val file = fileFor(section)
            if (!file.isFile) return emptyList()
            if (file.length() > MAX_BYTES) return emptyList()
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) return emptyList()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = bytes.toString(Charsets.UTF_8)
            if (!boundedJsonDepth(text)) return emptyList()
            val root = JSONObject(text)
            if (root.opt("schemaVersion") != 1 || root.optString("section") != section.name) return emptyList()
            val items = root.getJSONArray("items")
            if (items.length() > MAX_ITEMS) return emptyList()
            (0 until items.length()).mapNotNull {
                try { sanitize(decodeCard(items.getJSONObject(it)), section) } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Compatibility write; callers needing generation guards should stage first. */
    fun write(section: FeedSection, items: List<MediaCard>) {
        prepare(section, items).use { it.publish() }
    }

    /**
     * Serialize, sanitize and fsync an unpublished snapshot on an IO dispatcher.
     * No UI generation/section commit guard may be held while preparing.
     * Always use/close the result, including when publication is rejected as stale.
     */
    fun prepare(section: FeedSection, items: List<MediaCard>): PreparedWrite {
        val destination = fileFor(section)
        val key = destination.absoluteFile.toPath().normalize().toString()
        val epoch = synchronized(WRITE_LOCK) { clearEpochs[key] ?: 0L }
        check(rootDirectory.isDirectory || rootDirectory.mkdirs()) { "Cannot create catalog cache directory" }
        val output = ByteArrayOutputStream()
        fun append(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (output.size() + bytes.size > MAX_BYTES) throw IOException("Catalog snapshot exceeds byte budget")
            output.write(bytes)
        }
        append("{\"schemaVersion\":1,\"section\":${JSONObject.quote(section.name)},\"items\":[")
        items.asSequence().mapNotNull { sanitize(it, section) }.take(MAX_ITEMS).forEachIndexed { index, card ->
            if (index > 0) append(",")
            append(encodeCard(card).toString())
        }
        append("]}")
        val (staging, stream) = synchronized(WRITE_LOCK) {
            check((clearEpochs[key] ?: 0L) == epoch) { "Catalog cache was cleared during preparation" }
            val file = File.createTempFile(".${destination.name}.", ".tmp", rootDirectory)
            // Open before releasing the clear lock: clear may unlink an open
            // staging file, but preparation must never recreate it after opt-out.
            try {
                file to java.io.FileOutputStream(file)
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
        try {
            stream.use {
                output.writeTo(it)
                it.fd.sync()
            }
            return PreparedWrite(staging, destination, key, epoch)
        } catch (error: Throwable) {
            staging.delete()
            throw error
        }
    }

    /** One-shot staged snapshot. publish/close are IO operations, never main-thread callbacks. */
    class PreparedWrite internal constructor(
        private val staging: File,
        private val destination: File,
        private val key: String,
        private val epoch: Long
    ) : java.io.Closeable {
        private var consumed = false

        /**
         * Atomic replacement only: no serialization or fsync. Caller must order
         * publication with generation acceptance and clears via its suspend mutex.
         * Returns false after close/publication or a clear, including across instances.
         */
        fun publish(): Boolean = synchronized(WRITE_LOCK) {
            if (consumed) return@synchronized false
            consumed = true
            try {
                if ((clearEpochs[key] ?: 0L) != epoch) return@synchronized false
                // Never delete first or fall back to a non-atomic replacement.
                Files.move(staging.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            } finally {
                staging.delete()
            }
        }

        override fun close(): Unit = synchronized(WRITE_LOCK) {
            consumed = true
            Files.deleteIfExists(staging.toPath())
            Unit
        }
    }

    fun clear(section: FeedSection): Unit = synchronized(WRITE_LOCK) {
        val key = fileFor(section).absoluteFile.toPath().normalize().toString()
        clearEpochs[key] = (clearEpochs[key] ?: 0L) + 1L
        Files.deleteIfExists(fileFor(section).toPath())
        clearStaging(section)
    }

    private fun clearStaging(section: FeedSection) {
        val prefix = ".${fileFor(section).name}."
        rootDirectory.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".tmp") }
            ?.forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun fileFor(section: FeedSection) = File(rootDirectory, "${section.cacheKey}.json")

    private fun boundedJsonDepth(text: String): Boolean {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (char in text) {
            if (quote != null) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == quote) quote = null
            } else when (char) {
                '"', '\'' -> quote = char
                '{', '[' -> if (++depth > 8) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && quote == null
    }

    private fun sanitize(card: MediaCard, section: FeedSection): MediaCard? {
        if (!card.belongsTo(section)) return null
        return card.copy(
            externalUrl = persistentUrl(card.externalUrl),
            posterUrl = persistentUrl(card.posterUrl),
            backdropUrl = persistentUrl(card.backdropUrl),
            streams = card.streams.filter {
                it.headers.isEmpty() && it.contentId != null && it.contentId == card.contentId &&
                    it.section == section && it.isLive == (card.kind == MediaKind.LIVE_TV) &&
                    persistentUrl(it.url) != null
            }
        )
    }

    // Cache no headers and no query/fragment URLs, even opaque query parameter names:
    // they may carry expiring bearer credentials. Keep metadata when playback is omitted.
    private fun persistentUrl(url: String?): String? {
        if (url == null || !UrlPolicyValidator.isSafeHttpsUrl(url)) return null
        val uri = URI(url)
        if (uri.rawQuery != null || uri.rawFragment != null ||
            SECRET_PATH.containsMatchIn(uri.path.orEmpty())) return null
        return url
    }

    private fun encodeCard(card: MediaCard) = JSONObject().apply {
        put("id", card.id)
        put("title", card.title)
        put("subtitle", card.subtitle)
        put("synopsis", card.synopsis)
        put("section", card.section.name)
        put("kind", card.kind.name)
        put("contentId", card.contentId)
        put("providerId", card.providerId)
        put("providerName", card.providerName)
        put("year", card.year)
        put("rating", card.rating)
        put("genres", JSONArray(card.genres))
        put("externalUrl", card.externalUrl)
        put("posterUrl", card.posterUrl)
        put("backdropUrl", card.backdropUrl)
        put("streams", JSONArray(card.streams.map(::encodeStream)))
    }

    private fun encodeStream(stream: StreamSource) = JSONObject().apply {
        put("id", stream.id)
        put("label", stream.label)
        put("url", stream.url)
        put("protocol", stream.protocol.name)
        put("quality", stream.quality)
        put("isDownloadable", stream.isDownloadable)
        put("providerId", stream.providerId)
        put("subtitleLang", stream.subtitleLang)
        put("contentId", stream.contentId)
        put("section", stream.section?.name)
        put("isLive", stream.isLive)
    }

    private fun decodeCard(obj: JSONObject): MediaCard {
        val genres = obj.optJSONArray("genres") ?: JSONArray()
        val streams = obj.optJSONArray("streams") ?: JSONArray()
        return MediaCard(
            id = obj.getString("id"), title = obj.getString("title"),
            subtitle = obj.getString("subtitle"), synopsis = obj.getString("synopsis"),
            section = FeedSection.valueOf(obj.getString("section")),
            kind = MediaKind.valueOf(obj.getString("kind")), contentId = obj.nullableString("contentId"),
            providerId = obj.getString("providerId"), providerName = obj.getString("providerName"),
            year = obj.nullableString("year"), rating = obj.nullableString("rating"),
            genres = (0 until genres.length()).map { genres.getString(it) },
            externalUrl = obj.nullableString("externalUrl"), posterUrl = obj.nullableString("posterUrl"),
            backdropUrl = obj.nullableString("backdropUrl"),
            streams = (0 until streams.length()).mapNotNull {
                val stream = streams.getJSONObject(it)
                if (stream.has("headers") &&
                    (!stream.isNull("headers") && stream.optJSONObject("headers")?.length() != 0)) null
                else decodeStream(stream)
            }
        )
    }

    private fun decodeStream(obj: JSONObject) = StreamSource(
        id = obj.getString("id"), label = obj.getString("label"), url = obj.getString("url"),
        protocol = StreamProtocol.valueOf(obj.getString("protocol")), quality = obj.getString("quality"),
        isDownloadable = obj.getBoolean("isDownloadable"), providerId = obj.getString("providerId"),
        subtitleLang = obj.nullableString("subtitleLang"), contentId = obj.nullableString("contentId"),
        section = obj.nullableString("section")?.let(FeedSection::valueOf), isLive = obj.getBoolean("isLive")
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    companion object {
        // Shared replacement/clear bookkeeping; serialization and fsync are outside it.
        // No UI generation gate is acquired while holding this filesystem lock.
        private val WRITE_LOCK = Any()
        private val clearEpochs = mutableMapOf<String, Long>()
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_ITEMS = 500
        private val SECRET_PATH = Regex(
            "(?:^|[/;])(?:token|auth|authorization|password|passwd|secret|signature|sig|session|apikey|api_key|access_token|expires)(?:[/=;]|$)",
            RegexOption.IGNORE_CASE
        )
    }
}
