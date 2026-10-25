package com.cinator.app

import com.cinator.app.data.CloudStreamRepoParser
import com.cinator.app.data.LiveNetworkAdapters
import com.cinator.app.data.M3uPlaylistParser
import com.cinator.app.data.NuvioManifestParser
import com.cinator.app.data.SectionStateCoordinator
import com.cinator.app.data.UrlPolicyValidator
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.ProviderRegistry
import com.cinator.app.domain.SourceClass
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import com.cinator.app.downloads.CinatorDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinatorContractsAndIsolationTest {

    @Test
    fun providerRegistry_enforcesSectionBoundariesAndExternalLinkRules() {
        for (section in FeedSection.entries) {
            val providers = ProviderRegistry.forSection(section)
            assertTrue("Expected providers for $section", providers.isNotEmpty())
            assertTrue(providers.all { it.section == section })
        }

        val externalProviders = ProviderRegistry.allProviders.filter {
            it.sourceClass == SourceClass.EXTERNAL_LINK_ONLY
        }
        assertTrue(externalProviders.isNotEmpty())
        assertTrue(
            "External link references must never claim in-app playback or download",
            externalProviders.all { !it.canPlayInApp && !it.canDownload }
        )
    }

    @Test
    fun sectionCoordinator_isolatesFeedsSearchesErrorsAndAdultOptIn() {
        val coordinator = SectionStateCoordinator(adultInitiallyEnabled = false)

        // Adult is disabled and empty by default
        assertFalse(coordinator.isAdultEnabled)
        assertTrue(coordinator.getState(FeedSection.ADULT).items.isEmpty())

        // Attempting to push Anime items into Home store is rejected
        val animeLeakAttempt = MediaCard(
            id = "leak_1",
            title = "Leaked Anime",
            subtitle = "Should not enter Home",
            synopsis = "Test",
            section = FeedSection.ANIME,
            providerId = "tvmaze-anime",
            providerName = "TVMaze"
        )
        val homeBefore = coordinator.getState(FeedSection.HOME).items
        coordinator.updateSectionItems(FeedSection.HOME, homeBefore + animeLeakAttempt)
        val homeAfter = coordinator.getState(FeedSection.HOME).items
        assertFalse(homeAfter.any { it.id == "leak_1" })

        // Searching or failing in KDRAMA does not mutate HOME or ANIME
        coordinator.updateSearch(FeedSection.KDRAMA, "Seoul")
        coordinator.recordSectionError(FeedSection.KDRAMA, "Simulated timeout")
        assertEquals("", coordinator.getState(FeedSection.HOME).searchQuery)
        assertEquals(null, coordinator.getState(FeedSection.HOME).errorMessage)
        assertEquals(null, coordinator.getState(FeedSection.ANIME).errorMessage)
        assertEquals("Simulated timeout", coordinator.getState(FeedSection.KDRAMA).errorMessage)

        // Opting into Adult populates isolated store; opting out wipes it
        val adultOn = coordinator.setAdultOptIn(true)
        assertTrue(adultOn.items.isNotEmpty())
        val adultOff = coordinator.setAdultOptIn(false)
        assertTrue(adultOff.items.isEmpty())
    }

    @Test
    fun m3uPlaylistParser_extractsSafeHttpsChannelsAndSkipsInsecureUrls() {
        val sampleM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ADNTV.fr" tvg-logo="https://i.imgur.com/logo.png" group-title="Animation",ADN TV+ (720p)
            https://d3b73b34o7cvkq.cloudfront.net/v1/master/adn.m3u8
            #EXTINF:-1 tvg-id="Insecure.tv" group-title="News",Insecure Channel
            http://insecure.example.com/live.m3u8
        """.trimIndent()

        val channels = M3uPlaylistParser.parse(sampleM3u)
        assertEquals(1, channels.size)
        assertEquals("ADN TV+ (720p)", channels[0].title)
        assertEquals("Animation", channels[0].groupTitle)
        assertEquals("https://d3b73b34o7cvkq.cloudfront.net/v1/master/adn.m3u8", channels[0].streamUrl)
    }

    @Test
    fun nuvioAndCloudStreamContractParsers_validateSchemasSafely() {
        val nuvioJson = """
            {
              "id": "org.cinator.open",
              "name": "Cinator Open Catalog",
              "version": "1.2.0",
              "description": "Read-only manifest contract",
              "resources": ["catalog", {"name": "stream"}],
              "types": ["movie", "series"],
              "catalogs": [{"type": "movie", "id": "top"}]
            }
        """.trimIndent()
        val manifest = NuvioManifestParser.parseManifestJson(nuvioJson).getOrThrow()
        assertEquals("org.cinator.open", manifest.id)
        assertEquals(listOf("catalog", "stream"), manifest.resources)
        assertEquals(1, manifest.catalogCount)

        val csRepoJson = """
            {
              "name": "Sample Verified Metadata Repo",
              "description": "Inert contract check",
              "manifestVersion": 1,
              "pluginLists": ["https://raw.githubusercontent.com/recloudstream/extensions/master/plugins.json"]
            }
        """.trimIndent()
        val csSummary = CloudStreamRepoParser.parseRepoJson(csRepoJson).getOrThrow()
        assertEquals("METADATA_ONLY_NO_DEX_EXECUTION", csSummary.executionPolicy)
        assertEquals(1, csSummary.pluginListUrls.size)

        val unsafeCsRepo = """
            {
              "name": "Unsafe Repo",
              "pluginLists": ["http://cleartext.example.com/plugins.json"]
            }
        """.trimIndent()
        assertTrue(CloudStreamRepoParser.parseRepoJson(unsafeCsRepo).isFailure)
    }

    @Test
    fun downloadPolicy_allowsOnlyAuthorizedHttpsMp4AndRejectsLiveHls() {
        val mp4Stream = StreamSource(
            id = "s1",
            label = "CC0 MP4",
            url = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
            protocol = StreamProtocol.MP4,
            quality = "720p",
            isDownloadable = true,
            providerId = "open-cinema-mp4"
        )
        val hlsStream = StreamSource(
            id = "s2",
            label = "Live HLS",
            url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            protocol = StreamProtocol.HLS_M3U8,
            quality = "Live",
            isDownloadable = false,
            providerId = "iptv-org-public"
        )
        assertTrue(CinatorDownloadManager.canDownloadStream(mp4Stream))
        assertFalse(CinatorDownloadManager.canDownloadStream(hlsStream))
        assertTrue(UrlPolicyValidator.isDeclaredBdixEndpoint("https://movies.discoveryftp.net"))
        assertFalse(UrlPolicyValidator.isDeclaredBdixEndpoint("https://untrusted.example.com"))
    }

    @Test
    fun tvMazeParser_mapsJsonIntoTargetSectionOnly() {
        val sampleJson = """
            [
              {
                "score": 0.91,
                "show": {
                  "id": 3173,
                  "name": "Korean Peninsula",
                  "type": "Scripted",
                  "language": "Korean",
                  "premiered": "2012-02-06",
                  "genres": ["Drama", "Action"],
                  "rating": {"average": 7.8},
                  "summary": "<p>Political drama series.</p>",
                  "url": "https://www.tvmaze.com/shows/3173/korean-peninsula"
                }
              }
            ]
        """.trimIndent()
        val items = LiveNetworkAdapters.parseTvMazeSearchJson(sampleJson, FeedSection.KDRAMA)
        assertEquals(1, items.size)
        assertEquals(FeedSection.KDRAMA, items[0].section)
        assertEquals("Korean Peninsula", items[0].title)
        assertEquals("Political drama series.", items[0].synopsis)
    }
}
