package com.cinator.app.providers

import com.cinator.app.domain.*
import com.cinator.app.playback.PlaybackFallback
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only: no network access, bundled media, or inferred content rights. */
class ProviderResolverIntegrationTest {
    private val info = ProviderInfo("integration-fixture", "Fixture", FeedSection.HOME,
        ProviderCapabilities(episodes = true, playback = true, download = true),
        "https://rights.example.org/fixture")
    private val card = MediaCard("fixture-film", "Fixture", "", "", info.section, info.id,
        info.name, kind = MediaKind.MOVIE, contentId = "fixture:film")
    private fun source(id: String = "one") = StreamSource(id, "Fixture", "https://media.example.org/$id.mp4",
        StreamProtocol.MP4, "", true, info.id, contentId = card.contentId, section = card.section)
    private fun openFilm(streams: List<StreamSource>, configured: ProviderInfo = info) =
        OpenFilmProvider(configured, listOf(OpenFilm(card, streams, info.attributionUrl)))
    private fun attempts(sources: List<StreamSource>): List<StreamSource> {
        if (sources.isEmpty()) return emptyList()
        val fallback = PlaybackFallback(sources.first(), sources.drop(1), maxAttempts = 99)
        return generateSequence { fallback.next() }.toList()
    }
    private class FixtureProvider(
        override val info: ProviderInfo,
        val canonical: ProviderDetails,
        val returned: List<StreamSource>
    ) : PlaybackProvider {
        var detailsCalls = 0
        var resolveCalls = 0
        override fun catalog(query: String) = listOf(canonical.card)
        override fun details(card: MediaCard): ProviderDetails { detailsCalls++; return canonical }
        override fun resolve(request: ResolveRequest): List<StreamSource> { resolveCalls++; return returned }
    }

    @Test fun auditedAllowlistThroughResolverAndFallbackKeepsOnlyThreeSafeDistinctSources() {
        val signed = source().copy(url = "HTTPS://MEDIA.EXAMPLE.ORG:443/one.mp4?signature=A%2FB")
        val equivalent = signed.copy(id = "alias", url = "https://media.example.org/one.mp4?signature=A%2FB")
        val two = source("two")
        val three = source("three")
        val provider = openFilm(listOf(
            source("private").copy(url = "https://169.254.169.254/metadata"),
            source("headers").copy(headers = mapOf("Host" to "evil.example.org")),
            signed, equivalent, two, three, source("four")))
        val selected = provider.catalog().single()
        val resolved = ProviderResolver(listOf(provider)).resolve(ResolveRequest(selected))
        assertEquals(listOf(signed, two, three), resolved)
        assertEquals(resolved, attempts(resolved))
        assertTrue(resolved.all { it.isDownloadable })
    }

    @Test fun forgedEmbeddedStreamsCannotReplaceAuditedUrlOrPromotePermission() {
        val approved = source().copy(isDownloadable = false)
        val provider = openFilm(listOf(approved))
        val forged = provider.catalog().single().copy(streams = listOf(
            approved.copy(url = "https://foreign.example.org/unrelated.mp4", isDownloadable = true)))
        val result = ProviderResolver(listOf(provider)).resolve(ResolveRequest(forged))
        assertEquals(listOf(approved), result)
        assertEquals(listOf(approved), attempts(result))
    }

    @Test fun everyCanonicalCardIdentityFieldMustMatchBeforeResolving() {
        val changed = listOf(card.copy(id = "foreign"), card.copy(providerId = "foreign"),
            card.copy(contentId = "foreign"), card.copy(section = FeedSection.ANIME),
            card.copy(kind = MediaKind.SERIES))
        changed.forEach { returnedCard ->
            val provider = FixtureProvider(info, ProviderDetails(returnedCard), listOf(source()))
            assertTrue(ProviderResolver(listOf(provider)).resolve(ResolveRequest(card)).isEmpty())
            assertEquals(1, provider.detailsCalls)
            assertEquals(0, provider.resolveCalls)
        }
    }

