package com.cinator.app

import com.cinator.app.data.CatalogRepository
import com.cinator.app.data.EmbeddedCatalog
import com.cinator.app.data.EmbeddedCatalogParser
import com.cinator.app.data.EmbeddedSource
import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Exercises shipped registry -> repository -> parsers, with no UI or network. */
class FinalBundleIntegrationTest {
    private fun asset(name: String): File = listOf(
        File("app/src/main/assets/$name"), File("src/main/assets/$name")
    ).firstOrNull { it.isFile } ?: error("Missing bundled asset: $name")

    private fun bundle(): EmbeddedCatalog = EmbeddedCatalogParser.parse(asset("embedded_catalog.json").readText())

    private class FakeTextHttpClient(
        private val responses: Map<String, String>,
        private val failedUrls: Set<String> = emptySet()
    ) : TextHttpClient {
        val calls = mutableListOf<String>()
        override fun get(url: String): String {
            calls.add(url)
            // AssertionError is deliberately not swallowed by repository exception isolation.
            if (url !in responses) throw AssertionError("Unexpected network route: $url")
            if (url in failedUrls) throw IOException("private diagnostic must not leak")
            return responses.getValue(url)
        }
    }

    private fun assertStreamIdentity(card: MediaCard, live: Boolean) {
        val stream = card.streams.single()
        assertEquals(card.providerId, stream.providerId)
        assertEquals(card.contentId, stream.contentId)
        assertEquals(card.section, stream.section)
        assertEquals(live, stream.isLive)
        assertEquals(if (live) StreamProtocol.HLS_M3U8 else StreamProtocol.MP4, stream.protocol)
        assertEquals(!live, stream.isDownloadable)
    }

    @Test fun bundledCategoriesKeepDistinctStreamIdentitiesAcrossEverySingleEndpointFailure() {
        val registry = bundle()
        val source = registry.sources.single { it.enabled && it.engine == "IPTV" }
        val categories = listOf("news", "entertainment", "sports", "music", "animation")
        assertEquals(categories.map { "https://iptv-org.github.io/iptv/categories/$it.m3u" }, source.endpoints)
        val sharedUrl = "https://stream.example.com/shared.m3u8"
        val responses = source.endpoints.zip(categories).associate { (endpoint, category) ->
            endpoint to """
                #EXTM3U
                #EXTINF:-1 tvg-id="shared" group-title="Common",Shared channel
                $sharedUrl
                #EXTINF:-1 tvg-id="same-station" group-title="$category",$category channel
                https://stream.example.com/$category.m3u8?variant=primary
            """.trimIndent()
        }
        val healthyHttp = FakeTextHttpClient(responses)
        val healthy = CatalogRepository(registry, healthyHttp).load(FeedSection.LIVETV)
        assertEquals(source.endpoints, healthyHttp.calls)
        assertEquals(1, healthy.sourceCount)
        assertTrue(healthy.failures.isEmpty())
        assertEquals(6, healthy.items.size)
        assertEquals(6, healthy.items.map { it.id }.toSet().size)
        assertEquals(6, healthy.items.map { it.streams.single().id }.toSet().size)
        assertEquals(1, healthy.items.count { it.streams.single().url == sharedUrl })
        assertEquals(categories.toSet(), healthy.items.filter { it.title != "Shared channel" }.flatMap { it.genres }.toSet())
        healthy.items.forEach {
            assertEquals(FeedSection.LIVETV, it.section)
            assertEquals(MediaKind.LIVE_TV, it.kind)
            assertEquals(source.id, it.providerId)
            assertEquals(source.name, it.providerName)
            assertStreamIdentity(it, live = true)
        }
        for ((index, failedEndpoint) in source.endpoints.withIndex()) {
            val http = FakeTextHttpClient(responses, setOf(failedEndpoint))
            val partial = CatalogRepository(registry, http).load(FeedSection.LIVETV)
            assertEquals("All later categories must still be attempted", source.endpoints, http.calls)
            assertEquals(1, partial.sourceCount)
            assertEquals(listOf("${source.id}: 1/5 requests failed; completed results retained"), partial.failures)
            val expected = healthy.items.filterNot { it.title == "${categories[index]} channel" }
            assertEquals(expected.associateBy { it.id }, partial.items.associateBy { it.id })
        }
        val searchedHttp = FakeTextHttpClient(responses)
        val searched = CatalogRepository(registry, searchedHttp).load(FeedSection.LIVETV, " SPORTS ")
        assertEquals(source.endpoints, searchedHttp.calls)
        assertEquals(listOf(healthy.items.single { it.title == "sports channel" }), searched.items)
        assertTrue(searched.failures.isEmpty())
    }

