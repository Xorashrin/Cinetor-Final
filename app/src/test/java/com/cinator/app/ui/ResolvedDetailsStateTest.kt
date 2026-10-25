package com.cinator.app.ui

import com.cinator.app.providers.EpisodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedDetailsStateTest {
    private val first = EpisodeRef("e1", "Pilot", 1, 1, "show:e1")
    private val second = EpisodeRef("e2", "Finale", 1, 2, "show:e2")

    @Test fun initialStateSelectsFirstEpisodeWhenEpisodesExist() {
        val state = ResolvedDetailsState.initial("show", listOf(first, second))
        assertEquals("e1", state.selectedEpisodeId)
        assertEquals(first, state.selectedEpisode(listOf(first, second)))
    }

    @Test fun selectingEpisodeRejectsAnIdOutsideCurrentDetails() {
        val state = ResolvedDetailsState.initial("show", listOf(first))
        assertEquals(state, state.select(second, listOf(first)))
    }

    @Test fun sameEpisodeIdCannotSubstituteAnotherContentIdentity() {
        val state = ResolvedDetailsState.initial("show", listOf(first, second))
        assertEquals(state, state.select(second.copy(contentId = "unrelated"), listOf(first, second)))
    }

    @Test fun newContentDiscardsSelectionFromPreviousContent() {
        val old = ResolvedDetailsState.initial("show-a", listOf(first))
        val updated = old.forContent("show-b", listOf(second))
        assertEquals("show-b", updated.contentKey)
        assertEquals("e2", updated.selectedEpisodeId)
        assertEquals(second, updated.selectedEpisode(listOf(second)))
    }

    @Test fun missingEpisodesClearOldSelection() {
        val state = ResolvedDetailsState.initial("show", listOf(first))
        assertNull(state.forContent("show", emptyList()).selectedEpisodeId)
    }

    @Test fun staleSourcesAreHiddenAfterEpisodeChanges() {
        val good = com.cinator.app.domain.StreamSource(
            "one", "MP4", "https://example.org/episode.mp4",
            com.cinator.app.domain.StreamProtocol.MP4, "720p", false,
            "provider", contentId = first.contentId, section = com.cinator.app.domain.FeedSection.HOME
        )
        val card = com.cinator.app.domain.MediaCard(
            "show", "Show", "", "", com.cinator.app.domain.FeedSection.HOME,
            "provider", "Provider", streams = listOf(good,
                good.copy(id = "wrong-provider", providerId = "other"),
                good.copy(id = "wrong-section", section = com.cinator.app.domain.FeedSection.ANIME),
                good.copy(id = "wrong-content", contentId = "unrelated")), contentId = "show"
        )
        val state = ResolvedDetailsState.initial("show", listOf(first, second))
        assertEquals(listOf(good), state.visibleStreams(card, listOf(first, second), loading = false))
        assertEquals(emptyList<com.cinator.app.domain.StreamSource>(),
            state.select(second, listOf(first, second)).visibleStreams(card, listOf(first, second), false))
        assertEquals(emptyList<com.cinator.app.domain.StreamSource>(),
            state.visibleStreams(card, listOf(first, second), loading = true))
        assertEquals(emptyList<com.cinator.app.domain.StreamSource>(),
            ResolvedDetailsState.initial("film", emptyList()).visibleStreams(
                card.copy(contentId = null, streams = listOf(good.copy(contentId = null))), emptyList(), false))
    }

    @Test fun unknownSelectionNeverFallsBackToSeriesStream() {
        val stream = com.cinator.app.domain.StreamSource(
            "series", "MP4", "https://example.org/film.mp4",
            com.cinator.app.domain.StreamProtocol.MP4, "720p", false,
            "provider", contentId = "show", section = com.cinator.app.domain.FeedSection.HOME
        )
        val card = com.cinator.app.domain.MediaCard(
            "show", "Show", "", "", com.cinator.app.domain.FeedSection.HOME,
            "provider", "Provider", contentId = "show", streams = listOf(stream)
        )
        assertEquals(emptyList<com.cinator.app.domain.StreamSource>(),
            ResolvedDetailsState("show", "deleted").visibleStreams(card, listOf(first), false))
        assertEquals(listOf(stream), ResolvedDetailsState.initial("show", emptyList())
            .visibleStreams(card, emptyList(), false))
    }

    @Test fun selectionSurvivesSameContentRefresh() {
        val state = ResolvedDetailsState.initial("show", listOf(first, second)).select(second, listOf(first, second))
        assertEquals(second, state.selectedEpisode(listOf(first, second)))
        assertEquals(state, state.forContent("show", listOf(first, second)))
        assertEquals(first, state.forContent("show", listOf(first)).selectedEpisode(listOf(first)))
    }

    @Test fun contentWithoutEpisodesHasNoSelection() {
        val state = ResolvedDetailsState.initial("film", emptyList())
        assertNull(state.selectedEpisodeId)
        assertNull(state.selectedEpisode(emptyList()))
    }
}
