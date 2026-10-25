package com.cinator.app

import com.cinator.app.data.BoundedHttpClient
import com.cinator.app.data.CatalogLoadResult
import com.cinator.app.data.CatalogRepository
import com.cinator.app.data.CinemetaCatalogParser
import com.cinator.app.data.CinemetaManifestParser
import com.cinator.app.data.EmbeddedCatalog
import com.cinator.app.data.EmbeddedCatalogParser
import com.cinator.app.data.EmbeddedSource
import com.cinator.app.data.MultiCategoryIptvParser
import com.cinator.app.data.OpenVideoCatalogParser
import com.cinator.app.data.TVMazeCatalogParser
import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogTddTest {

    @Test
    fun testEmbeddedCatalogParser_parsesValidSchema() {
        val json = """
            {
              "schemaVersion": 1,
              "bundleVersion": "0.3.0",
              "licenseNote": "Public Catalog",
              "sources": [
                {
                  "id": "cinemeta-home",
                  "name": "Cinemeta Top Movies",
                  "section": "HOME",
                  "engine": "CINEMETA",
                  "endpoints": ["https://v3-cinemeta.strem.io/catalog/movie/top.json"],
                  "enabled": true,
                  "capabilityNote": "Official Stremio movie metadata catalog; declares catalog/meta, not stream",
                  "licenseNote": "Public Stremio Community Catalog"
                },
                {
                  "id": "tvmaze-anime",
                  "name": "TVMaze Anime Search",
                  "section": "ANIME",
                  "engine": "TVMAZE",
                  "endpoints": ["https://api.tvmaze.com/search/shows?q=anime"],
                  "enabled": true,
                  "capabilityNote": "Live public anime metadata API"
                }
              ]
            }
        """.trimIndent()

        val catalog = EmbeddedCatalogParser.parse(json)
        assertEquals(1, catalog.schemaVersion)
        assertEquals("0.3.0", catalog.bundleVersion)
        assertEquals(2, catalog.sources.size)

        val s1 = catalog.sources[0]
        assertEquals("cinemeta-home", s1.id)
        assertEquals(FeedSection.HOME, s1.section)
        assertEquals("CINEMETA", s1.engine)
        assertTrue(s1.enabled)
        assertEquals("Public Stremio Community Catalog", s1.licenseNote)
    }

    @Test
    fun testCinemetaCatalogParser_extractsItemsAndEnforcesNoStream() {
        val sampleJson = """
            {
              "metas": [
                {
                  "id": "tt0111161",
                  "type": "movie",
                  "name": "The Shawshank Redemption",
                  "genres": ["Drama"],
                  "poster": "https://images.metahub.space/poster/medium/tt0111161/img",
                  "background": "https://images.metahub.space/background/medium/tt0111161/img",
                  "description": "Two imprisoned men bond over a number of years.",
                  "releaseInfo": "1994",
                  "imdbRating": "9.3"
                }
              ]
            }
        """.trimIndent()

        val items = CinemetaCatalogParser.parse(sampleJson, FeedSection.HOME, "cinemeta-home", "Cinemeta Top Movies")
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("tt0111161", item.id)
        assertEquals("The Shawshank Redemption", item.title)
        assertEquals("1994", item.year)
        assertEquals("9.3", item.rating)
        assertEquals(listOf("Drama"), item.genres)
        assertEquals(FeedSection.HOME, item.section)
        // CRITICAL CONTRACT: Cinemeta declares catalog/meta NOT stream. No fictional media fallback!
        assertTrue("Cinemeta items must not have fictional streams", item.streams.isEmpty())
    }

    @Test
    fun testCinemetaManifestParser_validatesNoStreamDeclaration() {
        val manifestJson = """
            {
              "id": "com.linvo.cinemeta",
              "version": "3.0.14",
              "name": "Cinemeta",
              "resources": ["catalog", "meta", "addon_catalog"],
              "types": ["movie", "series"],
              "catalogs": [{"type":"movie","id":"top"},{"type":"series","id":"top"}]
            }
        """.trimIndent()

        val manifest = CinemetaManifestParser.parse(manifestJson)
        assertEquals("Cinemeta", manifest.name)
        assertTrue(manifest.resources.contains("catalog"))
        assertTrue(manifest.resources.contains("meta"))
        assertFalse("Manifest must not declare stream capability", manifest.resources.contains("stream"))
    }

    @Test
    fun testTVMazeCatalogParser_classifiesAnimeAndKdramaStrictly() {
        val animeShowJson = """
            [
              {
                "score": 1.5,
                "show": {
                  "id": 1375,
                  "name": "Naruto",
                  "type": "Animation",
                  "language": "Japanese",
                  "genres": ["Action", "Adventure", "Anime", "Fantasy"],
                  "premiered": "2002-10-03",
                  "rating": {"average": 8.3},
                  "network": {"country": {"name": "Japan", "code": "JP"}},
                  "summary": "<p>Naruto Uzumaki is a ninja.</p>"
                }
              },
              {
                "score": 1.1,
                "show": {
                  "id": 9999,
                  "name": "Western Cartoon",
                  "type": "Animation",
                  "language": "English",
                  "genres": ["Comedy"],
                  "premiered": "2020-01-01",
                  "rating": {"average": 6.0},
                  "network": {"country": {"name": "United States", "code": "US"}},
                  "summary": "<p>A cartoon show.</p>"
                }
              }
            ]
        """.trimIndent()

        // In ANIME section, only genuine Anime should pass; Western cartoon must be filtered out
        val animeItems = TVMazeCatalogParser.parse(animeShowJson, FeedSection.ANIME, "tvmaze-anime", "TVMaze Anime")
        assertEquals(1, animeItems.size)
        assertEquals("Naruto", animeItems[0].title)
        assertEquals("Naruto Uzumaki is a ninja.", animeItems[0].synopsis)
        assertTrue(animeItems[0].streams.isEmpty())

        val kdramaShowJson = """
            [
              {
                "score": 2.0,
                "show": {
                  "id": 22998,
                  "name": "Goblin",
                  "type": "Scripted",
                  "language": "Korean",
                  "genres": ["Drama", "Romance", "Fantasy"],
                  "premiered": "2016-12-02",
                  "rating": {"average": 8.7},
                  "network": {"name": "tvN", "country": {"name": "Korea, Republic of", "code": "KR"}},
                  "summary": "<p>Kim Shin is an immortal goblin.</p>"
                }
              },
              {
                "score": 1.0,
                "show": {
                  "id": 1375,
                  "name": "Naruto",
                  "type": "Animation",
                  "language": "Japanese",
                  "genres": ["Action", "Adventure", "Anime"],
                  "premiered": "2002-10-03",
                  "rating": {"average": 8.3},
                  "network": {"country": {"name": "Japan", "code": "JP"}},
                  "summary": "<p>Naruto Uzumaki is a ninja.</p>"
                }
              }
            ]
        """.trimIndent()

        // In KDRAMA section, Naruto (Anime) must be filtered out; only Korean drama permitted
        val kdramaItems = TVMazeCatalogParser.parse(kdramaShowJson, FeedSection.KDRAMA, "tvmaze-kdrama", "TVMaze K-Drama")
        assertEquals(1, kdramaItems.size)
        assertEquals("Goblin", kdramaItems[0].title)
        assertEquals(FeedSection.KDRAMA, kdramaItems[0].section)
        assertTrue(kdramaItems[0].streams.isEmpty())
    }

    @Test
    fun testMultiCategoryIptvParser_extractsValidHttpsLiveStreams() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ADNTV.fr" tvg-country="FR" tvg-language="fra" tvg-logo="https://i.imgur.com/HQZQyWt.png" group-title="Animation;Kids",ADN TV+ (720p)
            https://stream.example.com/live/adn.m3u8
            #EXTINF:-1 tvg-id="Insecure.tv" group-title="Animation",Insecure Channel
            http://insecure.example.com/live.m3u8
            #EXTINF:-1 tvg-id="RawIp.tv" group-title="Animation",Raw IP Channel
            https://192.168.1.100:8080/live.m3u8
        """.trimIndent()

        val items = MultiCategoryIptvParser.parse(m3uContent, FeedSection.LIVETV, "iptv-org", "IPTV-org")
        assertEquals(1, items.size)
        val channel = items[0]
        assertEquals("ADN TV+ (720p)", channel.title)
        assertTrue(channel.genres.contains("Animation"))
        assertTrue(channel.genres.contains("Kids"))
        assertEquals(1, channel.streams.size)
        val stream = channel.streams[0]
        assertEquals(StreamProtocol.HLS_M3U8, stream.protocol)
        assertTrue(stream.isLive)
        assertFalse(stream.isDownloadable)
        assertEquals("https://stream.example.com/live/adn.m3u8", stream.url)
    }

    @Test
    fun testOpenVideoCatalogParser_allowsHomeSampleOnlyAndRejectsAnimeOrKdrama() {
        // In HOME section, returns honestly-labeled MDN CC0 flower sample
        val homeItems = OpenVideoCatalogParser.parse(section = FeedSection.HOME)
        assertEquals(1, homeItems.size)
        val item = homeItems[0]
        assertEquals("MDN CC0 Flower Sample Video", item.title)
        assertEquals(MediaKind.VIDEO, item.kind)
        assertEquals(FeedSection.HOME, item.section)
        assertEquals(1, item.streams.size)
        assertEquals(StreamProtocol.MP4, item.streams[0].protocol)
        assertEquals(OpenVideoCatalogParser.MDN_FLOWER_URL, item.streams[0].url)

        // In ANIME or KDRAMA section, OPEN_VIDEO must NEVER disguise test video as anime/KDrama!
        val animeItems = OpenVideoCatalogParser.parse(section = FeedSection.ANIME)
        assertTrue("OPEN_VIDEO must reject ANIME feed section", animeItems.isEmpty())

        val kdramaItems = OpenVideoCatalogParser.parse(section = FeedSection.KDRAMA)
        assertTrue("OPEN_VIDEO must reject KDRAMA feed section", kdramaItems.isEmpty())
    }

    @Test
    fun testBoundedHttpClient_urlValidationSecurity() {
        val client = BoundedHttpClient()

        val valid = client.validateUrl("https://v3-cinemeta.strem.io/catalog/movie/top.json")
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top.json", valid)

        try {
            client.validateUrl("http://v3-cinemeta.strem.io/catalog")
            fail("Expected SecurityException for http://")
        } catch (_: SecurityException) {}

        try {
            client.validateUrl("https://localhost/api")
            fail("Expected SecurityException for localhost")
        } catch (_: SecurityException) {}

        try {
            client.validateUrl("https://127.0.0.1/api")
            fail("Expected SecurityException for 127.0.0.1")
        } catch (_: SecurityException) {}

        try {
            client.validateUrl("https://192.168.1.1/api")
            fail("Expected SecurityException for private IP")
        } catch (_: SecurityException) {}
    }

    @Test
    fun testCatalogRepository_isolatesSourcesAndSurvivesSingleFailure() {
        val catalog = EmbeddedCatalog(
            sources = listOf(
                EmbeddedSource(
                    id = "source-cinemeta",
                    name = "Cinemeta",
                    section = FeedSection.HOME,
                    engine = "CINEMETA",
                    endpoints = listOf("https://v3-cinemeta.strem.io/catalog/movie/top.json"),
                    enabled = true,
                    capabilityNote = "Metadata"
                ),
                EmbeddedSource(
                    id = "source-failing",
                    name = "Failing Source",
                    section = FeedSection.HOME,
                    engine = "CINEMETA",
                    endpoints = listOf("https://failing.example.com/broken.json"),
                    enabled = true,
                    capabilityNote = "Will fail"
                )
            )
        )

        val mockHttp = object : TextHttpClient {
            override fun get(url: String): String {
                if (url.contains("broken")) throw RuntimeException("Simulated 500 server error")
                return """{"metas":[{"id":"tt123","type":"movie","name":"Good Movie","releaseInfo":"2024"}]}"""
            }
        }

        val repo = CatalogRepository(catalog, mockHttp)
        val result = repo.load(FeedSection.HOME)

        assertEquals(2, result.sourceCount)
        assertEquals(1, result.items.size)
        assertEquals("Good Movie", result.items[0].title)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].contains("source-failing"))
    }

    @Test
    fun testCatalogRepository_bundledCatalogIntegrity() {
        val asset = listOf(java.io.File("app/src/main/assets/embedded_catalog.json"), java.io.File("src/main/assets/embedded_catalog.json")).first { it.exists() }
        val repo = CatalogRepository.createDefault(asset.readText())
        for (section in listOf(FeedSection.HOME, FeedSection.ANIME, FeedSection.KDRAMA, FeedSection.LIVETV)) {
            val sources = repo.getSourcesForSection(section)
            assertTrue("Section $section must have at least one bundled source", sources.isNotEmpty())
            assertTrue(sources.all { it.section == section })
            assertTrue(sources.all { it.endpoints.all { ep -> ep.startsWith("https://") } })
        }
    }
}