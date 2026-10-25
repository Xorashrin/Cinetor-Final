package com.cinator.app.providers

import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** API fixtures are unmodified responses saved from the official film UUID endpoints.
 * Registry fixture is projected from the independently audited film-streams evidence. */
class BlenderPlaybackProviderTest {
    private fun resource(name: String): String = listOf(File("app/src/test/resources/blender/$name"),
        File("src/test/resources/blender/$name")).first { it.isFile }.readText()
    private fun registry() = JSONObject(resource("audited-registry.json"))
    private fun api(uuid: String) = JSONObject(resource("$uuid.json"))
    private fun provider(raw: String = registry().toString(), get: (String) -> String): PlaybackProvider {
        val client = object : TextHttpClient { override fun get(url: String) = get(url) }
        return BundledPlaybackProviders.fromJson(raw, client).single()
    }

    @Test fun actualBundledAssetHasRealCatalogDescriptionsWithoutApiCalls() {
        val asset = listOf(File("app/src/main/assets/open_films.json"), File("src/main/assets/open_films.json"))
            .first { it.isFile }.readText()
        val p = provider(asset) { fail("Catalog must not fetch APIs"); "" }
        assertEquals(4, p.catalog().size)
        p.catalog().forEach { card ->
            val raw = api(card.contentId!!.substringAfter(':')).getString("description")
            val paragraphs = raw.split("\r\n\r\n").filter { it.length > 150 }
            assertTrue("Actual official synopsis required for ${card.title}", paragraphs.any { card.synopsis.contains(it) })
            assertTrue(card.synopsis.contains(card.title))
        }
    }

    @Test fun playlistFilesStillRequireSameOfficialCompleteMp4Identity() {
        val p = provider { url -> api(url.substringAfterLast('/')).apply {
            val original = getJSONArray("files")
            put("files", org.json.JSONArray())
            put("streamingPlaylists", org.json.JSONArray().put(JSONObject().put("files", original)))
        }.toString() }
        val card = p.catalog().first()
        assertEquals(3, p.resolve(ResolveRequest(card)).size)
    }

    @Test fun unavailableApiCannotReturnBundledOrInjectedFallback() {
        val p = provider { throw java.io.IOException("unavailable") }
        val card = p.catalog().first()
        assertThrows(java.io.IOException::class.java) { p.details(card) }
        assertThrows(java.io.IOException::class.java) { p.resolve(ResolveRequest(card)) }
        val empty = provider { url -> api(url.substringAfterLast('/')).put("files", org.json.JSONArray()).toString() }
        assertTrue(empty.resolve(ResolveRequest(empty.catalog().first())).isEmpty())
    }

