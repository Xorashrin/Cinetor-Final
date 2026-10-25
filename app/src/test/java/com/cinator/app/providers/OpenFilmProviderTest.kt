package com.cinator.app.providers

import com.cinator.app.domain.*
import org.junit.Assert.*
import org.junit.Test

/** All example.invalid URLs and "Fixture" titles are labelled fake test data, never seeded media. */
class OpenFilmProviderTest {
    private val info = ProviderInfo("fixture-provider", "Fixture Provider", FeedSection.HOME,
        ProviderCapabilities(playback = true, download = true), "https://example.invalid/provider-rights")
    private val stream = StreamSource("fixture-stream", "Fixture MP4", "https://example.invalid/fixture.mp4",
        StreamProtocol.MP4, "Fixture quality", true, info.id, contentId = "fixture-content",
        section = info.section)
    private val card = MediaCard("fixture-film", "Fixture Film", "Fixture subtitle", "A fixture story",
        info.section, info.id, info.name, genres = listOf("Fixture genre"), kind = MediaKind.MOVIE,
        contentId = "fixture-content")
    private fun provider(source: StreamSource = stream) = OpenFilmProvider(info,
        listOf(OpenFilm(card, listOf(source), "https://example.invalid/film-rights")))

    @Test fun auditedFixtureCompletesCatalogSearchDetailsAndResolution() {
        val provider = provider()
        assertEquals(listOf(card), provider.catalog())
        assertTrue(provider.details(card).card.streams.isEmpty())
        assertEquals(1, provider.catalog("  FIXTURE FILM ").size)
        assertEquals(1, provider.catalog("fixture GENRE").size)
        assertTrue(provider.catalog("not-present").isEmpty())
        val selected = provider.catalog().single()
        assertEquals(ProviderDetails(selected), provider.details(selected))
        assertEquals(listOf(stream), provider.resolve(ResolveRequest(selected)))
    }

    @Test fun rejectsForeignOrAlteredIdentityWithoutCrossTitleFallback() {
        val provider = provider()
        val selected = provider.catalog().single()
        listOf(
            selected.copy(id = "other"), selected.copy(providerId = "other"),
            selected.copy(section = FeedSection.ANIME), selected.copy(contentId = "other")
        ).forEach { altered ->
            assertThrows(IllegalArgumentException::class.java) { provider.details(altered) }
            assertThrows(IllegalArgumentException::class.java) { provider.resolve(ResolveRequest(altered)) }
        }
        val episode = EpisodeRef("foreign-episode", "Fixture episode", 1, 1, selected.contentId!!)
        assertThrows(IllegalArgumentException::class.java) { provider.resolve(ResolveRequest(selected, episode)) }
    }

    @Test fun rejectsMalformedConstructorIdentityAndDuplicateFilms() {
        fun reject(film: OpenFilm) = assertThrows(IllegalArgumentException::class.java) {
            OpenFilmProvider(info, listOf(film))
        }
        reject(OpenFilm(card.copy(providerId = "foreign"), listOf(stream), "https://example.invalid/rights"))
        reject(OpenFilm(card, listOf(stream.copy(section = FeedSection.ANIME)), "https://example.invalid/rights"))
        reject(OpenFilm(card, listOf(stream.copy(contentId = "foreign")), "https://example.invalid/rights"))
        reject(OpenFilm(card, listOf(stream.copy(providerId = "foreign")), "https://example.invalid/rights"))
        assertThrows(IllegalArgumentException::class.java) {
            OpenFilmProvider(info, listOf(OpenFilm(card, listOf(stream), "https://example.invalid/rights"),
                OpenFilm(card, listOf(stream), "https://example.invalid/rights")))
        }
    }

    @Test fun downloadFlagRequiresAuditedProviderPermissionAndSupportedDirectMp4() {
        val deniedInfo = info.copy(capabilities = info.capabilities.copy(download = false))
        val denied = OpenFilmProvider(deniedInfo, listOf(OpenFilm(card, listOf(stream),
            "https://example.invalid/film-rights"))).resolve(ResolveRequest(card)).single()
        assertFalse(denied.isDownloadable)
        val hls = provider(stream.copy(protocol = StreamProtocol.HLS_M3U8)).resolve(ResolveRequest(card)).single()
        assertFalse(hls.isDownloadable)
        assertFalse(provider(stream.copy(isLive = true)).resolve(ResolveRequest(card)).single().isDownloadable)
    }

