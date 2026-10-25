package com.cinator.app.ui

import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamSource
import com.cinator.app.providers.EpisodeRef
import com.cinator.app.providers.ProviderDetails

/** Immutable request state; generation changes even when reopening the same card. */
data class ResolvedSelectionState(
    val generation: Long = 0,
    val selectedCard: MediaCard? = null,
    val details: ProviderDetails? = null,
    val episode: EpisodeRef? = null,
    val detailsLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null
) {
    fun select(card: MediaCard?): ResolvedSelectionState = ResolvedSelectionState(
        generation = generation + 1, selectedCard = card,
        details = card?.let { ProviderDetails(it.copy(streams = emptyList())) },
        loading = card != null
    )

    fun selectEpisode(value: EpisodeRef): ResolvedSelectionState {
        if (!detailsLoaded || details?.episodes?.singleOrNull { it.id == value.id } != value || episode == value) return this
        return copy(generation = generation + 1, episode = value,
            details = details.copy(card = details.card.copy(streams = emptyList())), loading = false, error = null)
    }

    fun beginResolve(): ResolvedSelectionState {
        if (!detailsLoaded || details == null) return this
        return copy(generation = generation + 1, loading = true, error = null,
            details = details.copy(card = details.card.copy(streams = emptyList())))
    }

    fun receiveStreams(request: Long, sources: List<StreamSource>): ResolvedSelectionState {
        if (request != generation || !detailsLoaded || details == null || !loading) return this
        val accepted = matchingStreams(details.card.copy(streams = sources))
        return copy(details = details.copy(card = details.card.copy(streams = accepted)), loading = false,
            error = if (accepted.isEmpty()) "No playable sources available for this selection" else null)
    }

    fun fail(request: Long, message: String): ResolvedSelectionState =
        if (request != generation || selectedCard == null) this else copy(loading = false, error = message,
            details = details?.let { it.copy(card = it.card.copy(streams = emptyList())) })

    /** Re-check full membership at click time, then authorize the episode rather than the series. */
    fun actionCard(stream: StreamSource): MediaCard? {
        val card = details?.card ?: return null
        if (!detailsLoaded || loading || error != null || stream !in matchingStreams(card)) return null
        return card.copy(contentId = episode?.contentId ?: card.contentId,
            title = episode?.let { "${card.title} — ${it.title}" } ?: card.title)
    }

    private fun matchingStreams(card: MediaCard): List<StreamSource> {
        if (details?.episodes?.isNotEmpty() == true && episode == null) return emptyList()
        val contentId = episode?.contentId ?: card.contentId
        if (contentId.isNullOrBlank()) return emptyList()
        return card.streams.filter { it.contentId == contentId && it.providerId == card.providerId &&
            it.section == card.section && it.isLive == (card.kind == MediaKind.LIVE_TV) }
    }

    fun receiveDetails(request: Long, value: ProviderDetails): ResolvedSelectionState {
        if (request != generation || selectedCard == null) return this
        val card = value.card
        if (card.id != selectedCard.id || card.providerId != selectedCard.providerId ||
            card.contentId.isNullOrBlank() || card.contentId != selectedCard.contentId ||
            card.section != selectedCard.section || card.kind != selectedCard.kind) {
            return copy(loading = false, error = "Provider returned details for another selection")
        }
        return copy(details = value.copy(card = value.card.copy(streams = emptyList())),
            episode = value.episodes.firstOrNull(), detailsLoaded = true, loading = false, error = null)
    }
}

/** Local picker state only; async provider state remains owned by the caller. */
data class ResolvedDetailsState(
    val contentKey: String,
    val selectedEpisodeId: String?
) {
    fun selectedEpisode(episodes: List<EpisodeRef>): EpisodeRef? =
        selectedEpisodeId?.let { id -> episodes.firstOrNull { it.id == id } }

    fun select(episode: EpisodeRef, episodes: List<EpisodeRef>): ResolvedDetailsState =
        if (episodes.singleOrNull { it.id == episode.id } == episode) copy(selectedEpisodeId = episode.id) else this

    fun forContent(key: String, episodes: List<EpisodeRef>): ResolvedDetailsState = when {
        key != contentKey -> initial(key, episodes)
        episodes.isEmpty() -> copy(selectedEpisodeId = null)
        selectedEpisode(episodes) == null -> copy(selectedEpisodeId = episodes.first().id)
        else -> this
    }

    /** Hides unresolved and stale results rather than offering a stream for another identity. */
    fun visibleStreams(card: MediaCard, episodes: List<EpisodeRef>, loading: Boolean): List<StreamSource> {
        if (loading) return emptyList()
        val episode = selectedEpisode(episodes)
        if (episodes.isNotEmpty() && episode == null) return emptyList()
        val expectedContentId = episode?.contentId ?: card.contentId
        if (expectedContentId.isNullOrBlank()) return emptyList()
        return card.streams.filter { stream ->
            stream.providerId == card.providerId && stream.section == card.section &&
                stream.contentId == expectedContentId
        }
    }

    companion object {
        fun initial(contentKey: String, episodes: List<EpisodeRef>) =
            ResolvedDetailsState(contentKey, episodes.firstOrNull()?.id)
    }
}
