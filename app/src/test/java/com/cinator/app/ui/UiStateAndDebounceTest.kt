package com.cinator.app.ui

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class UiStateAndDebounceTest {

    @Test
    fun suspendCommitDoesNotBlockInvalidationOrOtherSectionsAndRejectsQueuedStaleWork() = runBlocking {
        val coordinator = SearchDebounceCoordinator(this)
        val old = coordinator.cancel(FeedSection.HOME)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            coordinator.commitIfLatestSuspending(FeedSection.HOME, old) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var staleWrites = 0
        val stale = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            coordinator.commitIfLatestSuspending(FeedSection.HOME, old) { staleWrites++ }
        }
        try {
            val latest = kotlinx.coroutines.withTimeout(1_000L) {
                kotlinx.coroutines.withContext(Dispatchers.Default) { coordinator.cancel(FeedSection.HOME) }
            }
            assertFalse(coordinator.isLatestGeneration(FeedSection.HOME, old))
            val anime = coordinator.cancel(FeedSection.ANIME)
            assertTrue(kotlinx.coroutines.withTimeout(1_000L) {
                coordinator.commitIfLatestSuspending(FeedSection.ANIME, anime) { }
            })
            var latestWrites = 0
            val current = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                coordinator.commitIfLatestSuspending(FeedSection.HOME, latest) { latestWrites++ }
            }
            assertFalse(current.isCompleted)
            release.complete(Unit)
            assertTrue(first.await())
            assertFalse(stale.await())
            assertEquals(0, staleWrites)
            assertTrue(current.await())
            assertEquals(1, latestWrites)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun clearWaitsForAcceptedPublicationWithoutBlockingInvalidation() = runBlocking {
        val coordinator = SearchDebounceCoordinator(this)
        val generation = coordinator.cancel(FeedSection.ADULT)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val publication = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            coordinator.commitIfLatestSuspending(FeedSection.ADULT, generation) {
                release.await()
                operations.add("publish")
            }
        }
        coordinator.cancel(FeedSection.ADULT)
        val clear = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            coordinator.withSectionCommit(FeedSection.ADULT) { operations.add("clear") }
        }
        assertFalse(clear.isCompleted)
        release.complete(Unit)
        publication.await()
        clear.await()
        assertEquals(listOf("publish", "clear"), operations)
        var staleWrites = 0
        assertFalse(coordinator.commitIfLatestSuspending(FeedSection.ADULT, generation) { staleWrites++ })
        assertEquals(0, staleWrites)
    }

    @Test
    fun clearingSearchInvalidatesAlreadyRunningGeneration() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Long>()
        val coordinator = SearchDebounceCoordinator(this, debounceDelayMs = 0L)
        coordinator.onQueryChanged(FeedSection.HOME, "old") { generation, _ ->
            started.complete(generation)
            delay(10_000)
        }
        val generation = started.await()
        coordinator.cancel(FeedSection.HOME)
        assertFalse("Clear must invalidate callbacks even when blocking IO ignores cancellation",
            coordinator.isLatestGeneration(FeedSection.HOME, generation))
    }

    @Test
    fun refreshSupersedesSearchWithSameGenerationGuard() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Long>()
        val coordinator = SearchDebounceCoordinator(this, debounceDelayMs = 0L)
        coordinator.onQueryChanged(FeedSection.HOME, "old") { gen, _ ->
            started.complete(gen)
            delay(10_000)
        }
        val old = started.await()
        val refreshed = kotlinx.coroutines.CompletableDeferred<Long>()
        coordinator.request(FeedSection.HOME, "", debounce = false) { gen, _ -> refreshed.complete(gen) }
        val latest = refreshed.await()
        var staleWrites = 0
        coordinator.commitIfLatest(FeedSection.HOME, old) { staleWrites++ }
        assertEquals(0, staleWrites)
        assertFalse(coordinator.isLatestGeneration(FeedSection.HOME, old))
        assertTrue(coordinator.isLatestGeneration(FeedSection.HOME, latest))
        assertFalse(coordinator.isLatestGeneration(FeedSection.ADULT, 99))
    }

    @Test
    fun partialRefreshNeverReplacesCompleteDiskSnapshot() {
        val item = MediaCard("new", "New title", "", "", FeedSection.HOME, "a", "A")
        assertFalse(shouldPersistCatalog("", listOf(item), listOf("b: request failed")))
        assertTrue(shouldPersistCatalog("", listOf(item), emptyList()))
        assertTrue(shouldPersistCatalog("", emptyList(), emptyList()))
        assertFalse(shouldPersistCatalog("query", listOf(item), emptyList()))
    }

    @Test
    fun partialRefreshRetainsOnlyFailedProviderRowsWithoutRenewingTheirAge() {
        val oldA = MediaCard("old-a", "Old A", "", "", FeedSection.HOME, "a", "A", kind = MediaKind.MOVIE)
        val oldB = oldA.copy(id = "old-b", providerId = "b", providerName = "B")
        val replacedB = oldB.copy(id = "shared")
        val freshA = oldA.copy(id = "new-a")
        val freshB = replacedB.copy(title = "Fresh B")
        val state = FeedUiState(FeedSection.HOME,
            items = listOf(oldA, oldB, replacedB), lastUpdatedMs = 1_000L)
        val failures = listOf("b: 1/2 requests failed; completed results retained")
        val partial = state.afterCatalogResult(listOf(freshA, freshB), failures, 2, 2_000L)
        assertEquals(listOf(freshA, freshB, oldB), partial.items)
        assertTrue(partial.isCached)
        assertTrue(partial.catalogIsCached)
        assertEquals(1_000L, partial.lastUpdatedMs)
        assertEquals(failures, partial.providerFailures)
        val expired = partial.afterCatalogResult(listOf(freshA), failures, 2, 1_001L + MAX_UI_STALE_MS)
        assertEquals(listOf(freshA), expired.items)
        assertFalse(expired.isCached)
    }

    @Test
    fun failedEmptyRefreshPreservesBoundedSnapshotWithoutCacheWrite() {
        val card = MediaCard("cached", "Saved title", "", "", FeedSection.HOME, "p", "Provider", kind = MediaKind.MOVIE)
        val state = FeedUiState(FeedSection.HOME, items = listOf(card), lastUpdatedMs = 1_000L)
        val failed = state.afterCatalogResult(emptyList(), listOf("provider offline"), 1, nowMs = 2_000L)
        assertEquals(listOf(card), failed.items)
        assertTrue(failed.isCached)
        assertTrue(failed.errorMessage!!.contains("saved"))
        assertEquals(1_000L, failed.lastUpdatedMs)
        assertFalse(shouldPersistCatalog("", emptyList(), listOf("provider offline")))
        val expired = state.afterCatalogResult(emptyList(), listOf("offline"), 1,
            nowMs = 1_001L + MAX_UI_STALE_MS)
        assertTrue(expired.items.isEmpty())
        assertTrue(expired.errorMessage!!.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun failedSearchRetainsOnlySameQueryResultsWithinStaleBound() {
        val match = MediaCard("found", "Search match", "", "", FeedSection.HOME, "p", "P", kind = MediaKind.MOVIE)
        val base = FeedUiState(FeedSection.HOME, searchQuery = "Search")
        val loaded = base.afterCatalogResult(listOf(match), emptyList(), 1, 1_000L)
        val failed = loaded.afterCatalogResult(emptyList(), listOf("offline"), 1, 2_000L)
        assertEquals(listOf(match), failed.searchResults)
        assertTrue(failed.isCached)
        val expired = loaded.afterCatalogResult(emptyList(), listOf("offline"), 1, MAX_UI_STALE_MS + 1_001L)
        assertTrue(expired.searchResults.isEmpty())
    }

    @Test
    fun partialSearchRetainsFailedProviderMatchesWithoutReplacingBaseCatalog() {
        val old = MediaCard("old", "Search result", "", "", FeedSection.HOME, "b", "B", kind = MediaKind.MOVIE)
        val fresh = old.copy(id = "fresh", providerId = "a")
        val unrelated = old.copy(id = "unrelated", title = "Other title")
        val state = FeedUiState(FeedSection.HOME, items = listOf(old, unrelated),
            lastUpdatedMs = 1_000L, searchQuery = "Search")
        val result = state.afterCatalogResult(listOf(fresh), listOf("b: offline"), 2, 2_000L)
        assertEquals(listOf(fresh, old), result.searchResults)
        assertEquals(state.items, result.items)
        assertEquals(1_000L, result.searchUpdatedMs)
        assertTrue(result.isCached)
        val expired = result.afterCatalogResult(listOf(fresh), listOf("b: offline"), 2, MAX_UI_STALE_MS + 1_001L)
        assertEquals(listOf(fresh), expired.searchResults)
    }

    @Test
    fun failedRefreshNeverRestoresOutOfSectionOrUnknownSavedRows() {
        val home = MediaCard("home", "Home", "", "", FeedSection.HOME, "p", "P", kind = MediaKind.MOVIE)
        val poisoned = listOf(home, home.copy(id = "adult", kind = MediaKind.ADULT),
            home.copy(id = "unknown", kind = MediaKind.UNKNOWN), home.copy(section = FeedSection.ADULT))
        val state = FeedUiState(FeedSection.HOME, items = poisoned, lastUpdatedMs = 1_000L,
            searchQuery = "Home", searchResults = poisoned, searchUpdatedMs = 1_000L)
        assertEquals(listOf(home), state.afterCatalogResult(emptyList(), listOf("offline"), 1, 2_000L).searchResults)
        assertEquals(listOf(home), state.copy(searchQuery = "")
            .afterCatalogResult(emptyList(), listOf("offline"), 1, 2_000L).items)
    }

    @Test
    fun feedFilterRejectsUnknownOrRelabeledKindsAndDeduplicatesProviderIdentity() {
        val home = MediaCard("same", "Home", "", "", FeedSection.HOME, "p", "P", kind = MediaKind.MOVIE)
        val otherProvider = home.copy(providerId = "q")
        assertEquals(listOf(home, otherProvider), FeedFilterHelper.enforceIsolation(listOf(
            home, home.copy(title = "duplicate"), otherProvider,
            home.copy(id = "adult", kind = MediaKind.ADULT), home.copy(id = "anime", kind = MediaKind.ANIME),
            home.copy(id = "unknown", kind = MediaKind.UNKNOWN), home.copy(id = "cross", section = FeedSection.ADULT)
        ), FeedSection.HOME))
    }

    @Test
    fun feedFilterHelper_strictlyEnforcesSectionBoundaries() {
        val homeCard = MediaCard(
            id = "m1",
            title = "Home Movie",
            subtitle = "2024",
            synopsis = "Action film",
            section = FeedSection.HOME,
            providerId = "p1",
            providerName = "P1", kind = MediaKind.MOVIE
        )
        val animeCard = MediaCard(
            id = "a1",
            title = "Anime Show",
            subtitle = "2023",
            synopsis = "Animation series",
            section = FeedSection.ANIME,
            providerId = "p2",
            providerName = "P2", kind = MediaKind.ANIME
        )
        val mixed = listOf(homeCard, animeCard)

        val homeIsolated = FeedFilterHelper.enforceIsolation(mixed, FeedSection.HOME)
        assertEquals(1, homeIsolated.size)
        assertEquals("m1", homeIsolated[0].id)
        assertEquals(FeedSection.HOME, homeIsolated[0].section)

        val animeIsolated = FeedFilterHelper.enforceIsolation(mixed, FeedSection.ANIME)
        assertEquals(1, animeIsolated.size)
        assertEquals("a1", animeIsolated[0].id)
        assertEquals(FeedSection.ANIME, animeIsolated[0].section)
    }

    @Test
    fun feedFilterHelper_formatsStatusBannerAccurately() {
        val cachedLabel = FeedFilterHelper.formatStatusBanner(isCached = true, sourceCount = 3, failureCount = 1)
        assertTrue(cachedLabel.contains("Cached snapshot"))
        assertTrue(cachedLabel.contains("3 sources configured"))
        assertTrue(cachedLabel.contains("1 source(s) unavailable"))

        val readyLabel = FeedFilterHelper.formatStatusBanner(isCached = false, sourceCount = 2, failureCount = 0)
        assertFalse(readyLabel.contains("Cached snapshot"))
        assertEquals("2 sources configured", readyLabel)
    }

    @Test
    fun feedUiState_correctlyPartitionsPlayableAndMetadataItems() {
        val streamSource = StreamSource(
            id = "s1",
            label = "720p",
            url = "https://example.com/video.mp4",
            protocol = StreamProtocol.MP4,
            quality = "720p",
            isDownloadable = true,
            providerId = "p1"
        )
        val playable = MediaCard(
            id = "p1",
            title = "Playable Movie",
            subtitle = "",
            synopsis = "",
            section = FeedSection.HOME,
            providerId = "p1",
            providerName = "P1",
            streams = listOf(streamSource)
        )
        val metadataOnly = MediaCard(
            id = "m1",
            title = "Metadata Only Series",
            subtitle = "",
            synopsis = "",
            section = FeedSection.HOME,
            providerId = "p2",
            providerName = "P2",
            streams = emptyList()
        )

        val state = FeedUiState(
            section = FeedSection.HOME,
            items = listOf(playable, metadataOnly)
        )

        assertEquals(2, state.activeDisplayItems.size)
        assertEquals(1, state.playableItems.size)
        assertEquals("p1", state.playableItems[0].id)
        assertEquals(1, state.metadataItems.size)
        assertEquals("m1", state.metadataItems[0].id)
        assertEquals(playable, state.heroItem)
    }

    @Test
    fun searchDebounceCoordinator_cancelsSupersededQueriesAndTracksGeneration() = runBlocking {
        val executed = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = SearchDebounceCoordinator(this, debounceDelayMs = 40L)

        coordinator.onQueryChanged(FeedSection.HOME, "f") { _, q -> executed.add(q) }
        delay(10L)
        coordinator.onQueryChanged(FeedSection.HOME, "fl") { _, q -> executed.add(q) }
        delay(10L)
        coordinator.onQueryChanged(FeedSection.HOME, "flower") { _, q -> executed.add(q) }

        delay(100L)
        assertEquals(listOf("flower"), executed)
    }

    @Test
    fun searchDebounceCoordinator_isolatesSectionsIndependently() = runBlocking {
        val homeQueries = Collections.synchronizedList(mutableListOf<String>())
        val animeQueries = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = SearchDebounceCoordinator(this, debounceDelayMs = 30L)

        coordinator.onQueryChanged(FeedSection.HOME, "matrix") { _, q -> homeQueries.add(q) }
        coordinator.onQueryChanged(FeedSection.ANIME, "naruto") { _, q -> animeQueries.add(q) }

        delay(80L)
        assertEquals(listOf("matrix"), homeQueries)
        assertEquals(listOf("naruto"), animeQueries)
    }
}
