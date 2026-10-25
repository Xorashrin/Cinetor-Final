package com.cinator.app.providers

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.StreamSource

data class ProviderCapabilities(
    val search: Boolean = true,
    val details: Boolean = true,
    val episodes: Boolean = false,
    val playback: Boolean = false,
    val download: Boolean = false
)

data class ProviderInfo(
    val id: String,
    val name: String,
    val section: FeedSection,
    val capabilities: ProviderCapabilities,
    val attributionUrl: String,
    val enabled: Boolean = true
)

data class EpisodeRef(
    val id: String,
    val title: String,
    val season: Int?,
    val number: Int?,
    val contentId: String
)

data class ProviderDetails(val card: MediaCard, val episodes: List<EpisodeRef> = emptyList())
data class ResolveRequest(val card: MediaCard, val episode: EpisodeRef? = null)

interface PlaybackProvider {
    val info: ProviderInfo
    fun catalog(query: String = ""): List<MediaCard>
    fun details(card: MediaCard): ProviderDetails
    fun resolve(request: ResolveRequest): List<StreamSource>
}
