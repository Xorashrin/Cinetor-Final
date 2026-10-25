package com.cinator.app.providers

import com.cinator.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class ProviderResolverTest {
    private val card = MediaCard("film-1", "Film", "", "", FeedSection.HOME,
        "reviewed", "Reviewed", kind = MediaKind.MOVIE, contentId = "film:1")
    private fun stream(
        url: String = "https://media.example.org/film-1.mp4",
        providerId: String = card.providerId,
        contentId: String? = card.contentId,
        section: FeedSection? = card.section,
        isLive: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ) = StreamSource(url, "MP4", url, StreamProtocol.MP4, "", false, providerId,
        contentId = contentId, section = section, isLive = isLive, headers = headers)

    private class Provider(
        override val info: ProviderInfo,
        var result: ProviderDetails,
        var sources: List<StreamSource>
    ) : PlaybackProvider {
        var resolveCalls = 0
        var detailsCalls = 0
        var onResolve: (() -> Unit)? = null
        var onDetails: (() -> Unit)? = null
        override fun catalog(query: String) = listOf(result.card)
        override fun details(card: MediaCard): ProviderDetails {
            detailsCalls++
            onDetails?.invoke()
            return result
        }
        override fun resolve(request: ResolveRequest): List<StreamSource> {
            resolveCalls++
            onResolve?.invoke()
            return sources
        }
    }

    private fun provider(
        sources: List<StreamSource> = listOf(stream()),
        details: ProviderDetails = ProviderDetails(card),
        info: ProviderInfo = ProviderInfo(card.providerId, "Reviewed", card.section,
            ProviderCapabilities(episodes = true, playback = true), "https://example.org/license")
    ) = Provider(info, details, sources)

    @Test fun resolvesOnlySelectedProviderWithoutUnrelatedFallback() {
        val selected = provider()
        val unrelated = provider(info = selected.info.copy(id = "other"))
        assertEquals(listOf(stream()), ProviderResolver(listOf(unrelated, selected)).resolve(ResolveRequest(card)))
        assertEquals(1, selected.resolveCalls)
        assertEquals(0, unrelated.resolveCalls)
        selected.sources = emptyList()
        assertTrue(ProviderResolver(listOf(selected, unrelated)).resolve(ResolveRequest(card)).isEmpty())
        assertEquals(0, unrelated.resolveCalls)
    }

    @Test fun rejectsProviderAndDetailsIdentityMismatchBeforeResolve() {
        val cases = listOf(
            provider(info = provider().info.copy(section = FeedSection.ANIME)),
            provider(info = provider().info.copy(enabled = false)),
            provider(info = provider().info.copy(capabilities = ProviderCapabilities(playback = false))),
            provider(details = ProviderDetails(card.copy(id = "another"))),
            provider(details = ProviderDetails(card.copy(contentId = "film:2")))
        )
        cases.forEach { candidate ->
            assertTrue(ProviderResolver(listOf(candidate)).resolve(ResolveRequest(card)).isEmpty())
            assertEquals(0, candidate.resolveCalls)
        }
        assertTrue(ProviderResolver(listOf(provider())).resolve(ResolveRequest(card.copy(id = " "))).isEmpty())
        assertTrue(ProviderResolver(listOf(provider())).resolve(ResolveRequest(card.copy(contentId = null))).isEmpty())
    }

    @Test fun episodeMustBelongToSelectedCardsDetailsAndDrivesContentIdentity() {
        val selectedEpisode = EpisodeRef("ep-2", "Episode 2", 1, 2, "film:1:s1:e2")
        val candidate = provider(
            sources = listOf(stream(contentId = selectedEpisode.contentId)),
            details = ProviderDetails(card, listOf(selectedEpisode))
        )
        assertEquals(candidate.sources, ProviderResolver(listOf(candidate)).resolve(ResolveRequest(card, selectedEpisode)))

        val forged = selectedEpisode.copy(contentId = "other:episode")
        assertTrue(ProviderResolver(listOf(candidate)).resolve(ResolveRequest(card, forged)).isEmpty())
        assertEquals(1, candidate.resolveCalls)
    }

    @Test fun filtersUnsafeOrCrossIdentityResultsAndLimitsDistinctUrlsToThree() {
        val valid1 = stream("https://media.example.org/one.mp4")
        val duplicate = valid1.copy(id = "duplicate")
        val valid2 = stream("https://media.example.org/two.mp4")
        val valid3 = stream("https://media.example.org/three.mp4")
        val valid4 = stream("https://media.example.org/four.mp4")
        val candidate = provider(listOf(
            stream("http://media.example.org/unsafe.mp4"),
            stream(providerId = "other"), stream(contentId = "film:2"),
            stream(section = FeedSection.ANIME),
            stream(headers = mapOf("Host" to "attacker.example")),
            valid1, duplicate, valid2, valid3, valid4
        ))
        assertEquals(listOf(valid1, valid2, valid3), ProviderResolver(listOf(candidate)).resolve(ResolveRequest(card)))
    }

    @Test fun keepsAllCandidatesInSelectedCardsPlaybackMode() {
        val vod1 = stream("https://media.example.org/vod-1.mp4")
        val live = stream("https://media.example.org/live.m3u8", isLive = true)
        val vod2 = stream("https://media.example.org/vod-2.mp4")
        assertEquals(listOf(vod1, vod2), ProviderResolver(listOf(provider(listOf(vod1, live, vod2))))
            .resolve(ResolveRequest(card)))
    }


    @Test fun rejectsMissingDuplicateAndMalformedProviderIdentity() {
        assertTrue(ProviderResolver(emptyList()).resolve(ResolveRequest(card)).isEmpty())
        val first = provider()
        val second = provider()
        assertTrue(ProviderResolver(listOf(first, second)).resolve(ResolveRequest(card)).isEmpty())
        assertEquals(0, first.resolveCalls + second.resolveCalls)
        listOf("", " film:1", "film:1 ", "film\n1").forEach {
            assertTrue(ProviderResolver(listOf(first)).resolve(ResolveRequest(card.copy(contentId = it))).isEmpty())
        }
    }

    @Test fun episodeRejectsDuplicateIdsAndAmbiguousContentIds() {
        val episode = EpisodeRef("ep", "Episode", 1, 1, "film:1:e1")
        val p = provider(listOf(stream(contentId = episode.contentId)), ProviderDetails(card, listOf(episode, episode)))
        assertTrue(ProviderResolver(listOf(p)).resolve(ResolveRequest(card, episode)).isEmpty())
        p.result = ProviderDetails(card, listOf(episode, episode.copy(id = "different")))
        assertTrue(ProviderResolver(listOf(p)).resolve(ResolveRequest(card, episode)).isEmpty())
    }

    @Test fun malformedEpisodeNumbersDoNotReachResolve() {
        val episode = EpisodeRef("ep", "Episode", -1, 1, "film:1:e1")
        val p = provider(listOf(stream(contentId = episode.contentId)), ProviderDetails(card, listOf(episode)))
        assertTrue(ProviderResolver(listOf(p)).resolve(ResolveRequest(card, episode)).isEmpty())
        assertEquals(0, p.resolveCalls)
    }

    @Test fun rejectsPrivateCredentialsAndEncodedControlsInUrls() {
        listOf("https://127.0.0.1/x", "https://10.0.0.1/x", "https://[::1]/x",
            "https://user:secret@media.example.org/x", "https://media.example.org/%0a",
            "file:///movie.mp4", "magnet:?xt=urn:btih:123", "https://media.example.org:8443/x").forEach {
            assertTrue(ProviderResolver(listOf(provider(listOf(stream(it))))).resolve(ResolveRequest(card)).isEmpty())
        }
    }

    @Test fun deduplicatesEquivalentAuthoritiesWithoutRewritingSignedUrls() {
        val signed = stream("HTTPS://MEDIA.EXAMPLE.ORG:443/a.mp4?token=A%2Fb#one")
        val equivalent = stream("https://media.example.org/a.mp4?token=A%2Fb#two")
        val differentQuery = stream("https://media.example.org/a.mp4?token=C%2Fd")
        assertEquals(listOf(signed, differentQuery), ProviderResolver(listOf(provider(listOf(signed, equivalent, differentQuery))))
            .resolve(ResolveRequest(card)))
    }

    @Test fun rejectsHeaderInjectionReservedNamesAndDuplicateCase() {
        listOf(mapOf("X-Test" to "ok\r\nHost: evil"), mapOf("Host" to "example.org"),
            mapOf("Range" to "bytes=0-1"), mapOf("Content-Length" to "9"),
            mapOf("Bad Name" to "x"), mapOf("X-Token" to "a", "x-token" to "b"),
            mapOf("X-Huge" to "a".repeat(4097))).forEach {
            assertTrue(ProviderResolver(listOf(provider(listOf(stream(headers = it)))))
                .resolve(ResolveRequest(card)).isEmpty())
        }
    }

    @Test fun preservesValidHeadersAndDoesNotRetainMutableProviderMap() {
        val headers = mutableMapOf("Referer" to "https://example.org/", "User-Agent" to "Player")
        val p = provider(listOf(stream(headers = headers)))
        val result = ProviderResolver(listOf(p)).resolve(ResolveRequest(card)).single()
        assertEquals(headers, result.headers)
        headers["Host"] = "evil.example.org"
        assertFalse(result.headers.containsKey("Host"))
    }

    @Test fun downloadRequiresBothProviderPermissionAndSupportedVodProtocol() {
        val downloadable = stream().copy(isDownloadable = true)
        val p = provider(listOf(downloadable))
        assertFalse(ProviderResolver(listOf(p)).resolve(ResolveRequest(card)).single().isDownloadable)
        val audited = provider(listOf(downloadable), info = p.info.copy(capabilities = p.info.capabilities.copy(download = true)))
        assertTrue(ProviderResolver(listOf(audited)).resolve(ResolveRequest(card)).single().isDownloadable)
        audited.sources = listOf(downloadable.copy(protocol = StreamProtocol.HLS_M3U8))
        assertFalse(ProviderResolver(listOf(audited)).resolve(ResolveRequest(card)).single().isDownloadable)
    }

    @Test fun liveCardsNeverReceiveVodOrDownloadableStreams() {
        val liveCard = card.copy(section = FeedSection.LIVETV, kind = MediaKind.LIVE_TV)
        val live = stream(section = FeedSection.LIVETV, isLive = true).copy(isDownloadable = true)
        val p = provider(listOf(live.copy(isLive = false), live), ProviderDetails(liveCard),
            provider().info.copy(section = FeedSection.LIVETV))
        val result = ProviderResolver(listOf(p)).resolve(ResolveRequest(liveCard))
        assertEquals(listOf(live.copy(isDownloadable = false)), result)
    }

    @Test fun cancellationExceptionIsNeverConvertedToUnavailable() {
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        val p = provider()
        p.onDetails = { throw cancelled }
        try {
            ProviderResolver(listOf(p)).resolve(ResolveRequest(card))
            fail("Expected cancellation")
        } catch (e: java.util.concurrent.CancellationException) {
            assertSame(cancelled, e)
            assertEquals(0, p.resolveCalls)
        }
    }

    @Test fun providerInterruptionRestoresFlagEvenWhenExceptionClearsIt() {
        val p = provider()
        p.onDetails = { throw InterruptedException("cancel") }
        try {
            ProviderResolver(listOf(p)).resolve(ResolveRequest(card))
            fail("Expected interruption")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, p.resolveCalls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun providerFailureCannotLeakCredentialUrlOrTriggerFallback() {
        val p = provider()
        p.onResolve = { throw java.io.IOException("https://example.org/?secret=credential") }
        val unrelated = provider(info = p.info.copy(id = "unrelated"))
        try {
            ProviderResolver(listOf(p, unrelated)).resolve(ResolveRequest(card))
            fail("Expected failure")
        } catch (e: Exception) {
            assertFalse(e.toString().contains("credential"))
            assertNull(e.cause)
            assertEquals(0, unrelated.resolveCalls)
        }
    }

    @Test fun interruptionStopsBeforeProviderWorkAndIsPreserved() {
        val candidate = provider()
        Thread.currentThread().interrupt()
        try {
            ProviderResolver(listOf(candidate)).resolve(ResolveRequest(card))
            fail("Expected InterruptedException")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, candidate.detailsCalls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun interruptionDuringProviderResolveIsPreserved() {
        val candidate = provider()
        candidate.onResolve = { Thread.currentThread().interrupt() }
        try {
            ProviderResolver(listOf(candidate)).resolve(ResolveRequest(card))
            fail("Expected InterruptedException")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
