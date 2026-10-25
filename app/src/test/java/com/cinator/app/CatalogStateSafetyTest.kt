package com.cinator.app

import com.cinator.app.data.SectionStateCoordinator
import com.cinator.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class CatalogStateSafetyTest {
    private fun card(id: String, section: FeedSection, kind: MediaKind) = MediaCard(
        id = id, title = id, subtitle = "", synopsis = "", section = section,
        providerId = "test-catalog", providerName = "Test", kind = kind, contentId = "test:$id"
    )

    @Test fun stateAndSearchRejectWrongKindsEvenWhenSectionIsRelabeled() {
        val coordinator = SectionStateCoordinator()
        val safe = card("Home title", FeedSection.HOME, MediaKind.SERIES)
        val unsafe = listOf(
            card("Anime", FeedSection.HOME, MediaKind.ANIME),
            card("Korean Drama", FeedSection.HOME, MediaKind.K_DRAMA),
            card("Adult", FeedSection.HOME, MediaKind.ADULT),
            card("Unknown", FeedSection.HOME, MediaKind.UNKNOWN),
            card("Wrong section", FeedSection.ANIME, MediaKind.ANIME)
        )
        assertEquals(listOf(safe), coordinator.updateSectionItems(FeedSection.HOME, listOf(safe) + unsafe).items)
        coordinator.updateSearch(FeedSection.HOME, "title")
        assertEquals(listOf(safe), coordinator.updateSearch(FeedSection.HOME, "title", unsafe).searchResults)
        assertTrue(coordinator.updateSectionItems(FeedSection.ANIME,
            listOf(card("Relabeled reality", FeedSection.ANIME, MediaKind.SERIES))).items.isEmpty())
    }

    @Test fun staleSearchCompletionsCannotOverwriteNewOrClearedQueries() {
        val coordinator = SectionStateCoordinator()
        val old = card("Old show", FeedSection.HOME, MediaKind.SERIES)
        val current = card("Current show", FeedSection.HOME, MediaKind.SERIES)
        coordinator.updateSectionItems(FeedSection.HOME, listOf(old, current))
        coordinator.updateSearch(FeedSection.HOME, "Old")
        coordinator.updateSearch(FeedSection.HOME, "Current")
        coordinator.updateSearch(FeedSection.HOME, "Old", listOf(old))
        assertEquals("Current", coordinator.getState(FeedSection.HOME).searchQuery)
        assertEquals(listOf(current), coordinator.getState(FeedSection.HOME).searchResults)
        coordinator.updateSearch(FeedSection.HOME, "Old", emptyList())
        assertEquals("Current", coordinator.getState(FeedSection.HOME).searchQuery)
        coordinator.updateSearch(FeedSection.HOME, "")
        coordinator.updateSearch(FeedSection.HOME, "Old", listOf(old))
        assertEquals("", coordinator.getState(FeedSection.HOME).searchQuery)
        assertTrue(coordinator.getState(FeedSection.HOME).searchResults.isEmpty())
        assertTrue(coordinator.updateSearch(FeedSection.HOME, "", listOf(current)).searchResults.isEmpty())
        coordinator.updateSearch(FeedSection.HOME, "Current")
        coordinator.updateSectionItems(FeedSection.HOME, listOf(old))
        assertTrue(coordinator.getState(FeedSection.HOME).searchResults.isEmpty())
    }

    @Test fun olderCatalogUpdatesAndDisabledAdultErrorsAreInert() {
        val coordinator = SectionStateCoordinator()
        val current = card("new", FeedSection.HOME, MediaKind.MOVIE)
        coordinator.updateSectionItems(FeedSection.HOME, listOf(current), timestampMs = 200)
        coordinator.updateSectionItems(FeedSection.HOME,
            listOf(card("old", FeedSection.HOME, MediaKind.MOVIE)), timestampMs = 100)
        assertEquals(listOf(current), coordinator.getState(FeedSection.HOME).items)
        assertEquals(200L, coordinator.getState(FeedSection.HOME).lastUpdatedMs)
        coordinator.setAdultOptIn(true)
        coordinator.updateSectionItems(FeedSection.ADULT, listOf(card("private", FeedSection.ADULT, MediaKind.ADULT)))
        coordinator.updateSearch(FeedSection.ADULT, "private")
        coordinator.setAdultOptIn(false)
        val disabled = coordinator.getState(FeedSection.ADULT)
        coordinator.recordSectionError(FeedSection.ADULT, "Late private failure")
        assertEquals(disabled, coordinator.getState(FeedSection.ADULT))
        assertTrue(coordinator.setAdultOptIn(true).items.isEmpty())
    }

    @Test fun defaultsNeverInventMoviesOrMislabeledSamples() {
        for (section in FeedSection.entries.filter { it != FeedSection.HOME }) {
            assertTrue(section.name, SectionStateCoordinator.defaultCuratedItems(section).isEmpty())
        }
        val sample = SectionStateCoordinator.defaultCuratedItems(FeedSection.HOME).single()
        assertEquals(MediaKind.VIDEO, sample.kind)
        assertEquals("Flower (MDN CC0 sample)", sample.title)
        assertNull(sample.rating)
        assertNull(sample.year)
        assertTrue(sample.genres.isEmpty())
        val stream = sample.streams.single()
        assertEquals("https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4", stream.url)
        assertEquals(sample.contentId, stream.contentId)
        assertEquals(FeedSection.HOME, stream.section)
        assertNull(stream.subtitleLang)
        assertTrue(stream.quality.isEmpty())
        assertTrue(SectionStateCoordinator(true).getState(FeedSection.ADULT).items.isEmpty())
        assertTrue(SectionStateCoordinator().setAdultOptIn(true).items.isEmpty())
    }
}
