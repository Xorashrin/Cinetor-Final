package com.cinator.app.providers

import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.*
import java.io.IOException
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic real-API-shape fixtures modeled after archive.org/metadata/BigBuckBunny_328
 * observed 2026-10-26. Field names/types and original/derivative relationships match
 * the public API. Test identifiers, filenames, sizes and selected metadata are modified;
 * these are NOT captured API responses or live transport/rights-audit evidence.
 */
class InternetArchivePlaybackProviderTest {
    private val info = ProviderInfo(
        id = "internet-archive", name = "Internet Archive", section = FeedSection.HOME,
        capabilities = ProviderCapabilities(playback = true, download = true),
        attributionUrl = "https://archive.org/"
    )

    private fun fixture(
        id: String = "Big Buck Bunny_328",
        license: String = "http://creativecommons.org/licenses/by/3.0/us/",
        title: String = "Big Buck Bunny",
        subject: Any = "open movie",
        files: JSONArray = JSONArray()
            .put(JSONObject().put("name", "Big Buck Bunny 512kb.mp4").put("source", "derivative")
                .put("format", "512Kb MPEG4").put("original", "Big Buck Bunny.avi")
                .put("size", "12345678").put("length", "596.45").put("height", "360"))
            .put(JSONObject().put("name", "Big Buck Bunny.avi").put("source", "original")
                .put("format", "Cinepack").put("size", "400455228").put("length", "596.45"))
    ): String = JSONObject()
        .put("metadata", JSONObject().put("identifier", id).put("mediatype", "movies")
            .put("title", title).put("description", "Big Buck Bunny in DivX 720p.")
            .put("date", "2008").put("licenseurl", license).put("subject", subject))
        .put("files", files).put("files_count", files.length()).toString()

    private class FakeHttp(val responses: MutableMap<String, Any>) : TextHttpClient {
        val calls = mutableListOf<String>()
        override fun get(url: String): String {
            calls += url
            return when (val value = responses[url] ?: error("Unexpected URL: $url")) {
                is Throwable -> throw value
                else -> value as String
            }
        }
    }

    private fun provider(ids: Set<String>, http: TextHttpClient) =
        InternetArchivePlaybackProvider(info, ids, http)

    @Test fun catalogLoadsOnlyConfiguredMetadataAndPreservesExactNamespacedIdentity() {
        val url = "https://archive.org/metadata/Big%20Buck%20Bunny_328"
        val http = FakeHttp(mutableMapOf(url to fixture()))
        val p = provider(setOf("Big Buck Bunny_328"), http)
        val card = p.catalog().single()
        assertEquals(listOf(url), http.calls)
        assertEquals("archive:Big Buck Bunny_328", card.id)
        assertEquals(card.id, card.contentId)
        assertEquals("Big Buck Bunny", card.title)
        assertEquals(FeedSection.HOME, card.section)
        assertEquals(MediaKind.MOVIE, card.kind)
        assertEquals(listOf(card), p.catalog(" bunny "))
        assertTrue(p.catalog("anime").isEmpty())
    }

    @Test fun detailsRefetchesExactIdentifierAndResolveUsesFreshAuditedMetadata() {
        val url = "https://archive.org/metadata/BigBuckBunny_328"
        val http = FakeHttp(mutableMapOf(url to fixture(id = "BigBuckBunny_328")))
        val p = provider(setOf("BigBuckBunny_328"), http)
        val card = p.catalog().single()
        val details = p.details(card)
        val stream = p.resolve(ResolveRequest(details.card)).single()
        assertEquals(3, http.calls.size)
        assertEquals("archive:BigBuckBunny_328", stream.contentId)
        assertEquals("https://archive.org/download/BigBuckBunny_328/Big%20Buck%20Bunny%20512kb.mp4", stream.url)
        assertEquals(StreamProtocol.MP4, stream.protocol)
        assertTrue(stream.isDownloadable)
        assertTrue(stream.headers.isEmpty())
        assertFalse(stream.isLive)
        assertTrue(details.episodes.isEmpty())
    }

