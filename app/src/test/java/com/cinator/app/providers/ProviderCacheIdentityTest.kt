package com.cinator.app.providers

import com.cinator.app.data.CatalogCache
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Uses the batch-2 Blender identity shape and actual cache/provider/resolver implementations.
 * Every example.invalid URL and Fixture title is synthetic test data, not audited media evidence.
 * No network, factory assets, Android state, or shared cache directories are required.
 */
class ProviderCacheIdentityTest {
    @get:Rule val temp = TemporaryFolder(System.getenv("TMPDIR")?.let(::File))

    private val uuid = "bf1f3fb5-b119-4f9f-9930-8e20e892b898"
    private val contentId = "blender:$uuid"
    private val info = ProviderInfo(
        id = "blender-open-films",
        name = "Fixture Blender Open Films",
        section = FeedSection.HOME,
        capabilities = ProviderCapabilities(playback = true, download = true),
        attributionUrl = "https://example.invalid/fixture-rights"
    )
    private val movie = MediaCard(
        id = "big-buck-bunny",
        title = "Fixture Big Buck Bunny",
        subtitle = "Synthetic cache fixture",
        synopsis = "Fixture attribution and license metadata; not a media audit.",
        section = FeedSection.HOME,
        providerId = info.id,
        providerName = info.name,
        year = "2008",
        genres = listOf("Animation"),
        externalUrl = "https://example.invalid/fixture-rights",
        posterUrl = "https://example.invalid/fixture-poster.jpg",
        kind = MediaKind.MOVIE,
        contentId = contentId
    )
    private val publicSource = StreamSource(
        id = "$contentId:720",
        label = "Fixture MP4",
        url = "https://example.invalid/$uuid/fixture-720.mp4",
        protocol = StreamProtocol.MP4,
        quality = "720p",
        isDownloadable = true,
        providerId = info.id,
        contentId = contentId,
        section = FeedSection.HOME
    )

    private fun provider(sources: List<StreamSource> = listOf(publicSource)) =
        OpenFilmProvider(info, listOf(OpenFilm(movie, sources, info.attributionUrl)))

    private fun snapshot(root: File) = File(root, "${FeedSection.HOME.cacheKey}.json")

    @Test fun publicProviderSourceRoundTripKeepsCardContentAndStreamIdentitiesDistinct() {
        val actualProvider = provider()
        val selectedMetadata = actualProvider.catalog().single()
        val selected = selectedMetadata.copy(streams = ProviderResolver(listOf(actualProvider)).resolve(ResolveRequest(selectedMetadata)))
        val root = temp.newFolder("public-round-trip")
        CatalogCache(root).write(FeedSection.HOME, listOf(selected))

        val restored = CatalogCache(root).read(FeedSection.HOME).single()
        assertEquals(selected, restored)
        assertEquals("big-buck-bunny", restored.id)
        assertEquals("blender:$uuid", restored.contentId)
        assertNotEquals(restored.id, restored.contentId)
        assertEquals(MediaKind.MOVIE, restored.kind)
        val source = restored.streams.single()
        assertEquals("$contentId:720", source.id)
        assertEquals(restored.contentId, source.contentId)
        assertEquals(restored.providerId, source.providerId)
        assertEquals(restored.section, source.section)
        assertEquals(publicSource, source)
        assertEquals(listOf(publicSource), ProviderResolver(listOf(actualProvider)).resolve(ResolveRequest(restored)))
    }

    @Test fun independentCacheRootsCannotShareTheSameUuidMetadataOrStreams() {
        val firstRoot = temp.newFolder("first-provider-cache")
        val secondRoot = temp.newFolder("second-provider-cache")
        val first = provider().catalog().single()
        CatalogCache(firstRoot).write(FeedSection.HOME, listOf(first))
        assertTrue(CatalogCache(secondRoot).read(FeedSection.HOME).isEmpty())

        val second = first.copy(title = "Different fixture snapshot", streams = emptyList())
        CatalogCache(secondRoot).write(FeedSection.HOME, listOf(second))
        assertEquals(listOf(first), CatalogCache(firstRoot).read(FeedSection.HOME))
        assertEquals(listOf(second), CatalogCache(secondRoot).read(FeedSection.HOME))
        CatalogCache(secondRoot).clear(FeedSection.HOME)
        assertEquals(listOf(first), CatalogCache(firstRoot).read(FeedSection.HOME))
        assertTrue(CatalogCache(secondRoot).read(FeedSection.HOME).isEmpty())
    }

