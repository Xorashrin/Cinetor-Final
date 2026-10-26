package com.cinator.app.providers

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaKind
import org.junit.Assert.*
import org.junit.Test

class AuthorizedExternalProviderTest {
    private val crunchyrollUrl =
        "https://www.crunchyroll.com/series/GZJH3DWEE/naruto-shippuden-international-dubs"
    private val vikiUrl = "https://www.viki.com/tv/40466c-lovely-runner"

    @Test fun crunchyrollProducesOnlyProviderControlledAnimeNavigationMetadata() {
        val provider = AuthorizedExternalProvider.crunchyroll(
            listOf(OfficialContentRoute("Naruto Shippuden", crunchyrollUrl, MediaKind.ANIME, "JP"))
        )

        assertTrue(provider.active)
        assertEquals(FeedSection.ANIME, provider.supportedSection)
        assertFalse(provider.capabilities.nativeStreamPlayback)
        assertFalse(provider.capabilities.download)
        assertTrue(provider.capabilities.externalNavigation)
        val navigation = provider.navigation("GZJH3DWEE")
        assertEquals(ExternalPlaybackStatus.EXTERNAL_AUTHORIZED, navigation.status)
        assertEquals(MediaKind.ANIME, navigation.kind)
        assertEquals(crunchyrollUrl, navigation.officialUrl)
        assertTrue(navigation.providerControlsPlayback)
        assertTrue(navigation.rightsAndAvailabilityNotice.contains("subscription", ignoreCase = true))
        assertTrue(navigation.rightsAndAvailabilityNotice.contains("territory", ignoreCase = true))
    }

    @Test fun vikiIsStrictlyKoreanScriptedDramaExternalNavigation() {
        val provider = AuthorizedExternalProvider.viki(
            listOf(OfficialContentRoute("Lovely Runner", vikiUrl, MediaKind.K_DRAMA, "KR"))
        )

        assertEquals(FeedSection.KDRAMA, provider.supportedSection)
        assertEquals(MediaKind.K_DRAMA, provider.navigation("40466c").kind)
        assertTrue(provider.navigation("40466c").rightsAndAvailabilityNotice.contains("Viki Pass"))
        assertFalse(provider.capabilities.catalogDiscovery)
    }

    @Test fun asiancrushIsStrictlyKoreanScriptedDramaExternalNavigation() {
        val asiancrushUrl = "https://www.asiancrush.com/video/010190v-voice"
        val provider = AuthorizedExternalProvider.asiancrush(
            listOf(OfficialContentRoute("Voice", asiancrushUrl, MediaKind.K_DRAMA, "KR"))
        )

        assertEquals(FeedSection.KDRAMA, provider.supportedSection)
        assertEquals(MediaKind.K_DRAMA, provider.navigation("010190v-voice").kind)
        assertTrue(provider.navigation("010190v-voice").rightsAndAvailabilityNotice.contains("AsianCrush"))
        assertFalse(provider.capabilities.catalogDiscovery)
    }

    @Test fun providerWithoutReviewedRoutesIsInactive() {
        val provider = AuthorizedExternalProvider.crunchyroll(emptyList())
        assertFalse(provider.active)
        assertThrows(IllegalStateException::class.java) { provider.navigation("GZJH3DWEE") }
    }

