package com.cinator.app.providers

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import java.net.URI
import java.util.Collections
import java.util.concurrent.CancellationException

/**
 * An externally audited film. The caller must verify that attributionUrl documents the rights
 * for this exact film and that every stream belongs to it. isDownloadable is the caller's
 * audited permission, not a claim inferred from a URL, title, or file extension.
 */
data class OpenFilm(val card: MediaCard, val streams: List<StreamSource>, val attributionUrl: String)

/** Data-only allowlist: no bundled titles, discovery, fallback media, or remote code execution. */
class OpenFilmProvider(override val info: ProviderInfo, films: List<OpenFilm>) : PlaybackProvider {
    private val approved: Map<String, OpenFilm>

    init {
        requireToken(info.id, "provider ID")
        require(info.name.isNotBlank()) { "Missing provider name" }
        requireHttps(info.attributionUrl)
        val entries = LinkedHashMap<String, OpenFilm>()
        val contentIds = HashSet<String>()
        for (film in films) {
            checkCancellation()
            val card = film.card
            requireToken(card.id, "film ID")
            requireToken(card.contentId, "content ID")
            require(card.title.isNotBlank()) { "Missing film title" }
            require(card.providerId == info.id && card.section == info.section) { "Foreign film identity" }
            require(card.id !in entries && contentIds.add(card.contentId!!)) { "Duplicate film identity" }
            requireHttps(film.attributionUrl)
            val sourceIds = HashSet<String>()
            val streams = film.streams.map { source ->
                requireToken(source.id, "stream ID")
                require(sourceIds.add(source.id)) { "Duplicate stream identity" }
                require(source.providerId == info.id && source.section == card.section &&
                    source.contentId == card.contentId) { "Stream does not belong to film" }
                requireHttps(source.url)
                source.copy(
                    isDownloadable = source.isDownloadable && info.enabled && info.capabilities.playback &&
                        info.capabilities.download && source.protocol == StreamProtocol.MP4 &&
                        !source.isLive && card.kind != MediaKind.LIVE_TV && card.section != FeedSection.LIVETV &&
                        source.headers.isEmpty(),
                    headers = Collections.unmodifiableMap(LinkedHashMap(source.headers))
                )
            }
            // OpenFilm.streams is authoritative; embedded card streams never grant permission.
            val visibleStreams = if (info.enabled && info.capabilities.playback) frozen(streams) else emptyList()
            entries[card.id] = film.copy(card = card.copy(genres = frozen(card.genres), streams = emptyList()),
                streams = visibleStreams)
        }
        approved = Collections.unmodifiableMap(entries)
    }

    override fun catalog(query: String): List<MediaCard> {
        checkCancellation()
        val needle = query.trim()
        if (!info.enabled || (needle.isNotEmpty() && !info.capabilities.search)) return emptyList()
        return approved.values.mapNotNull { film ->
            checkCancellation()
            val card = film.card
            if (needle.isEmpty() || listOf(card.title, card.subtitle, card.synopsis, card.year.orEmpty())
                    .plus(card.genres).any { it.contains(needle, ignoreCase = true) }) card else null
        }
    }

    override fun details(card: MediaCard): ProviderDetails {
        val film = selectedFilm(card)
        check(info.enabled && info.capabilities.details) { "Provider details unavailable" }
        return ProviderDetails(film.card)
    }

    override fun resolve(request: ResolveRequest): List<StreamSource> {
        val film = selectedFilm(request.card)
        require(request.episode == null) { "Open films have no episode resolver" }
        return if (info.enabled && info.capabilities.playback) film.streams else emptyList()
    }

    private fun selectedFilm(card: MediaCard): OpenFilm {
        checkCancellation()
        require(card.providerId == info.id && card.section == info.section) { "Foreign film identity" }
        val film = approved[card.id] ?: throw IllegalArgumentException("Film is not approved")
        require(card.contentId == film.card.contentId) { "Content identity mismatch" }
        return film
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Open-film request interrupted")
    }

    private fun requireToken(value: String?, label: String) {
        require(!value.isNullOrBlank() && value == value.trim() && value.none { it.isISOControl() }) {
            "Missing or malformed $label"
        }
    }

    /** Syntax only; the shared resolver/network client enforces public-host/DNS/redirect policy. */
    private fun requireHttps(value: String) {
        val uri = try { URI(value) } catch (_: Exception) {
            throw IllegalArgumentException("Malformed audited URL")
        }
        require(value == value.trim() && uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443)) { "Audited URLs must use credential-free HTTPS" }
    }

    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