    @Test fun expiredSecretSourcesDisappearWhileMetadataStillSupportsFreshResolution() {
        val secret = "FIXTURE_SECRET_MUST_NOT_PERSIST"
        val expiredSources = listOf(
            publicSource.copy(id = "$contentId:expired-query", url = publicSource.url + "?expires=1&token=$secret"),
            publicSource.copy(id = "$contentId:expired-header", headers = mapOf("Authorization" to "Bearer $secret")),
            publicSource.copy(id = "$contentId:expired-path", url = "https://example.invalid/token/$secret/fixture.mp4")
        )
        val root = temp.newFolder("expired-sources")
        val expiredProvider = provider(expiredSources)
        CatalogCache(root).write(FeedSection.HOME, listOf(movie.copy(streams = expiredSources)))
        val restored = CatalogCache(root).read(FeedSection.HOME).single()

        assertEquals(movie, restored)
        assertTrue(restored.streams.isEmpty())
        assertFalse(snapshot(root).readText().contains(secret))
        val currentSource = publicSource.copy(
            url = publicSource.url + "?expires=9999999999&token=FIXTURE_CURRENT_TOKEN"
        )
        val currentProvider = provider(listOf(currentSource))
        assertTrue("Missing persistent streams must not mean missing metadata", currentProvider.info.capabilities.details)
        assertEquals(movie, currentProvider.details(restored).card)
        assertEquals(listOf(currentSource), ProviderResolver(listOf(currentProvider)).resolve(ResolveRequest(restored)))
        assertTrue("Resolution must not mutate the metadata snapshot", CatalogCache(root).read(FeedSection.HOME).single().streams.isEmpty())
    }

    @Test fun retainedPublicSourceIsMetadataNotAnAuthoritativePlaybackResolution() {
        val root = temp.newFolder("stale-public-source")
        CatalogCache(root).write(FeedSection.HOME, listOf(movie.copy(streams = listOf(publicSource))))
        val restored = CatalogCache(root).read(FeedSection.HOME).single()
        assertEquals(listOf(publicSource), restored.streams)

        val fresh = publicSource.copy(id = "$contentId:1080", quality = "1080p",
            url = "https://example.invalid/$uuid/fixture-current-1080.mp4")
        val currentProvider = provider(listOf(fresh))
        val resolved = ProviderResolver(listOf(currentProvider)).resolve(ResolveRequest(restored))
        assertEquals(listOf(fresh), resolved)
        assertFalse(resolved.any { it.url == publicSource.url })
        assertEquals(listOf(publicSource), CatalogCache(root).read(FeedSection.HOME).single().streams)
    }

    @Test fun unavailableProviderCannotFallBackToRetainedCachedPlayback() {
        val root = temp.newFolder("unavailable-resolution")
        CatalogCache(root).write(FeedSection.HOME, listOf(movie.copy(streams = listOf(publicSource))))
        val restored = CatalogCache(root).read(FeedSection.HOME).single()
        assertFalse(restored.streams.isEmpty())
        val unavailable = provider(emptyList())
        assertEquals(movie, unavailable.details(restored).card)
        assertTrue(ProviderResolver(listOf(unavailable)).resolve(ResolveRequest(restored)).isEmpty())
        assertTrue(ProviderResolver(emptyList()).resolve(ResolveRequest(restored)).isEmpty())
    }

    @Test fun sameUuidNeverLetsStaleStreamsOrMovieMetadataLeakIntoAnotherCategory() {
        val root = temp.newFolder("category-isolation")
        val wrongSources = listOf(
            publicSource.copy(id = "$contentId:anime", section = FeedSection.ANIME),
            publicSource.copy(id = "$contentId:adult", section = FeedSection.ADULT),
            publicSource.copy(id = "$contentId:live", isLive = true),
            publicSource.copy(id = "$contentId:wrong-content", contentId = "blender:other-fixture"),
            publicSource.copy(id = "$contentId:card-id", contentId = movie.id)
        )
        CatalogCache(root).write(FeedSection.HOME, listOf(movie.copy(streams = listOf(publicSource) + wrongSources)))
        assertEquals(listOf(publicSource), CatalogCache(root).read(FeedSection.HOME).single().streams)
        assertTrue(CatalogCache(root).read(FeedSection.ANIME).isEmpty())
        assertTrue(CatalogCache(root).read(FeedSection.ADULT).isEmpty())
        CatalogCache(root).write(FeedSection.ANIME, listOf(movie.copy(section = FeedSection.ANIME)))
        assertTrue("Western animation remains MOVIE, not ANIME", CatalogCache(root).read(FeedSection.ANIME).isEmpty())

        // Read-time validation must also reject streams added directly to untrusted disk metadata.
        val document = JSONObject(snapshot(root).readText())
        val streams = document.getJSONArray("items").getJSONObject(0).getJSONArray("streams")
        val encodedPublic = streams.getJSONObject(0).toString()
        streams.put(JSONObject(encodedPublic).put("id", "$contentId:disk-anime").put("section", "ANIME"))
        streams.put(JSONObject(encodedPublic).put("id", "$contentId:disk-adult").put("section", "ADULT"))
        streams.put(JSONObject(encodedPublic).put("id", "$contentId:disk-other").put("contentId", movie.id))
        snapshot(root).writeText(document.toString())
        val restored = CatalogCache(root).read(FeedSection.HOME).single()
        assertEquals(movie.copy(streams = listOf(publicSource)), restored)
        assertEquals(listOf(publicSource), ProviderResolver(listOf(provider())).resolve(ResolveRequest(restored)))
        assertTrue(ProviderResolver(listOf(provider())).resolve(ResolveRequest(restored.copy(section = FeedSection.ANIME))).isEmpty())
        assertTrue(CatalogCache(root).read(FeedSection.ANIME).isEmpty())
        assertTrue(CatalogCache(root).read(FeedSection.ADULT).isEmpty())
    }
}
