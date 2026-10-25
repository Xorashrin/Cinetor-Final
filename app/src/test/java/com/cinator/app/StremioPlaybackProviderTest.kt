package com.cinator.app

import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.*
import com.cinator.app.providers.*
import org.junit.Assert.*
import org.junit.Test

class StremioPlaybackProviderTest {
    private val base = "https://addon.example.org/reviewed"
    private val providerInfo = ProviderInfo("reviewed", "Reviewed addon", FeedSection.HOME,
        ProviderCapabilities(episodes = true, playback = true, download = true), "https://addon.example.org")
    private class FixtureHttp(val responses: MutableMap<String, String>) : TextHttpClient {
        val calls = mutableListOf<String>()
        override fun get(url: String): String {
            calls += url
            return responses[url] ?: error("Undeclared fixture request: $url")
        }
    }
    private fun adapter(http: TextHttpClient, info: ProviderInfo = providerInfo, download: Boolean = false): PlaybackProvider {
        return StremioPlaybackProvider(info, "$base/manifest.json", http, download)
    }
    private val manifest = """{"id":"org.example.reviewed","name":"Reviewed","version":"1.0.0", "types":["movie","series"],
      "resources":["catalog",{"name":"meta","types":["movie","series"],"idPrefixes":["film:","show:"]},
      {"name":"stream","types":["movie","series"],"idPrefixes":["film:","show:"]}],
      "catalogs":[{"type":"series","id":"curated / list","extra":[{"name":"search"}]}]}"""
    private val series = """{"id":"show:one / 雪%","type":"series","name":"Fixture series","country":"US","genres":["Drama"]}"""
    private fun fixture(): FixtureHttp = FixtureHttp(mutableMapOf(
        "$base/manifest.json" to manifest,
        "$base/catalog/series/curated%20%2F%20list.json" to """{"metas":[$series]}""",
        "$base/meta/series/show%3Aone%20%2F%20%E9%9B%AA%25.json" to
            """{"meta":{"id":"show:one / 雪%","type":"series","name":"Fixture series","country":"US","genres":["Drama"],
            "videos":[{"id":"show:one / 雪%:1:2","title":"Exact episode","season":1,"episode":2}]}}""",
        "$base/stream/series/show%3Aone%20%2F%20%E9%9B%AA%25%3A1%3A2.json" to
            """{"streams":[{"url":"https://media.example.org/exact-episode.mp4","name":"Fixture direct transport"}]}"""
    ))
    @Test fun resolvesExactDiscoveredEpisodeWithoutManualUserUrl() {
        val http = fixture()
        val provider = adapter(http)
        val card = provider.catalog().single()
        val details = provider.details(card)
        val episode = details.episodes.single()
        assertEquals("show:one / 雪%:1:2", episode.id)
        assertEquals(episode.id, episode.contentId)
        val stream = provider.resolve(ResolveRequest(details.card, episode)).single()
        assertEquals(episode.contentId, stream.contentId)
        assertEquals(StreamProtocol.MP4, stream.protocol)
        assertEquals(providerInfo.id, stream.providerId)
        assertFalse(stream.isDownloadable)
        assertFalse(provider.info.capabilities.download)
        assertEquals(4, http.calls.size)
        assertEquals(FeedSection.HOME, card.section)
        assertTrue(card.streams.isEmpty())
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected explicit rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun metadataOnlyNeverCallsStream() {
        val http = fixture()
        http.responses["$base/manifest.json"] = manifest.replace(
            "{\"name\":\"stream\",\"types\":[\"movie\",\"series\"],\"idPrefixes\":[\"film:\",\"show:\"]}", "\"addon_catalog\"")
        val provider = adapter(http)
        val card = provider.catalog().single()
        val episode = provider.details(card).episodes.single()
        assertTrue(provider.resolve(ResolveRequest(card, episode)).isEmpty())
        assertFalse(provider.info.capabilities.playback)
        assertFalse(provider.info.capabilities.download)
        assertTrue(http.calls.none { "/stream/" in it })
    }
    @Test fun searchExtraIsEncodedOnlyWhenAdvertised() {
        val http = fixture()
        val route = "$base/catalog/series/curated%20%2F%20list/search=a%20b%2F%26%3F%25.json"
        http.responses[route] = """{"metas":[$series]}"""
        assertEquals(1, adapter(http).catalog("a b/&?%").size)
        assertEquals(route, http.calls.last())
        val noSearch = fixture()
        noSearch.responses["$base/manifest.json"] = manifest.replace("\"extra\":[{\"name\":\"search\"}]", "\"extra\":[{\"name\":\"genre\",\"isRequired\":true}]")
        val noSearchProvider = adapter(noSearch)
        assertTrue(noSearchProvider.catalog().isEmpty())
        assertTrue(noSearchProvider.catalog("test").isEmpty())
        assertEquals(listOf("$base/manifest.json"), noSearch.calls)
    }
    @Test fun unsupportedDirectCandidatesNeverBecomeNativePlayback() {
        val http = fixture()
        val provider = adapter(http)
        val card = provider.catalog().single()
        val episode = provider.details(card).episodes.single()
        http.responses["$base/stream/series/show%3Aone%20%2F%20%E9%9B%AA%25%3A1%3A2.json"] = """{"streams":[
          {"infoHash":"fixturehash","url":"https://media.example.org/a.mp4"},
          {"externalUrl":"https://media.example.org/b.mp4"},
          {"url":"http://media.example.org/c.mp4"},
          {"url":"https://127.0.0.1/d.mp4"},
          {"url":"https://user:secret@media.example.org/e.mp4"},
          {"url":"https://media.example.org/not-native.html"},
          {"url":"https://media.example.org/f.mp4","drm":{"licenseUrl":"https://media.example.org/license"}},
          {"url":"https://media.example.org/g.mp4","behaviorHints":{"proxyHeaders":{"request":{"Authorization":"secret"}}}},
          {"url":"https://media.example.org/h.mp4","contentId":"another-title"},
          {"url":"https://media.example.org/exact.m3u8?x=1"}]}"""
        val stream = provider.resolve(ResolveRequest(card, episode)).single()
        assertEquals(StreamProtocol.HLS_M3U8, stream.protocol)
        assertEquals(episode.contentId, stream.contentId)
        assertTrue(stream.headers.isEmpty())
    }
    @Test fun identityForgeryRejectedBeforeStreamRequest() {
        val http = fixture()
        val provider = adapter(http)
        val card = provider.catalog().single()
        val episode = provider.details(card).episodes.single()
        rejects { provider.details(card.copy(providerId = "other")) }
        rejects { provider.resolve(ResolveRequest(card.copy(section = FeedSection.ANIME), episode)) }
        rejects { provider.resolve(ResolveRequest(card.copy(contentId = "another"), episode)) }
        rejects { provider.resolve(ResolveRequest(card, episode.copy(id = "show:other:1:1", contentId = "show:other:1:1"))) }
        rejects { provider.resolve(ResolveRequest(card, episode.copy(contentId = "another"))) }
        assertTrue(provider.resolve(ResolveRequest(card)).isEmpty())
        assertTrue(http.calls.none { "/stream/" in it })
    }
    @Test fun unadvertisedStreamTypeAndPrefixAreNotRequested() {
        for (declaration in listOf("{\"name\":\"stream\",\"types\":[\"movie\"]}",
            "{\"name\":\"stream\",\"types\":[\"series\"],\"idPrefixes\":[\"other:\"]}")) {
            val http = fixture()
            http.responses["$base/manifest.json"] = manifest.replace(
                "{\"name\":\"stream\",\"types\":[\"movie\",\"series\"],\"idPrefixes\":[\"film:\",\"show:\"]}", declaration)
            val provider = adapter(http)
            val card = provider.catalog().single()
            assertTrue(provider.resolve(ResolveRequest(card, provider.details(card).episodes.single())).isEmpty())
            assertTrue(http.calls.none { "/stream/" in it })
        }
    }
    @Test fun auditedPermissionIsRequiredForDownload() {
        val http = fixture()
        val provider = adapter(http, download = true)
        val card = provider.catalog().single()
        val stream = provider.resolve(ResolveRequest(card, provider.details(card).episodes.single())).single()
        assertTrue(stream.isDownloadable)
        assertTrue(provider.info.capabilities.download)
    }
    @Test fun adultAndDisabledConfigurationMakesNoNetworkRequests() {
        for (info in listOf(providerInfo.copy(enabled = false), providerInfo.copy(section = FeedSection.ADULT))) {
            val http = fixture()
            val provider = adapter(http, info)
            assertTrue(provider.catalog().isEmpty())
            assertFalse(provider.info.enabled)
            assertFalse(provider.info.capabilities.playback)
            assertTrue(http.calls.isEmpty())
        }
    }
    @Test fun malformedIdentityAndManifestAreRejected() {
        val http = fixture()
        http.responses["$base/meta/series/show%3Aone%20%2F%20%E9%9B%AA%25.json"] =
            """{"meta":{"id":"show:other","type":"series","name":"Wrong","country":"US","genres":["Drama"]}}"""
        val provider = adapter(http)
        rejects { provider.details(provider.catalog().single()) }
        for (bad in listOf("not json", manifest.replace("\"types\":[\"movie\",\"series\"],\"idPrefixes\":[\"film:\",\"show:\"]", "\"types\":[\"alien\"]"))) {
            val badHttp = fixture()
            badHttp.responses["$base/manifest.json"] = bad
            rejects { adapter(badHttp).catalog() }
            assertEquals(1, badHttp.calls.size)
        }
    }
    @Test fun interruptedTransportIsNeverCalled() {
        val http = fixture()
        val provider = adapter(http)
        Thread.currentThread().interrupt()
        try {
            try { provider.catalog(); fail("Expected interruption") } catch (_: java.io.InterruptedIOException) { }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(http.calls.isEmpty())
        } finally { Thread.interrupted() }
    }
    @Test fun genresCannotPromoteOrCrossSection() {
        val http = fixture()
        http.responses["$base/catalog/series/curated%20%2F%20list.json"] = """{"metas":[
          {"id":"show:anime","type":"series","name":"Anime","country":"JP","genres":["Animation"]},
          {"id":"show:kr","type":"series","name":"Korean","country":"KR","genres":["Drama"]},
          {"id":"show:adult","type":"series","name":"Adult","country":"US","genres":["Adult"]},
          {"id":"show:unknown","type":"series","name":"Unknown","genres":["Drama"]},$series]}"""
        assertEquals(listOf("show:one / 雪%"), adapter(http).catalog().map { it.id })
        assertEquals(listOf("show:anime"), adapter(http, providerInfo.copy(section = FeedSection.ANIME)).catalog().map { it.id })
        assertEquals(listOf("show:kr"), adapter(http, providerInfo.copy(section = FeedSection.KDRAMA)).catalog().map { it.id })
    }
    @Test fun nestedArrayDrmCannotHideInsideDirectCandidate() {
        val http = fixture()
        val provider = adapter(http)
        val card = provider.catalog().single()
        val episode = provider.details(card).episodes.single()
        http.responses["$base/stream/series/show%3Aone%20%2F%20%E9%9B%AA%25%3A1%3A2.json"] =
            """{"streams":[{"url":"https://media.example.org/encrypted.mp4","behaviorHints":{"alternatives":[{"drm":"widevine"}]}}]}"""
        assertTrue("No DRM in arbitrary nested stream declarations", provider.resolve(ResolveRequest(card, episode)).isEmpty())
    }

    @Test fun movieIdentityUsesEncodedExactIdAndStringResources() {
        val http = FixtureHttp(mutableMapOf(
            "$base/manifest.json" to """{"id":"fixture.movie","version":"1","name":"Fixture","types":["movie"],"idPrefixes":["film:"],"resources":["catalog","meta","stream"],"catalogs":[{"type":"movie","id":"films"}]}""",
            "$base/catalog/movie/films.json" to """{"metas":[{"id":"film:one?x=/..","type":"movie","name":"Fixture film"}]}""",
            "$base/stream/movie/film%3Aone%3Fx%3D%2F%2E%2E.json" to """{"streams":[{"url":"https://media.example.org/film.mp4"}]}"""
        ))
        val provider = adapter(http)
        val card = provider.catalog().single()
        assertEquals("film:one?x=/..", provider.resolve(ResolveRequest(card)).single().contentId)
        assertEquals(3, http.calls.size)
    }
    @Test fun unsafeManifestTargetsAreRejectedWithoutNetwork() {
        val http = fixture()
        for (url in listOf("http://addon.example.org/manifest.json", "https://localhost/manifest.json",
            "https://10.0.0.1/manifest.json", "https://user:secret@addon.example.org/manifest.json",
            "https://addon.example.org/plugin.js", "https://addon.example.org/manifest.json?key=private",
            "https://addon.example.org/manifest.json#fragment", "https://addon.example.org/a/../manifest.json")) {
            rejects { StremioPlaybackProvider(providerInfo, url, http) }
        }
        assertTrue(http.calls.isEmpty())
    }
    @Test fun explicitAdultFlagCannotEnterNonAdultFeed() {
        val http = fixture()
        http.responses["$base/catalog/series/curated%20%2F%20list.json"] =
            """{"metas":[{"id":"show:adult","name":"Flagged","type":"series","country":"US","genres":["Drama"],"adult":true}]}"""
        assertTrue(adapter(http).catalog().isEmpty())
    }

}