    @Test fun maliciousEpisodeResultsCannotSupplyParentOtherEpisodeOrForeignProvider() {
        val episode = EpisodeRef("ep-2", "Fixture episode", 1, 2, "fixture:film:s1:e2")
        val accepted = source("episode").copy(contentId = episode.contentId)
        val provider = FixtureProvider(info, ProviderDetails(card, listOf(episode)), listOf(
            source("parent"), accepted.copy(id = "foreign-provider", providerId = "other"),
            accepted.copy(id = "other-episode", contentId = "fixture:film:s1:e1"),
            accepted.copy(id = "foreign-section", section = FeedSection.ANIME),
            accepted.copy(id = "live", isLive = true), accepted.copy(id = "invalid\n"), accepted))
        val result = ProviderResolver(listOf(provider)).resolve(ResolveRequest(card, episode))
        assertEquals(listOf(accepted), result)
        assertEquals(listOf(accepted), attempts(result))
    }

    @Test fun disabledDuplicateCannotBeIgnoredToSelectAnEnabledProvider() {
        val active = FixtureProvider(info, ProviderDetails(card), listOf(source()))
        val disabled = FixtureProvider(info.copy(enabled = false), ProviderDetails(card), listOf(source()))
        listOf(listOf(active, disabled), listOf(disabled, active)).forEach { providers ->
            assertTrue(ProviderResolver(providers).resolve(ResolveRequest(card)).isEmpty())
        }
        assertEquals(0, active.detailsCalls + disabled.detailsCalls)
        assertEquals(0, active.resolveCalls + disabled.resolveCalls)
    }

    @Test fun effectivePermissionsRemainRestrictedAcrossTheWholePipeline() {
        val cases = listOf(
            info.copy(enabled = false),
            info.copy(capabilities = info.capabilities.copy(details = false)),
            info.copy(capabilities = info.capabilities.copy(playback = false)),
            info.copy(capabilities = info.capabilities.copy(download = false)))
        cases.forEach { configured ->
            val provider = openFilm(listOf(source()), configured)
            val result = ProviderResolver(listOf(provider)).resolve(ResolveRequest(card))
            if (!configured.enabled || !configured.capabilities.details || !configured.capabilities.playback) {
                assertTrue(result.isEmpty())
            } else {
                assertEquals(1, result.size)
                assertFalse(result.single().isDownloadable)
                assertFalse(attempts(result).single().isDownloadable)
            }
        }
    }

    @Test fun episodeCoordinatesCannotBeChangedWhileKeepingTheSameIds() {
        val episode = EpisodeRef("ep-2", "Fixture episode", 1, 2, "fixture:film:s1:e2")
        val provider = FixtureProvider(info, ProviderDetails(card, listOf(episode)),
            listOf(source().copy(contentId = episode.contentId)))
        listOf(episode.copy(season = 2), episode.copy(number = 1), episode.copy(season = null),
            episode.copy(number = null), episode.copy(id = "foreign")).forEach { forged ->
            assertTrue(ProviderResolver(listOf(provider)).resolve(ResolveRequest(card, forged)).isEmpty())
        }
        assertEquals(0, provider.resolveCalls)
    }

    @Test fun allRejectedStreamsMeanUnavailableWithoutCallingAnotherProvider() {
        val blocked = openFilm(listOf(source().copy(url = "https://127.0.0.1/fixture.mp4")))
        val unrelated = FixtureProvider(info.copy(id = "unrelated"), ProviderDetails(card), listOf(source()))
        val result = ProviderResolver(listOf(blocked, unrelated)).resolve(ResolveRequest(card))
        assertTrue(result.isEmpty())
        assertTrue(attempts(result).isEmpty())
        assertEquals(0, unrelated.detailsCalls)
        assertEquals(0, unrelated.resolveCalls)
    }
}
