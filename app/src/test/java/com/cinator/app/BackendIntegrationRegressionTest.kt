package com.cinator.app

import com.cinator.app.data.*
import com.cinator.app.domain.FeedSection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackendIntegrationRegressionTest {
    private fun sourceJson() = JSONObject("""{"id":"fixture","name":"Fixture","section":"HOME","engine":"CINEMETA","endpoints":["https://catalog.example.com/manifest.json"],"enabled":true}""")
    private fun registry(source: JSONObject = sourceJson()) = JSONObject().put("schemaVersion", 1)
        .put("bundleVersion", "0.3.0").put("sources", JSONArray().put(source))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid input to be rejected") } catch (_: Exception) { }
    }

    @Test fun manifestLoadsDeclaredMovieAndSeriesCatalogsAndSearchRoutes() {
        val endpoint = "https://catalog.example.com/addon/manifest.json"
        val manifest = """{"id":"fixture","version":"1.0.0","name":"Fixture","resources":["catalog","meta"],"types":["movie","series"],"catalogs":[{"type":"movie","id":"top","extra":[{"name":"search"}]},{"type":"series","id":"top","extra":[{"name":"search"}]}]}"""
        val movie = """{"metas":[{"id":"movie1","type":"movie","name":"Movie"}]}"""
        val series = """{"metas":[{"id":"series1","type":"series","name":"Series","country":"United States","genres":["Drama"]}]}"""
        val fixtures = mapOf(endpoint to manifest,
            "https://catalog.example.com/addon/catalog/movie/top.json" to movie,
            "https://catalog.example.com/addon/catalog/series/top.json" to series,
            "https://catalog.example.com/addon/catalog/movie/top/search=Star%20%26%20Moon%2F%E6%97%A5.json" to movie,
            "https://catalog.example.com/addon/catalog/series/top/search=Star%20%26%20Moon%2F%E6%97%A5.json" to series)
        val calls = mutableListOf<String>()
        val http = object : TextHttpClient {
            override fun get(url: String): String { calls.add(url); return fixtures[url] ?: error("Unexpected fixture request") }
        }
        val bundle = EmbeddedCatalog(listOf(EmbeddedSource("fixture", "Fixture", FeedSection.HOME, "CINEMETA", listOf(endpoint), true, "Metadata only")))
        val result = CatalogRepository(bundle, http).load(FeedSection.HOME)
        assertEquals(listOf("Movie", "Series"), result.items.map { it.title })
        assertTrue(result.failures.isEmpty())
        assertTrue(result.items.all { it.streams.isEmpty() })
        assertEquals(3, calls.size)
        calls.clear()
        val searched = CatalogRepository(bundle, http).load(FeedSection.HOME, "Star & Moon/日")
        assertEquals(2, searched.items.size)
        assertEquals(fixtures.keys.filter { it == endpoint || it.contains("search=") }, calls)
    }

    @Test fun invalidProviderSchemaIsFailureNotEmptySuccess() {
        listOf("", "[]", "{}", "{\"metas\":{}}", "{\"metas\":[{}]}").forEach { raw ->
            rejects { CinemetaCatalogParser.parse(raw) }
        }
        listOf("{}", "{\"resources\":[\"catalog\"],\"types\":[\"movie\"],\"catalogs\":[]}").forEach { raw ->
            rejects { CinemetaManifestParser.parse(raw) }
        }
        assertTrue(CinemetaCatalogParser.parse("{\"metas\":[]}").isEmpty())
    }

    @Test fun actualAssetFetchesAllFiveIptvCategories() {
        val asset = listOf(java.io.File("app/src/main/assets/embedded_catalog.json"), java.io.File("src/main/assets/embedded_catalog.json")).first { it.exists() }
        val bundle = EmbeddedCatalogParser.parse(asset.readText())
        val endpoints = bundle.sources.filter { it.enabled && it.engine == "IPTV" }.flatMap { it.endpoints }
        assertEquals(5, endpoints.size)
        val calls = mutableListOf<String>()
        val http = object : TextHttpClient {
            override fun get(url: String): String {
                calls.add(url)
                val index = endpoints.indexOf(url)
                check(index >= 0)
                return "#EXTM3U\n#EXTINF:-1,Fixture category $index\nhttps://stream.example.com/category$index.m3u8"
            }
        }
        val result = CatalogRepository(bundle, http).load(FeedSection.LIVETV)
        assertEquals(endpoints, calls)
        assertEquals(5, result.items.size)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun allIptvCategoriesAreLoadedAndPartialFailuresCountProvidersOnce() {
        val endpoints = listOf("news", "sports", "music").map { "https://playlist.example.com/$it.m3u" }
        val calls = mutableListOf<String>()
        val source = EmbeddedSource("live", "Live", FeedSection.LIVETV, "IPTV", endpoints, true, "Unverified playlist")
        val http = object : TextHttpClient {
            override fun get(url: String): String {
                calls.add(url)
                if (url != endpoints[0]) error("https://secret:password@example.com/private")
                return "#EXTM3U\n#EXTINF:-1 tvg-id=\"news\",News\nhttps://stream.example.com/news.m3u8"
            }
        }
        val result = CatalogRepository(EmbeddedCatalog(listOf(source)), http).load(FeedSection.LIVETV)
        assertEquals(endpoints, calls)
        assertEquals(1, result.items.size)
        assertEquals(1, result.sourceCount)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().contains("2/3"))
        assertFalse(result.failures.toString().contains("password"))
    }

    @Test fun catalogFailurePreservesCompletedCatalogsWithinProvider() {
        val manifest = """{"id":"fixture","version":"1","name":"Fixture","resources":["catalog"],"types":["movie","series"],"catalogs":[{"type":"movie","id":"top"},{"type":"series","id":"top"}]}"""
        val source = EmbeddedSource("partial", "Partial", FeedSection.HOME, "CINEMETA", listOf("https://catalog.example.com/manifest.json"), true, "Metadata")
        val http = object : TextHttpClient {
            override fun get(url: String): String = when {
                url.endsWith("manifest.json") -> manifest
                url.contains("/movie/") -> """{"metas":[{"id":"one","type":"movie","name":"Completed"}]}"""
                else -> "{\"bad\":true}"
            }
        }
        val result = CatalogRepository(EmbeddedCatalog(listOf(source)), http).load(FeedSection.HOME)
        assertEquals("Completed", result.items.single().title)
        assertEquals(1, result.failures.size)
    }

    @Test fun registryIsStrictAndDisabledByDefault() {
        val invalid = listOf(
            registry().put("schemaVersion", 2), registry().put("schemaVersion", "1"),
            registry().also { it.remove("schemaVersion") }, registry().put("sources", "wrong"),
            registry(sourceJson().put("section", "HMOE")), registry(sourceJson().put("id", "")),
            registry(sourceJson().put("id", "  ")),
            registry().put("sources", JSONArray().put(sourceJson()).put(sourceJson())),
            registry(sourceJson().put("engine", "EXECUTE_PLUGIN")),
            registry(sourceJson().put("endpoints", JSONArray())),
            registry(sourceJson().put("endpoints", JSONArray().put("https://[::1]/catalog"))),
            registry(sourceJson().put("enabled", "true"))
        )
        invalid.forEach { value -> rejects { EmbeddedCatalogParser.parse(value.toString()) } }
        val missingEnabled = sourceJson().also { it.remove("enabled") }
        assertFalse(EmbeddedCatalogParser.parse(registry(missingEnabled).toString()).sources.single().enabled)
        assertFalse(EmbeddedCatalogParser.parse(registry(sourceJson().put("enabled", false).put("engine", "REFERENCE")).toString()).sources.single().enabled)
    }
}