    @Test fun foreignApiProvenanceAndIncompleteFilmsFailClosed() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("uuid", "0eb052d0-fd51-43e6-aa33-ecdbf77a5d40") },
            { it.put("url", "https://video.blender.org/videos/watch/other") },
            { it.put("isLocal", false) }, { it.put("isLive", true) },
            { it.getJSONObject("account").put("name", "not-blender") },
            { it.getJSONObject("channel").put("host", "example.invalid") },
            { it.put("duration", 30) },
            { it.getJSONObject("privacy").put("id", 3) },
            { it.put("blacklisted", true) }
        )
        edits.forEach { edit ->
            val p = provider { url -> api(url.substringAfterLast('/')).also(edit).toString() }
            val card = p.catalog().first()
            assertThrows(IllegalArgumentException::class.java) { p.details(card) }
            assertThrows(IllegalArgumentException::class.java) { p.resolve(ResolveRequest(card)) }
        }
    }

    @Test fun exactFileProvenanceAndDefaultQualitySurviveSameFilmFallback() {
        val p = provider { url ->
            val d = api(url.substringAfterLast('/'))
            d.getJSONArray("files").getJSONObject(0).put("fileUrl", "https://example.invalid/unrelated.mp4")
            d.toString()
        }
        val card = p.catalog().first()
        assertEquals(listOf("480p", "360p", "240p"), p.resolve(ResolveRequest(card)).map { it.quality })
        val uuid = card.contentId!!.substringAfter(':')
        val badUrls = listOf("https://video.blender.org/object-storage/web_videos/$uuid-720.mp4?token=x",
            "https://video.blender.org/object-storage/web_videos/other-720.mp4",
            "https://video.blender.org.evil.invalid/object-storage/web_videos/$uuid-720.mp4",
            "https://video.blender.org/object-storage/web_videos/$uuid-720.mp4#t=10,20",
            "https://video.blender.org/object-storage/web_videos/%2e%2e/$uuid-720.mp4")
        badUrls.forEach { bad ->
            val q = provider { url -> api(url.substringAfterLast('/')).apply {
                val only = getJSONArray("files").getJSONObject(0).put("fileUrl", bad)
                put("files", org.json.JSONArray().put(only))
            }.toString() }
            assertTrue(q.resolve(ResolveRequest(q.catalog().first())).isEmpty())
        }
    }

    @Test fun permissionRequiresAuditedCcAndCompleteCreditsNotApiLicenseLabel() {
        listOf(null, "", "https://example.invalid/rights", "https://creativecommons.org/licenses/by/4.0/").forEach { license ->
            val r = registry()
            r.getJSONArray("films").getJSONObject(0).put("licenseUrl", license ?: JSONObject.NULL)
            val p = provider(r.toString()) { url -> api(url.substringAfterLast('/')).toString() }
            assertFalse(p.resolve(ResolveRequest(p.catalog().first())).first().isDownloadable)
        }
        val r = registry()
        r.getJSONArray("films").getJSONObject(0).put("preserveCompleteEndCredits", false)
        val p = provider(r.toString()) { url -> api(url.substringAfterLast('/')).toString() }
        assertFalse(p.resolve(ResolveRequest(p.catalog().first())).first().isDownloadable)
        val disabled = provider { url -> api(url.substringAfterLast('/')).put("downloadEnabled", false).toString() }
        assertFalse(disabled.resolve(ResolveRequest(disabled.catalog().first())).first().isDownloadable)
    }

    @Test fun wrongSelectionCannotInjectMediaOrBorrowAnotherFilmsIdentity() {
        val p = provider { url -> api(url.substringAfterLast('/')).toString() }
        val a = p.catalog().first()
        listOf(a.copy(id = "sintel"), a.copy(contentId = "foreign"), a.copy(providerId = "foreign"),
            a.copy(section = FeedSection.ANIME), a.copy(kind = MediaKind.SERIES)).forEach {
            assertThrows(IllegalArgumentException::class.java) { p.details(it) }
            assertThrows(IllegalArgumentException::class.java) { p.resolve(ResolveRequest(it)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.resolve(ResolveRequest(a, EpisodeRef("e", "e", 1, 1, a.contentId!!)))
        }
        val source = p.resolve(ResolveRequest(a)).first()
        assertEquals(source, p.resolve(ResolveRequest(a.copy(streams = listOf(source.copy(url = "https://example.invalid/fake.mp4"))))).first())
    }

    @Test fun rejectsTamperedRegistryBeforeAnyNetworkCall() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("uuid", "bf1f3fb5-b119-4f9f-9930-8e20e892b898 ") },
            { it.put("apiUrl", "https://example.invalid/api") }, { it.put("title", "Sintel") },
            { it.put("streamUrl", "https://example.invalid/film.mp4") },
            { it.put("attributionUrl", "https://mango.blender.org/sharing/") },
            { it.put("attribution", "") }, { it.put("durationMs", 0) })
        edits.forEach { edit ->
            val r = registry(); edit(r.getJSONArray("films").getJSONObject(0))
            assertThrows(IllegalArgumentException::class.java) { provider(r.toString()) { fail("must not fetch"); "" } }
        }
        val duplicate = registry(); duplicate.getJSONArray("films").put(1, duplicate.getJSONArray("films").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { provider(duplicate.toString()) { "" } }
    }

    @Test fun cancellationPropagatesBeforeAndAfterHttpWithoutLosingInterrupt() {
        val p = provider { throw java.util.concurrent.CancellationException("cancelled") }
        val c = p.catalog().first()
        assertThrows(java.util.concurrent.CancellationException::class.java) { p.resolve(ResolveRequest(c)) }
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) { p.catalog() }
            assertThrows(java.util.concurrent.CancellationException::class.java) { p.details(c) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        val after = provider { url -> Thread.currentThread().interrupt(); api(url.substringAfterLast('/')).toString() }
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) { after.resolve(ResolveRequest(c)) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun allFourAuditedFilmsResolveFreshOfficialApiWithExactIdentityAndCredits() {
        val calls = mutableListOf<String>()
        val p = provider { url -> calls += url; api(url.substringAfterLast('/')).toString() }
        val records = registry().getJSONArray("films")
        val cards = p.catalog()
        assertEquals(4, cards.size)
        assertEquals(4, cards.map { it.contentId }.toSet().size)
        assertTrue(calls.isEmpty())
        cards.forEachIndexed { index, card ->
            val record = records.getJSONObject(index)
            val uuid = record.getString("uuid")
            val response = api(uuid)
            assertEquals(record.getString("title"), card.title)
            assertEquals(record.getInt("year").toString(), card.year)
            assertEquals("blender:$uuid", card.contentId)
            assertEquals(FeedSection.HOME, card.section)
            assertEquals(MediaKind.MOVIE, card.kind)
            assertNull(card.rating)
            assertTrue(card.streams.isEmpty())
            assertTrue(card.synopsis.contains(record.getString("attribution")))
            assertTrue(card.synopsis.contains(record.getString("licenseUrl")))
            assertEquals(record.getString("attributionUrl"), card.externalUrl)
            assertEquals(listOf(card), p.catalog("  ${card.title.uppercase()}  "))
            val details = p.details(card)
            assertEquals(card.contentId, details.card.contentId)
            assertEquals(response.getString("description"), details.card.synopsis.substringBefore("\n\nAttribution:"))
            assertEquals("https://video.blender.org" + response.getString("previewPath"), details.card.posterUrl)
            assertTrue(details.episodes.isEmpty())
            val sources = p.resolve(ResolveRequest(details.card))
            assertEquals(3, sources.size)
            assertEquals(record.getString("streamUrl"), sources.first().url)
            assertEquals("blender:$uuid:${record.getInt("height")}", sources.first().id)
            sources.forEach {
                assertEquals(card.contentId, it.contentId)
                assertEquals(card.providerId, it.providerId)
                assertEquals(card.section, it.section)
                assertEquals(StreamProtocol.MP4, it.protocol)
                assertTrue(it.isDownloadable)
                assertTrue(it.headers.isEmpty())
                assertFalse(it.isLive)
            }
            p.resolve(ResolveRequest(card))
            assertEquals(3, calls.count { it == record.getString("apiUrl") })
        }
        assertTrue(p.catalog("not a film title").isEmpty())
    }
}