    private val manifest = """{
        "id":"fixture.cinemeta","version":"1","name":"Declared catalogs",
        "resources":["catalog","meta","stream"],"types":["movie","series","video"],
        "catalogs":[
          {"type":"movie","id":"curated/top","extra":[{"name":"search"}]},
          {"type":"series","id":"browse-only"},
          {"type":"series","id":"search only","extra":[{"name":"search","isRequired":true}]},
          {"type":"movie","id":"needs-genre","extra":[{"name":"genre","isRequired":true}]},
          {"type":"video","id":"not-home"}
        ]
    }"""

    private val movie = """{"metas":[{"id":"tt-fixture","type":"movie","name":"Fixture movie",
        "streams":[{"url":"https://stream.example.com/not-authorized.mp4"}]}]}"""
    private val series = """{"metas":[{"id":"series-fixture","type":"series","name":"Fixture series",
        "country":"United States","genres":["Drama"]}]}"""
    private val mixedTvMaze = """[
        {"show":{"id":101,"name":"Ordinary drama","type":"Scripted","language":"English","genres":["Drama"],
          "streams":[{"url":"https://stream.example.com/not-authorized.mp4"}]}},
        {"show":{"id":102,"name":"Japanese animation","type":"Animation","language":"Japanese","genres":["Animation"]}},
        {"show":{"id":103,"name":"Korean drama","type":"Scripted","language":"Korean","genres":["Drama"]}}
    ]"""

    private fun cinemeta(registry: EmbeddedCatalog): EmbeddedSource = registry.sources.single { it.engine == "CINEMETA" }
    private val cinemetaBase = "https://v3-cinemeta.strem.io/"

    @Test fun actualHomeManifestFailureRetainsCatalogAndOtherProvidersWithoutBorrowingSampleStreams() {
        val registry = bundle()
        val cinema = cinemeta(registry)
        assertEquals(listOf("${cinemetaBase}manifest.json"), cinema.endpoints)
        val tv = registry.sources.single { it.id == "tvmaze-home" }
        val movieRoute = "${cinemetaBase}catalog/movie/curated%2Ftop.json"
        val failedRoute = "${cinemetaBase}catalog/series/browse-only.json"
        val responses = mapOf(cinema.endpoints.single() to manifest, movieRoute to movie, failedRoute to "{\"metas\":{}}") +
            tv.endpoints.associateWith { mixedTvMaze }
        val http = FakeTextHttpClient(responses)
        val result = CatalogRepository(registry, http).load(FeedSection.HOME)
        assertEquals(listOf(cinema.endpoints.single(), movieRoute, failedRoute) + tv.endpoints, http.calls)
        assertEquals(3, result.sourceCount)
        assertEquals(listOf("${cinema.id}: 1/3 requests failed; completed results retained"), result.failures)
        assertEquals(setOf("Fixture movie", "Ordinary drama", "MDN CC0 Flower Sample Video"), result.items.map { it.title }.toSet())
        assertEquals(3, result.items.size)
        val metadata = result.items.filter { it.kind != MediaKind.VIDEO }
        assertEquals(setOf(cinema.id, tv.id), metadata.map { it.providerId }.toSet())
        assertTrue("Neither manifest stream capability nor another provider's sample supplies a movie stream", metadata.all { it.streams.isEmpty() })
        val sample = result.items.single { it.kind == MediaKind.VIDEO }
        val sampleProvider = registry.sources.single { it.engine == "OPEN_VIDEO" }
        assertEquals(sampleProvider.id, sample.providerId)
        assertEquals(sampleProvider.endpoints.single(), sample.streams.single().url)
        assertStreamIdentity(sample, live = false)
    }

