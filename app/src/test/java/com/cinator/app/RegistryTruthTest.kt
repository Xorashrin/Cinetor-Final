package com.cinator.app

import com.cinator.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class RegistryTruthTest {
    @Test fun registryDoesNotClaimTestClipsAreAnimeOrDramaProviders() {
        val fake = ProviderRegistry.allProviders.filter {
            it.id == "open-anime-stream" || it.id == "open-kdrama-preview"
        }
        assertTrue("Do not expose generic MP4/HLS fixtures as real Anime/K-Drama sources", fake.isEmpty())
    }

    @Test fun adultRegistryHasNoPlaceholderNetworkEndpointOrFalseCapabilities() {
        val adult = ProviderRegistry.forSection(FeedSection.ADULT)
        assertTrue(adult.none { "example.org" in it.endpoint })
        assertTrue(adult.none { it.canPlayInApp || it.canDownload })
        assertTrue(adult.all { it.capabilities.isEmpty() })
    }
}
