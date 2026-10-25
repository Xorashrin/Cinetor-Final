package com.cinator.app.providers

import com.cinator.app.data.*
import com.cinator.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackCatalogWiringTest {
    @Test fun selectedSectionLoadsBundledPlaybackProviderWithoutFakeStreams() {
        val card = MediaCard("film", "Test film", "", "Fixture only", FeedSection.HOME,
            "open-test", "Open test", kind=MediaKind.MOVIE, contentId="test:film")
        val provider = object : PlaybackProvider {
            override val info = ProviderInfo("open-test", "Open test", FeedSection.HOME,
                ProviderCapabilities(playback=true), "https://example.com/rights")
            override fun catalog(query:String) = if (query.isEmpty() || card.title.contains(query,true)) listOf(card) else emptyList()
            override fun details(card:MediaCard) = ProviderDetails(card)
            override fun resolve(request:ResolveRequest) = emptyList<StreamSource>()
        }
        val repo = CatalogRepository(EmbeddedCatalog(emptyList()), object : TextHttpClient {
            override fun get(url:String):String = error("No HTTP expected")
        }, listOf(provider))
        val result = repo.load(FeedSection.HOME,"film")
        assertEquals(listOf(card), result.items)
        assertEquals(1, result.sourceCount)
        assertTrue(repo.load(FeedSection.ANIME).items.isEmpty())
        assertTrue(repo.load(FeedSection.HOME,"no-match").items.isEmpty())
    }
}