    @Test fun actualManifestSearchRequestsOnlyEligibleEncodedCatalogsAndOneTvMazeSearchRoute() {
        val registry = bundle()
        val cinema = cinemeta(registry)
        val query = "Night & Day/日"
        val encoded = "Night%20%26%20Day%2F%E6%97%A5"
        val movieRoute = "${cinemetaBase}catalog/movie/curated%2Ftop/search=$encoded.json"
        val seriesRoute = "${cinemetaBase}catalog/series/search%20only/search=$encoded.json"
        val tvRoute = "https://api.tvmaze.com/search/shows?q=$encoded"
        val http = FakeTextHttpClient(mapOf(cinema.endpoints.single() to manifest, movieRoute to movie,
            seriesRoute to series, tvRoute to mixedTvMaze))
        val result = CatalogRepository(registry, http).load(FeedSection.HOME, "  $query  ")
        assertEquals(listOf(cinema.endpoints.single(), movieRoute, seriesRoute, tvRoute), http.calls)
        assertTrue(result.failures.isEmpty())
        assertEquals(3, result.sourceCount)
        assertEquals(setOf("Fixture movie", "Fixture series", "Ordinary drama"), result.items.map { it.title }.toSet())
        assertEquals(3, result.items.size)
        assertTrue(result.items.all { it.section == FeedSection.HOME && it.streams.isEmpty() })
    }

    @Test fun actualBundleFiltersSharedMetadataPayloadIntoSeparateFeedsAndNeverFallsBackOnFailure() {
        val registry = bundle()
        val responses = registry.sources.filter { it.engine == "TVMAZE" }.flatMap { it.endpoints }.associateWith { mixedTvMaze }
        val http = FakeTextHttpClient(responses)
        val repository = CatalogRepository(registry, http)
        for ((section, id, kind) in listOf(
            Triple(FeedSection.ANIME, "tvmaze-102", MediaKind.ANIME),
            Triple(FeedSection.KDRAMA, "tvmaze-103", MediaKind.K_DRAMA)
        )) {
            http.calls.clear()
            val source = registry.sources.single { it.section == section }
            val result = repository.load(section)
            assertEquals(source.endpoints, http.calls)
            assertTrue(result.failures.isEmpty())
            assertEquals(1, result.sourceCount)
            val card = result.items.single()
            assertEquals(id, card.id)
            assertEquals(kind, card.kind)
            assertEquals(section, card.section)
            assertEquals(source.id, card.providerId)
            assertEquals(source.name, card.providerName)
            assertTrue(card.streams.isEmpty())
            val offline = FakeTextHttpClient(responses, source.endpoints.toSet())
            val failed = CatalogRepository(registry, offline).load(section)
            assertEquals(source.endpoints, offline.calls)
            assertTrue("Unavailable metadata must not become the Home sample video", failed.items.isEmpty())
            assertEquals(listOf("${source.id}: 2/2 requests failed; completed results retained"), failed.failures)
        }
        http.calls.clear()
        val adult = repository.load(FeedSection.ADULT, "animation")
        assertTrue(adult.items.isEmpty())
        assertTrue(adult.failures.isEmpty())
        assertEquals(0, adult.sourceCount)
        assertTrue(http.calls.isEmpty())
    }

    @Test fun catalogDirectoryPointerResolves79InertReferencesWithCountsRecomputedFromEntries() {
        val root = JSONObject(asset("embedded_catalog.json").readText())
        val directory = JSONObject(asset(root.getString("directorySnapshotAsset")).readText())
        val array = directory.getJSONArray("entries")
        val entries = (0 until array.length()).map { array.getJSONObject(it) }
        val ids = entries.map { it.getString("id") }
        assertEquals(79, ids.toSet().size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(directory.getInt("uniqueEntries"), entries.size)
        val counts = entries.groupingBy { it.getString("ecosystem") }.eachCount()
        assertEquals(setOf("nuvio", "cloudstream"), counts.keys)
        counts.forEach { (ecosystem, count) -> assertEquals(directory.getJSONObject("counts").getInt(ecosystem), count) }
        assertEquals(mapOf("nuvio" to 11, "cloudstream" to 68), counts)
        val descriptors = entries.sumOf { it.getJSONArray("subplugins").length() }
        assertEquals(162, descriptors)
        assertEquals(directory.getInt("nuvioSubpluginDescriptors"), descriptors)
        val references = entries.mapNotNull { it.optString("endpoint").takeIf(String::isNotBlank) }.toSet()
        val runtime = bundle().sources.filter { it.enabled }
        assertTrue(entries.all { it.getString("status") == "REFERENCE_ONLY" && !it.getBoolean("runtimeEnabled") })
        assertTrue("Directory identifiers must not become executable providers", runtime.none { it.id in ids })
        assertTrue("Directory endpoints are not runtime catalog endpoints", runtime.flatMap { it.endpoints }.none { it in references })
    }
}