    @Test fun rightsMustBeExplicitMachineReadableAndControlDownloadPermission() {
        val accepted = listOf(
            "https://creativecommons.org/publicdomain/zero/1.0/",
            "https://creativecommons.org/licenses/by/3.0/",
            "http://creativecommons.org/licenses/by/3.0/us/",
            "https://creativecommons.org/licenses/by/4.0/",
            "https://creativecommons.org/publicdomain/mark/1.0/"
        )
        accepted.forEachIndexed { i, license ->
            val id = "accepted$i"; val u = "https://archive.org/metadata/$id"
            val p = provider(setOf(id), FakeHttp(mutableMapOf(u to fixture(id, license))))
            assertTrue(p.resolve(ResolveRequest(p.catalog().single())).single().isDownloadable)
        }
        listOf("", "CC BY 4.0", "https://example.org/public-domain",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "https://creativecommons.org/licenses/by/4.0/?fake=1").forEachIndexed { i, license ->
            val id = "denied$i"; val u = "https://archive.org/metadata/$id"
            val p = provider(setOf(id), FakeHttp(mutableMapOf(u to fixture(id, license))))
            assertTrue(p.catalog().isEmpty())
        }
    }

    @Test fun rejectsAnimeKDramaAdultAndNonMovieMetadata() {
        val cases = listOf(
            Triple("anime", "Anime", "movies"), Triple("kd", "Korean drama", "movies"),
            Triple("adult", "adult", "movies"), Triple("audio", "open movie", "audio")
        )
        cases.forEach { (id, subject, type) ->
            val raw = JSONObject(fixture(id = id, subject = subject)).also {
                it.getJSONObject("metadata").put("mediatype", type)
            }.toString()
            val p = provider(setOf(id), FakeHttp(mutableMapOf("https://archive.org/metadata/$id" to raw)))
            assertTrue(p.catalog().isEmpty())
        }
    }

    @Test fun fileCandidatesMustBeBoundedCompleteLocalMp4WithSafeNames() {
        fun file(name: String, source: String = "original", size: Any = "1000", length: Any = "60") =
            JSONObject().put("name", name).put("source", source).put("format", "MPEG4")
                .put("size", size).put("length", length)
        val files = JSONArray()
            .put(file("safe.mp4"))
            .put(file("../escape.mp4"))
            .put(file("folder/escape.mp4"))
            .put(file("torrent.mp4").put("format", "Archive BitTorrent"))
            .put(file("external.mp4").put("url", "https://evil.invalid/x.mp4"))
            .put(file("huge.mp4", size = (512L * 1024 * 1024 + 1).toString()))
            .put(file("zero.mp4", length = "0"))
            .put(file("missing-parent.mp4", source = "derivative").put("original", "none.avi"))
        val id = "safe id"; val url = "https://archive.org/metadata/safe%20id"
        val p = provider(setOf(id), FakeHttp(mutableMapOf(url to fixture(id = id, files = files))))
        val streams = p.resolve(ResolveRequest(p.catalog().single()))
        assertEquals(listOf("https://archive.org/download/safe%20id/safe.mp4"), streams.map { it.url })
    }

