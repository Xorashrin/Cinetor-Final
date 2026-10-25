package com.cinator.app

import com.cinator.app.data.*
import com.cinator.app.domain.FeedSection
import org.junit.Assert.*
import org.junit.Test

class BackendPlaylistRegressionTest {
    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="same" group-title="News",News A
        https://stream.example.com/a.m3u8
        #EXTINF:-1 tvg-id="same" group-title="News",News B
        https://stream.example.com/b.m3u8?variant=2
        #EXTINF:-1 tvg-id="same",Duplicate A
        https://stream.example.com/a.m3u8
        #EXTINF:-1,Not HLS
        https://stream.example.com/movie.mp4
        #EXTINF:-1,Not HLS either
        https://stream.example.com/page?file=video.m3u8
        #EXTINF:-1,Private
        https://[fd00::1]/live.m3u8
    """.trimIndent()

    @Test fun onlyLiveHlsAndStableNamespacedVariantsSurvive() {
        val items = MultiCategoryIptvParser.parse(playlist)
        assertEquals(2, items.size)
        assertEquals(2, items.map { it.id }.toSet().size)
        assertTrue(items.all { it.id.startsWith("iptv-org:") })
        val other = MultiCategoryIptvParser.parse(playlist, providerId = "other")
        assertTrue(other.none { item -> items.any { it.id == item.id } })
        assertEquals(items.map { it.id }, MultiCategoryIptvParser.parse(playlist).map { it.id })
        assertTrue(items.all { it.streams.single().quality.isEmpty() && !it.streams.single().isDownloadable })
        assertTrue(MultiCategoryIptvParser.parse(playlist, FeedSection.HOME).isEmpty())
        assertTrue(MultiCategoryIptvParser.parse(playlist, FeedSection.ADULT).isEmpty())
    }

    @Test fun playlistHasBoundedItemCountAndRejectsNonPlaylistResponses() {
        val huge = "#EXTM3U\n" + (1..500).joinToString("\n") { "#EXTINF:-1,Channel $it\nhttps://stream.example.com/$it.m3u8" }
        assertTrue(MultiCategoryIptvParser.parse(huge).size <= 200)
        try { MultiCategoryIptvParser.parse("<html>Unavailable</html>"); fail("Schema error must not be empty success") } catch (_: Exception) { }
    }

    @Test fun sampleHasNoInventedMetadataAndHonorsSearch() {
        val item = OpenVideoCatalogParser.parse().single()
        assertNull(item.year)
        assertNull(item.rating)
        assertTrue(item.streams.single().quality.isEmpty())
        assertFalse(item.streams.single().label.contains("720"))
        val source = EmbeddedSource("sample", "Sample", FeedSection.HOME, "OPEN_VIDEO", listOf(OpenVideoCatalogParser.MDN_FLOWER_URL), true, "CC0 sample")
        val repo = CatalogRepository(EmbeddedCatalog(listOf(source)), object : TextHttpClient {
            override fun get(url: String): String = error("Sample must not fetch metadata")
        })
        assertTrue(repo.load(FeedSection.HOME, "Naruto").items.isEmpty())
        assertEquals(1, repo.load(FeedSection.HOME, "flower").items.size)
    }
}
