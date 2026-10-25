package com.cinator.app

import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.providers.ProviderCapabilities
import com.cinator.app.providers.ProviderInfo
import com.cinator.app.providers.ResolveRequest
import com.cinator.app.providers.StremioPlaybackProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fake transport only: production adapter and domain models, no public addon activation. */
class StremioProtocolRegressionTest {
    private val base = "https://addon.example.org/regression"
    private val manifestUrl = "$base/manifest.json"
    private val streamUrl = "$base/stream/movie/film%3Aone.json"
    private val directUrl = "https://media.example.org/film.mp4"
    private val info = ProviderInfo("regression", "Regression fixture", FeedSection.HOME,
        ProviderCapabilities(playback = true, download = true), "https://addon.example.org")
    private val card = MediaCard("film:one", "Fixture film", "", "", FeedSection.HOME,
        info.id, info.name, kind = MediaKind.MOVIE, contentId = "film:one")

    private class FixtureHttp(val responses: MutableMap<String, String>) : TextHttpClient {
        val calls = mutableListOf<String>()
        override fun get(url: String): String {
            calls += url
            return responses[url] ?: error("Unexpected fixture request: $url")
        }
    }
    private fun manifest(): JSONObject = JSONObject("""{
        "id":"org.example.regression","name":"Fixture","version":"1",
        "types":["movie","series"],"idPrefixes":["film:"],
        "resources":["catalog","meta","stream"],
        "catalogs":[{"type":"movie","id":"curated"}]
    }""")
    private fun fixture(root: JSONObject = manifest(), streams: String = """{"streams":[]}"""): FixtureHttp =
        FixtureHttp(mutableMapOf(manifestUrl to root.toString(), streamUrl to streams,
            "$base/catalog/movie/curated.json" to """{"metas":[{"id":"film:one","type":"movie","name":"Fixture film"}]}"""))
    private fun adapter(http: FixtureHttp) = StremioPlaybackProvider(info, manifestUrl, http)
    private fun rejected(label: String, block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        catch (_: org.json.JSONException) { return }
        fail("Expected malformed addon rejection: $label")
    }
    private fun assertOnlyManifest(http: FixtureHttp) = assertEquals(listOf(manifestUrl), http.calls)

    @Test fun malformedResourceValuesAreRejectedBeforeResourceRequests() {
        for (raw in listOf("null", "true", "17", "[]", "{}", "{\"name\":17}")) {
            val root = manifest().put("resources", JSONArray("[$raw]"))
            val http = fixture(root)
            rejected(raw) { adapter(http).resolve(ResolveRequest(card)) }
            assertOnlyManifest(http)
        }
    }

    @Test fun malformedManifestStringArraysAreRejectedBeforeResourceRequests() {
        for (key in listOf("types", "idPrefixes")) {
            for (raw in listOf("null", "{}", "\"movie\"", "[null]", "[4]", "[\"\"]", "[\" film:\"]", "[\"film:\\n\"]")) {
                val root = manifest().put(key, JSONObject("{\"value\":$raw}").get("value"))
                val http = fixture(root)
                rejected("$key=$raw") { adapter(http).resolve(ResolveRequest(card)) }
                assertOnlyManifest(http)
            }
        }
    }

    @Test fun malformedResourceTypesAndPrefixesAreRejectedBeforeResourceRequests() {
        for (key in listOf("types", "idPrefixes")) {
            for (raw in listOf("null", "{}", "\"movie\"", "[false]", "[\"\"]")) {
                val resource = JSONObject().put("name", "stream")
                    .put(key, JSONObject("{\"value\":$raw}").get("value"))
                val http = fixture(manifest().put("resources", JSONArray().put(resource)))
                rejected("resource $key=$raw") { adapter(http).resolve(ResolveRequest(card)) }
                assertOnlyManifest(http)
            }
        }
    }

    @Test fun resourceCannotAdvertiseTypeAbsentFromManifest() {
        val root = manifest().put("resources", JSONArray("""[{"name":"stream","types":["unknown"]}]"""))
        val http = fixture(root)
        rejected("undeclared resource type") { adapter(http).resolve(ResolveRequest(card)) }
        assertOnlyManifest(http)
    }

    @Test fun emptyResourceTypesDisableTheRoute() {
        val http = fixture(manifest().put("resources", JSONArray("""[{"name":"stream","types":[]}]""")))
        assertTrue(adapter(http).resolve(ResolveRequest(card)).isEmpty())
        assertOnlyManifest(http)
    }

    @Test fun emptyAndNonmatchingPrefixesDisableTheRoute() {
        for (prefixes in listOf("[]", "[\"other:\"]", "[\"Film:\"]")) {
            val http = fixture(manifest().put("idPrefixes", JSONArray(prefixes)))
            assertTrue(adapter(http).resolve(ResolveRequest(card)).isEmpty())
            assertOnlyManifest(http)
        }
    }

    @Test fun explicitResourcePrefixOverridesManifestPrefix() {
        val root = manifest().put("idPrefixes", JSONArray("""["other:"]"""))
            .put("resources", JSONArray("""[{"name":"stream","types":["movie"],"idPrefixes":["film:"]}]"""))
        val http = fixture(root, """{"streams":[{"url":"$directUrl"}]}""")
        assertEquals(card.id, adapter(http).resolve(ResolveRequest(card)).single().contentId)
        assertEquals(listOf(manifestUrl, streamUrl), http.calls)
    }

    @Test fun absentResourcePrefixInheritsManifestPrefix() {
        val root = manifest().put("idPrefixes", JSONArray("""["other:"]"""))
            .put("resources", JSONArray("""[{"name":"stream","types":["movie"]}]"""))
        val http = fixture(root)
        assertTrue(adapter(http).resolve(ResolveRequest(card)).isEmpty())
        assertOnlyManifest(http)
    }

