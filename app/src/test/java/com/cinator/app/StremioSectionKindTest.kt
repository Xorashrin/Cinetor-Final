package com.cinator.app

import com.cinator.app.data.*
import com.cinator.app.domain.*
import com.cinator.app.providers.*
import org.junit.Assert.*
import org.junit.Test

class StremioSectionKindTest {
    @Test fun supportedSeriesKindsPassSharedIsolation() {
        for ((section, country, genres, expected) in listOf(
            arrayOf("ANIME", "Japan", "Anime", "ANIME"),
            arrayOf("KDRAMA", "South Korea", "Drama", "K_DRAMA")
        )) {
            val info = ProviderInfo("stremio-test", "Test", FeedSection.valueOf(section),
                ProviderCapabilities(episodes=true,playback=true), "https://example.com")
            val manifest = """{"id":"test","version":"1","name":"test","resources":["catalog","meta","stream"],"types":["series"],"catalogs":[{"type":"series","id":"top"}]}"""
            val meta = """{"id":"tt1","name":"Fixture","type":"series","country":"$country","genres":["$genres"]}"""
            val provider = StremioPlaybackProvider(info,"https://example.com/manifest.json",object:TextHttpClient {
                override fun get(url:String) = if(url.endsWith("manifest.json")) manifest else """{"metas":[$meta]}"""
            })
            val card = provider.catalog().single()
            assertEquals(MediaKind.valueOf(expected),card.kind)
            assertTrue(card.belongsTo(info.section))
        }
    }
}