    @Test fun foreignCardsEpisodesAndChangedApiIdentityFailClosed() {
        val id = "movie"; val url = "https://archive.org/metadata/movie"
        val http = FakeHttp(mutableMapOf(url to fixture(id)))
        val p = provider(setOf(id), http)
        val card = p.catalog().single()
        listOf(card.copy(id = "archive:other"), card.copy(contentId = "archive:other"),
            card.copy(providerId = "other"), card.copy(section = FeedSection.ANIME),
            card.copy(kind = MediaKind.ANIME)).forEach { foreign ->
            assertThrows(IllegalArgumentException::class.java) { p.details(foreign) }
            assertThrows(IllegalArgumentException::class.java) { p.resolve(ResolveRequest(foreign)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.resolve(ResolveRequest(card, EpisodeRef("e", "e", 1, 1, card.id)))
        }
        http.responses[url] = fixture("changed")
        assertThrows(IllegalArgumentException::class.java) { p.details(card) }
    }

    @Test fun retriesIoAtMostOnceAndDoesNotRetryMalformedMetadata() {
        val id = "retry"; val url = "https://archive.org/metadata/retry"
        var calls = 0
        val client = object : TextHttpClient {
            override fun get(url: String): String {
                calls++
                if (calls == 1) throw IOException("transient")
                return fixture(id)
            }
        }
        assertEquals(1, provider(setOf(id), client).catalog().size)
        assertEquals(2, calls)
        val failed = FakeHttp(mutableMapOf(url to IOException("down")))
        assertThrows(IOException::class.java) { provider(setOf(id), failed).catalog() }
        assertEquals(2, failed.calls.size)
        val malformed = FakeHttp(mutableMapOf(url to "not JSON"))
        assertThrows(IllegalArgumentException::class.java) { provider(setOf(id), malformed).catalog() }
        assertEquals(1, malformed.calls.size)
    }

    @Test fun capabilitiesGateNetworkAndDownloadRightsAreNeverTakenFromCards() {
        val id = "movie"; val url = "https://archive.org/metadata/movie"
        val http = FakeHttp(mutableMapOf(url to fixture(id)))
        val disabled = InternetArchivePlaybackProvider(info.copy(enabled = false), setOf(id), http)
        assertTrue(disabled.catalog().isEmpty())
        assertTrue(http.calls.isEmpty())
        val p = InternetArchivePlaybackProvider(info.copy(capabilities = info.capabilities.copy(download = false)), setOf(id), http)
        val card = p.catalog().single()
        assertFalse(p.resolve(ResolveRequest(card)).single().isDownloadable)
        val stream = p.resolve(ResolveRequest(card)).single()
        val forged = card.copy(streams = listOf(stream.copy(url = "https://evil.invalid/fake.mp4", isDownloadable = true)))
        assertEquals(stream, p.resolve(ResolveRequest(forged)).single())
        http.responses[url] = fixture(id, license = "all rights reserved")
        assertTrue(p.resolve(ResolveRequest(forged)).isEmpty())
        http.responses[url] = JSONObject(fixture(id)).put("is_dark", true).toString()
        assertTrue(p.resolve(ResolveRequest(card)).isEmpty())
        for (section in listOf(FeedSection.ANIME, FeedSection.KDRAMA, FeedSection.ADULT, FeedSection.LIVETV)) {
            assertThrows(IllegalArgumentException::class.java) {
                InternetArchivePlaybackProvider(info.copy(section = section), setOf(id), http)
            }
        }
    }

    @Test fun traversalAmbiguousFilesAndTruncatedDerivativesDoNotResolve() {
        val id = "movie"; val url = "https://archive.org/metadata/movie"
        val badNames = listOf("../a.mp4", "%2e%2e.mp4", "a\\b.mp4", "a.mp4?x", "a.mp4#x", "https://host/a.mp4", "a\u0000.mp4")
        val root = JSONObject(fixture(id))
        val baseline = root.getJSONArray("files").getJSONObject(0)
        val files = root.getJSONArray("files")
        files.put(JSONObject(baseline.toString()).put("length", "10").put("name", "truncated.mp4"))
        badNames.forEach { name -> files.put(JSONObject(baseline.toString()).put("name", name)) }
        // An ambiguous repeated original/derivative filename invalidates that candidate.
        files.put(JSONObject(baseline.toString()))
        val http = FakeHttp(mutableMapOf(url to root.toString()))
        val p = provider(setOf(id), http)
        assertTrue(p.resolve(ResolveRequest(p.catalog().single())).isEmpty())
        val huge = JSONObject(fixture(id))
        huge.getJSONArray("files").getJSONObject(0).put("size", "536870912")
        http.responses[url] = huge.toString()
        assertEquals(1, p.resolve(ResolveRequest(p.catalog().single())).size)
    }

    @Test fun interruptionBeforeOrAfterHttpPreservesThreadCancellation() {
        val id = "movie"; val url = "https://archive.org/metadata/movie"
        val http = FakeHttp(mutableMapOf(url to fixture(id)))
        val p = provider(setOf(id), http)
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) { p.catalog() }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(http.calls.isEmpty())
        } finally { Thread.interrupted() }
        val post = object : TextHttpClient {
            override fun get(url: String): String { Thread.currentThread().interrupt(); return fixture(id) }
        }
        try {
            assertThrows(CancellationException::class.java) { provider(setOf(id), post).catalog() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun constructorAndResponsesAreBoundedAndCancellationIsNotRetried() {
        assertThrows(IllegalArgumentException::class.java) { provider(emptySet(), FakeHttp(mutableMapOf())) }
        assertThrows(IllegalArgumentException::class.java) {
            provider(setOf("../x"), FakeHttp(mutableMapOf()))
        }
        val id = "cancel"; val url = "https://archive.org/metadata/cancel"
        val cancelled = FakeHttp(mutableMapOf(url to CancellationException("stop")))
        assertThrows(CancellationException::class.java) { provider(setOf(id), cancelled).catalog() }
        assertEquals(1, cancelled.calls.size)
        val oversized = FakeHttp(mutableMapOf(url to " ".repeat(2 * 1024 * 1024 + 1)))
        assertThrows(IllegalArgumentException::class.java) { provider(setOf(id), oversized).catalog() }
    }

}