    @Test fun refusesMissingProvenanceAndInvalidStreamTransport() {
        listOf("", "http://example.invalid/rights", "https://user:pass@example.invalid/rights").forEach {
            assertThrows(IllegalArgumentException::class.java) { OpenFilmProvider(info,
                listOf(OpenFilm(card, listOf(stream), it))) }
        }
        assertThrows(IllegalArgumentException::class.java) { provider(stream.copy(url = "magnet:?xt=fixture")) }
        assertThrows(IllegalArgumentException::class.java) { provider(stream.copy(id = " ")) }
        assertThrows(IllegalArgumentException::class.java) { OpenFilmProvider(info,
            listOf(OpenFilm(card.copy(contentId = null), listOf(stream), "https://example.invalid/rights"))) }
    }

    @Test fun requestCannotInjectSourcesOrElevateDownloadPermission() {
        val provider = provider(stream.copy(isDownloadable = false))
        val forged = card.copy(streams = listOf(stream.copy(url = "https://example.invalid/other.mp4")))
        val resolved = provider.resolve(ResolveRequest(forged)).single()
        assertEquals(stream.url, resolved.url)
        assertFalse(resolved.isDownloadable)
        assertFalse(provider(stream.copy(headers = mapOf("Referer" to "https://example.invalid")))
            .resolve(ResolveRequest(card)).single().isDownloadable)
    }

    @Test fun respectsDisabledAndUnsupportedCapabilities() {
        fun configured(value: ProviderInfo) = OpenFilmProvider(value,
            listOf(OpenFilm(card, listOf(stream), "https://example.invalid/rights")))
        val disabled = configured(info.copy(enabled = false))
        assertTrue(disabled.catalog().isEmpty())
        assertTrue(disabled.resolve(ResolveRequest(card)).isEmpty())
        assertThrows(IllegalStateException::class.java) { disabled.details(card) }
        val metadata = configured(info.copy(capabilities = info.capabilities.copy(playback = false)))
        assertTrue(metadata.resolve(ResolveRequest(card)).isEmpty())
        assertTrue(metadata.catalog().single().streams.isEmpty())
        val noSearch = configured(info.copy(capabilities = info.capabilities.copy(search = false)))
        assertEquals(1, noSearch.catalog().size)
        assertTrue(noSearch.catalog("Fixture").isEmpty())
    }

    @Test fun emptyCatalogNeverFallsBackToSampleMedia() {
        val empty = OpenFilmProvider(info, emptyList())
        assertTrue(empty.catalog().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { empty.resolve(ResolveRequest(card)) }
    }

    @Test fun interruptIsPreservedAndPropagated() {
        val provider = provider()
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) { provider.catalog() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun mutableGenresAndHeadersCannotChangeApprovedData() {
        val genres = mutableListOf("Fixture genre")
        val headers = mutableMapOf("Referer" to "https://example.invalid")
        val provider = OpenFilmProvider(info, listOf(OpenFilm(card.copy(genres = genres),
            listOf(stream.copy(headers = headers)), "https://example.invalid/rights")))
        genres.clear(); headers.clear()
        val selected = provider.catalog().single()
        assertEquals(listOf("Fixture genre"), selected.genres)
        val resolved = provider.resolve(ResolveRequest(selected))
        assertTrue(selected.streams.isEmpty())
        assertEquals(1, resolved.single().headers.size)
        assertThrows(UnsupportedOperationException::class.java) { (selected.genres as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (resolved as MutableList).clear() }
    }

    @Test fun constructorSnapshotsMutableSourceLists() {
        val streams = mutableListOf(stream)
        val films = mutableListOf(OpenFilm(card, streams, "https://example.invalid/film-rights"))
        val provider = OpenFilmProvider(info, films)
        streams.clear(); films.clear()
        assertEquals(1, provider.catalog().size)
        assertEquals(1, provider.resolve(ResolveRequest(provider.catalog().single())).size)
    }
}