    @Test fun rejectsNonCanonicalOrUnsafeProviderUrls() {
        val rejected = listOf(
            "http://www.crunchyroll.com/series/GZJH3DWEE/naruto-shippuden-international-dubs",
            "https://user:pass@www.crunchyroll.com/series/GZJH3DWEE/naruto-shippuden-international-dubs",
            "https://www.crunchyroll.com/series/GZJH3DWEE/naruto-shippuden-international-dubs?token=secret",
            "https://www.crunchyroll.com/series/GZJH3DWEE/naruto-shippuden-international-dubs#player",
            "https://crunchyroll.com/series/GZJH3DWEE/naruto-shippuden-international-dubs",
            "https://127.0.0.1/series/GZJH3DWEE/naruto-shippuden-international-dubs",
            "https://www.crunchyroll.com/search?q=naruto",
            "javascript:location='https://www.crunchyroll.com'",
            "https://www.crunchyroll.com/series/GZJH3DWEE/%6earuto"
        )
        rejected.forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                AuthorizedExternalProvider.crunchyroll(listOf(OfficialContentRoute("Naruto", url, MediaKind.ANIME, "JP")))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorizedExternalProvider.viki(listOf(OfficialContentRoute("Lovely Runner", crunchyrollUrl, MediaKind.K_DRAMA, "KR")))
        }
    }

    @Test fun rejectsProviderHomepageAndNonDramaVikiRoutes() {
        listOf(
            "https://www.viki.com/",
            "https://www.viki.com/movies/1234c-example",
            "https://www.viki.com/tv/40466c-lovely-runner?locale=en",
            "https://www.viki.com/tv/40466c-lovely-runner/episodes"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                AuthorizedExternalProvider.viki(listOf(OfficialContentRoute("Lovely Runner", url, MediaKind.K_DRAMA, "KR")))
            }
        }
    }

    @Test fun externalOutputCannotBeCopiedIntoAnUnvalidatedUrl() {
        assertFalse(ExternalPlaybackMetadata::class.java.methods.any { it.name == "copy" })
        assertFalse(PlaybackProvider::class.java.isAssignableFrom(AuthorizedExternalProvider::class.java))
    }

    @Test fun classificationDoesNotInferKoreanDramaFromAProviderHostname() {
        listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.VIDEO, MediaKind.UNKNOWN).forEach { kind ->
            assertThrows(IllegalArgumentException::class.java) {
                AuthorizedExternalProvider.viki(listOf(OfficialContentRoute("Lovely Runner", vikiUrl, kind, "KR")))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorizedExternalProvider.viki(listOf(OfficialContentRoute("Lovely Runner", vikiUrl, MediaKind.K_DRAMA, "JP")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorizedExternalProvider.crunchyroll(listOf(OfficialContentRoute("Naruto", crunchyrollUrl, MediaKind.ANIME, "KR")))
        }
    }

    @Test fun snapshotsRoutesAndNeverAcceptsHostOrPathConfusion() {
        val routes = mutableListOf(OfficialContentRoute("Lovely Runner", vikiUrl, MediaKind.K_DRAMA, "KR"))
        val provider = AuthorizedExternalProvider.viki(routes)
        routes.clear()
        assertEquals(vikiUrl, provider.navigation("40466c").officialUrl)
        listOf(
            vikiUrl.replace("www.viki.com", "www.viki.com.evil.invalid"),
            vikiUrl.replace("www.viki.com", "localhost"),
            vikiUrl.replace("www.viki.com", "[::1]"),
            vikiUrl.replace("www.viki.com", "192.168.1.1"),
            vikiUrl.replace("www.viki.com", "www.viki.com:443"),
            vikiUrl.replace("www.viki.com", "WWW.VIKI.COM"),
            vikiUrl.replace("www.viki.com", "www.viki.com."),
            vikiUrl.replace("/tv/", "/tv/../tv/"),
            vikiUrl.replace("/tv/", "/tv//"),
            vikiUrl.replace("/tv/", "/tv/%2e%2e/"),
            vikiUrl + "/", vikiUrl + ".m3u8", vikiUrl + "?password=secret",
            " " + vikiUrl, vikiUrl + "\n",
            "https://www.youtube.com/watch?v=GZJH3DWEE", "https://youtu.be/GZJH3DWEE"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                AuthorizedExternalProvider.viki(listOf(OfficialContentRoute("Lovely Runner", url, MediaKind.K_DRAMA, "KR")))
            }
        }
    }

    @Test fun derivesIdentityFromOfficialUrlAndRejectsDuplicatesOrUnknownIds() {
        val route = OfficialContentRoute("Naruto Shippuden", crunchyrollUrl, MediaKind.ANIME, "JP")
        assertThrows(IllegalArgumentException::class.java) {
            AuthorizedExternalProvider.crunchyroll(listOf(route, route))
        }
        val provider = AuthorizedExternalProvider.crunchyroll(listOf(route))
        assertThrows(IllegalArgumentException::class.java) { provider.navigation("made-up-id") }
    }
}