    @Test fun catalogDoesNotInheritContentIdPrefixes() {
        val http = fixture()
        assertEquals(card.id, adapter(http).catalog().single().id)
        assertEquals(listOf(manifestUrl, "$base/catalog/movie/curated.json"), http.calls)
    }

    @Test fun explicitCatalogPrefixRestrictsCatalogIdentity() {
        val root = manifest().put("resources", JSONArray("""[{"name":"catalog","types":["movie"],"idPrefixes":["other:"]}]"""))
        val http = fixture(root)
        assertTrue(adapter(http).catalog().isEmpty())
        assertOnlyManifest(http)
    }

    private fun assertUnsupportedFields(fields: List<String>) {
        for (field in fields) {
            val entry = JSONObject("{$field}").put("url", directUrl)
            val http = fixture(streams = JSONObject().put("streams", JSONArray().put(entry)).toString())
            assertTrue("Unsupported field escaped: $field", adapter(http).resolve(ResolveRequest(card)).isEmpty())
            assertEquals(listOf(manifestUrl, streamUrl), http.calls)
        }
    }

    @Test fun proxyAndHeaderDeclarationsAreNotSilentlyStrippedIntoPlayableUrls() {
        assertUnsupportedFields(listOf(
            "\"headers\":{}", "\"HEADERS\":null", "\"proxyHeaders\":{}",
            "\"behaviorHints\":{\"proxyHeaders\":{\"request\":{\"Authorization\":\"fixture-secret\"},\"response\":{\"Set-Cookie\":\"fixture\"}}}",
            "\"behaviorHints\":{\"nested\":[[ {\"PrOxYhEaDeRs\":{}} ]]}",
            "\"behaviorHints\":{\"nested\":[{\"headers\":{\"Referer\":\"https://media.example.org/\"}}]}"))
    }

    @Test fun drmDeclarationsAreRejectedEvenWhenNestedOrEmpty() {
        assertUnsupportedFields(listOf("\"drm\":{}", "\"drmType\":\"widevine\"",
            "\"drmLicenseUrl\":\"https://media.example.org/license\"", "\"licenseUrl\":null",
            "\"licenseKey\":\"fixture\"", "\"widevine\":{}", "\"clearKey\":{}",
            "\"behaviorHints\":{\"nested\":[[{\"DrM\":null}]]}"))
    }

    @Test fun torrentAndExternalDeclarationsCannotBecomeDirectPlayback() {
        assertUnsupportedFields(listOf("\"infoHash\":\"fixture\"", "\"torrent\":{}", "\"webtorrent\":{}",
            "\"magnet\":\"magnet:?xt=fixture\"", "\"sources\":[]", "\"fileIdx\":0",
            "\"externalUrl\":\"https://media.example.org/watch\"", "\"ytId\":\"fixture\"",
            "\"behaviorHints\":{\"nested\":[[{\"ExTeRnAlUrL\":null}]]}"))
    }

    @Test fun notWebReadyIsRejectedInsideBehaviorHints() {
        assertUnsupportedFields(listOf("\"notWebReady\":true", "\"behaviorHints\":{\"notWebReady\":true}"))
    }

    @Test fun malformedStreamShapesAreExplicitlyRejected() {
        for (body in listOf("not json", "[]", "{}", "{\"streams\":null}", "{\"streams\":{}}",
            "{\"streams\":[null]}", "{\"streams\":[17]}", "{\"streams\":[{\"url\":17}]}")) {
            val http = fixture(streams = body)
            rejected(body) { adapter(http).resolve(ResolveRequest(card)) }
            assertEquals(listOf(manifestUrl, streamUrl), http.calls)
        }
    }

    @Test fun unsupportedEntriesDoNotPoisonValidIdentityPreservingDirectCandidates() {
        val http = fixture(streams = """{"streams":[
            {"url":"$directUrl","infoHash":"fixture"},
            {"url":"$directUrl","contentId":"film:other"},
            {"url":"$directUrl","contentId":"film:one","behaviorHints":{"notWebReady":false}},
            {"url":"$directUrl"},
            {"url":"https://media.example.org/film.M3U8?token=fixture"}
        ]}""")
        val sources = adapter(http).resolve(ResolveRequest(card))
        assertEquals(listOf(directUrl, "https://media.example.org/film.M3U8?token=fixture"), sources.map { it.url })
        assertEquals(listOf(StreamProtocol.MP4, StreamProtocol.HLS_M3U8), sources.map { it.protocol })
        for (source in sources) {
            assertEquals(card.contentId, source.contentId)
            assertEquals(info.id, source.providerId)
            assertEquals(FeedSection.HOME, source.section)
            assertTrue(source.headers.isEmpty())
            assertFalse(source.isDownloadable)
        }
    }

    @Test fun malformedConfigurationRequirementCannotBeIgnored() {
        for (raw in listOf("0", "null", "\"false\"", "{}", "[]")) {
            val hints = JSONObject("{\"configurationRequired\":$raw}")
            val http = fixture(manifest().put("behaviorHints", hints))
            rejected("configurationRequired=$raw") { adapter(http).resolve(ResolveRequest(card)) }
            assertOnlyManifest(http)
        }
    }

    @Test fun requiredPrivateConfigurationRejectsBeforeResourceRequests() {
        val http = fixture(manifest().put("behaviorHints", JSONObject().put("configurationRequired", true)))
        rejected("private configuration required") { adapter(http).resolve(ResolveRequest(card)) }
        assertOnlyManifest(http)
    }
}
