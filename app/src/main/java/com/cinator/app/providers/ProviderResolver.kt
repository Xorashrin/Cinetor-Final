package com.cinator.app.providers

import com.cinator.app.data.UrlPolicyValidator
import com.cinator.app.data.belongsTo
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import com.cinator.app.playback.safePlaybackHeaders
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.Locale

/** A safe UI-facing failure without provider-controlled diagnostics. */
class ProviderResolutionException : IllegalStateException("Provider resolution failed")

/**
 * Resolves only the selected provider's verified card/episode identity.
 * Providers own bounded HTTP calls; playback transport must also enforce DNS/redirect policy.
 * Empty means unavailable, never permission to search another provider or title.
 */
class ProviderResolver(providers: List<PlaybackProvider>) {
    private val providers = providers.toList()

    fun resolve(request: ResolveRequest): List<StreamSource> {
        checkInterrupted()
        val card = request.card
        if (!validId(card.id) || !validId(card.providerId) || !validId(card.contentId) ||
            !card.belongsTo(card.section)) return emptyList()
        // Ambiguous provider registrations fail closed, including disabled duplicates.
        val provider = providers.singleOrNull { it.info.id == card.providerId } ?: return emptyList()
        val info = provider.info
        if (!info.enabled || info.section != card.section || !info.capabilities.playback ||
            !info.capabilities.details) return emptyList()
        val details = providerCall { provider.details(card) }
        if (!sameCard(card, details.card)) return emptyList()
        val episode = request.episode
        if (episode != null) {
            if (!info.capabilities.episodes || !validId(episode.id) || !validId(episode.contentId) ||
                (episode.season != null && episode.season < 0) ||
                (episode.number != null && episode.number < 0)) return emptyList()
            if (details.episodes.count { it.contentId == episode.contentId } != 1) return emptyList()
            val canonical = details.episodes.singleOrNull { it.id == episode.id } ?: return emptyList()
            if (canonical.contentId != episode.contentId || canonical.season != episode.season ||
                canonical.number != episode.number) return emptyList()
        }
        val contentId = episode?.contentId ?: card.contentId
        val isLive = card.kind == MediaKind.LIVE_TV
        val sources = providerCall { provider.resolve(request) }
        val accepted = mutableListOf<StreamSource>()
        val seen = mutableSetOf<String>()
        for (source in sources) {
            checkInterrupted()
            if (!validId(source.id) || source.providerId != info.id || source.contentId != contentId ||
                source.section != card.section || source.isLive != isLive ||
                !UrlPolicyValidator.isSafeHttpsUrl(source.url) || !safePlaybackHeaders(source.headers)) continue
            if (!seen.add(urlIdentity(source.url))) continue
            accepted.add(source.copy(
                headers = source.headers.toMap(),
                isDownloadable = source.isDownloadable && info.capabilities.download &&
                    !source.isLive && source.protocol == StreamProtocol.MP4
            ))
            if (accepted.size == MAX_SOURCES) break
        }
        checkInterrupted()
        return accepted
    }

    private fun sameCard(selected: MediaCard, actual: MediaCard): Boolean =
        selected.id == actual.id && selected.providerId == actual.providerId &&
            selected.contentId == actual.contentId && selected.section == actual.section &&
            selected.kind == actual.kind

    private fun validId(value: String?): Boolean = !value.isNullOrBlank() &&
        value == value.trim() && value.none { it.isISOControl() }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Provider resolution interrupted")
    }

    private fun <T> providerCall(block: () -> T): T {
        checkInterrupted()
        try {
            return block().also { checkInterrupted() }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            checkInterrupted()
            // Never expose a provider's URL, request headers or credential-bearing exception cause.
            throw ProviderResolutionException()
        }
    }

    companion object {
        const val MAX_SOURCES = 3

        // Deduplication only: return the original URL unchanged, preserving signed query bytes.
        private fun urlIdentity(url: String): String {
            val uri = URI(url)
            return "https://${uri.host.lowercase(Locale.ROOT)}" +
                uri.rawPath.orEmpty().ifEmpty { "/" } +
                (uri.rawQuery?.let { "?$it" } ?: "")
        }
    }
}
