package com.cinator.app.ui

import com.cinator.app.domain.*
import com.cinator.app.providers.*
import org.junit.Assert.*
import org.junit.Test

class ResolvedSelectionStateTest {
    private val card = MediaCard("film", "Full film", "", "", FeedSection.HOME,
        "blender-open-films", "Blender", kind = MediaKind.MOVIE,
        contentId = "blender:bf1f3fb5-b119-4f9f-9930-8e20e892b898")
    private val stream = StreamSource("film:720", "720p", "https://example.org/film.mp4",
        StreamProtocol.MP4, "720p", true, card.providerId, contentId = card.contentId, section = card.section)

    @Test fun changingEpisodeCancelsSelectionAndRejectsLateSuccessAndFailure() {
        val first = EpisodeRef("first", "Episode one", 1, 1, "show:1")
        val second = EpisodeRef("second", "Episode two", 1, 2, "show:2")
        val waiting = ResolvedSelectionState().select(card)
        val loaded = waiting.receiveDetails(waiting.generation, ProviderDetails(card, listOf(first, second)))
        val resolving = loaded.beginResolve()
        assertTrue(resolving.loading)
        val changed = resolving.selectEpisode(second)
        assertFalse(changed.loading)
        assertEquals(changed, changed.receiveStreams(resolving.generation, listOf(stream.copy(contentId = first.contentId))))
        assertEquals(changed, changed.fail(resolving.generation, "Old failure"))
        assertEquals(changed, changed.selectEpisode(first.copy(contentId = "forged")))
        val request = changed.beginResolve()
        val good = stream.copy(contentId = second.contentId)
        val resolved = request.receiveStreams(request.generation, listOf(good,
            good.copy(id = "wrong-provider", providerId = "other"),
            good.copy(id = "wrong-section", section = FeedSection.ANIME),
            good.copy(id = "wrong-mode", isLive = true), stream))
        assertEquals(listOf(good), resolved.details!!.card.streams)
        val authorized = resolved.actionCard(good)!!
        assertEquals(second.contentId, authorized.contentId)
        assertTrue(authorized.title.contains(second.title))
        assertEquals(listOf(good), authorized.streams)
        assertNull(resolved.actionCard(stream))
        assertNull(resolved.beginResolve().actionCard(good))
    }

    @Test fun filmSourcesRequireFreshResolveAndRepeatedRequestsInvalidateOldResults() {
        val waiting = ResolvedSelectionState().select(card.copy(streams = listOf(stream)))
        assertNull(waiting.actionCard(stream))
        val loaded = waiting.receiveDetails(waiting.generation, ProviderDetails(card))
        val first = loaded.beginResolve()
        val second = first.beginResolve()
        assertEquals(second, second.receiveStreams(first.generation, listOf(stream)))
        val ready = second.receiveStreams(second.generation, listOf(stream))
        assertEquals(card.copy(streams = listOf(stream)), ready.actionCard(stream))
        val empty = ready.beginResolve()
        assertNotNull(empty.receiveStreams(empty.generation, emptyList()).error)
        assertEquals(ready.select(null), ready.select(null).fail(ready.generation, "late"))
    }

    @Test fun mismatchedDetailsAreRejectedBeforeRenderingOrResolving() {
        val waiting = ResolvedSelectionState().select(card)
        val rejected = waiting.receiveDetails(waiting.generation,
            ProviderDetails(card.copy(contentId = "another-film")))
        assertFalse(rejected.detailsLoaded)
        assertEquals(card.contentId, rejected.details!!.card.contentId)
        assertNotNull(rejected.error)
    }

    @Test fun closingAndReopeningSameCardRejectsOldDetails() {
        val first = ResolvedSelectionState().select(card.copy(streams = listOf(stream)))
        assertTrue(first.details!!.card.streams.isEmpty())
        val reopened = first.select(null).select(card)
        assertEquals(reopened, reopened.receiveDetails(first.generation, ProviderDetails(card)))
        val loaded = reopened.receiveDetails(reopened.generation, ProviderDetails(card.copy(streams = listOf(stream))))
        assertTrue(loaded.detailsLoaded)
        assertFalse(loaded.loading)
        assertTrue(loaded.details!!.card.streams.isEmpty())
        assertNull(loaded.episode)
    }
}
