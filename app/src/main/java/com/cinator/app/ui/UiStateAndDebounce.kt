package com.cinator.app.ui

import com.cinator.app.data.belongsTo
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Isolated UI state for each feed section in Cinator.
 * Distinguishes cached snapshots from fresh remote syncs, tracks active queries,
 * and stores specific provider failure messages without suppressing successful sources.
 */
data class FeedUiState(
    val section: FeedSection,
    val isLoading: Boolean = false,
    val isCached: Boolean = false,
    val catalogIsCached: Boolean = false,
    val items: List<MediaCard> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<MediaCard> = emptyList(),
    val providerFailures: List<String> = emptyList(),
    val errorMessage: String? = null,
    val sourceCount: Int = 0,
    val lastUpdatedMs: Long = 0L,
    val searchUpdatedMs: Long = 0L
) {
    val activeDisplayItems: List<MediaCard>
        get() = if (searchQuery.isNotBlank()) searchResults else items

    val playableItems: List<MediaCard>
        get() = activeDisplayItems.filter { it.streams.isNotEmpty() }

    val metadataItems: List<MediaCard>
        get() = activeDisplayItems.filter { it.streams.isEmpty() }

    val heroItem: MediaCard?
        get() = playableItems.firstOrNull() ?: activeDisplayItems.firstOrNull()
}

/**
 * Coordinates refreshes and debounced searches scoped to a single section.
 * Uses guarded generation counters and per-section Job cancellation
 * to ensure outdated or superseded queries never overwrite current results.
 */
class SearchDebounceCoordinator(
    private val scope: CoroutineScope,
    private val debounceDelayMs: Long = 350L
) {
    // Main-thread generation changes never acquire a filesystem/commit mutex.
    // Keep this gate for short in-memory operations only.
    private val gate = Any()
    private val commitMutexes = FeedSection.entries.associateWith { Mutex() }
    private val sectionGenerations = mutableMapOf<FeedSection, Long>()
    private val activeJobs = mutableMapOf<FeedSection, Job>()

    fun onQueryChanged(
        section: FeedSection,
        query: String,
        onExecution: suspend (generation: Long, query: String) -> Unit
    ) = request(section, query, debounce = true, onExecution = onExecution)

    fun request(
        section: FeedSection,
        query: String,
        debounce: Boolean = false,
        onExecution: suspend (generation: Long, query: String) -> Unit
    ) = synchronized(gate) {
        val generation = cancel(section)
        activeJobs[section] = scope.launch {
            if (debounce) delay(debounceDelayMs)
            if (isLatestGeneration(section, generation)) onExecution(generation, query)
        }
    }

    fun isLatestGeneration(section: FeedSection, generation: Long): Boolean = synchronized(gate) {
        sectionGenerations[section] == generation
    }

    /** In-memory callbacks only; use [commitIfLatestSuspending] for prepared disk publication. */
    fun commitIfLatest(section: FeedSection, generation: Long, commit: () -> Unit): Boolean = synchronized(gate) {
        if (sectionGenerations[section] != generation) return@synchronized false
        commit()
        true
    }

    /**
     * Serializes publication per section without blocking generation invalidation.
     * Stage/serialize/fsync BEFORE calling this; the callback only publishes.
     * The generation check is the acceptance point: once accepted, a commit may
     * finish after cancel(). New publications and opt-out clears must use the
     * same section mutex ([withSectionCommit]) so they run after that commit.
     * This deliberately does not claim cancel() can undo an accepted IO syscall.
     */
    suspend fun commitIfLatestSuspending(
        section: FeedSection, generation: Long, commit: suspend () -> Unit
    ): Boolean = withSectionCommit(section) {
        if (!isLatestGeneration(section, generation)) false else {
            commit()
            true
        }
    }

    /** Order an unconditional clear behind already accepted section publications. */
    suspend fun <T> withSectionCommit(section: FeedSection, commit: suspend () -> T): T =
        commitMutexes.getValue(section).withLock { commit() }

    fun cancel(section: FeedSection): Long = synchronized(gate) {
        val generation = (sectionGenerations[section] ?: 0L) + 1L
        sectionGenerations[section] = generation
        activeJobs.remove(section)?.cancel()
        generation
    }
}

// In-memory fallback cannot live indefinitely; disk cache applies its own bound too.
const val MAX_UI_STALE_MS = 24L * 60L * 60L * 1_000L

fun shouldPersistCatalog(query: String, items: List<MediaCard>, failures: List<String>): Boolean =
    query.isBlank() && failures.isEmpty()

fun FeedUiState.afterCatalogResult(
    loaded: List<MediaCard>, failures: List<String>, count: Int, nowMs: Long
): FeedUiState {
    val scoped = FeedFilterHelper.enforceIsolation(loaded, section)
    val failedEmpty = scoped.isEmpty() && failures.isNotEmpty()
    val snapshotValid = lastUpdatedMs > 0L && nowMs - lastUpdatedMs in 0..MAX_UI_STALE_MS
    val retained = if (snapshotValid) FeedFilterHelper.enforceIsolation(items, section) else emptyList()
    val searchValid = searchUpdatedMs > 0L && nowMs - searchUpdatedMs in 0..MAX_UI_STALE_MS
    val retainedSearch = if (searchValid) FeedFilterHelper.enforceIsolation(searchResults, section)
        else retained.filter { it.matchesQuery(searchQuery) }
    val previous = if (searchQuery.isNotBlank()) retainedSearch else retained
    val freshKeys = scoped.map { it.providerId to it.id }.toSet()
    val fallback = if (failedEmpty) previous else previous.filter { card ->
        failures.any { it.startsWith("${card.providerId}:") || it.startsWith("${card.providerId} ") } &&
            (card.providerId to card.id) !in freshKeys
    }
    val combined = scoped + fallback
    val hasFallback = fallback.isNotEmpty()
    val message = when {
        failedEmpty && hasFallback -> "Sync unavailable; showing saved catalog. Playback is not reverified."
        failedEmpty -> "Catalog unavailable. No usable saved snapshot."
        hasFallback -> "Some sources unavailable; showing completed results with saved entries. Saved playback is not reverified."
        scoped.isEmpty() && searchQuery.isNotBlank() -> "No matching entries."
        scoped.isEmpty() -> "Catalog unavailable: no entries returned by the embedded adapters."
        else -> null
    }
    // Preserve the oldest snapshot age while any saved rows remain. A partial
    // refresh must not indefinitely renew unavailable providers' fallback TTL.
    return if (searchQuery.isNotBlank()) copy(
        searchResults = combined,
        searchUpdatedMs = if (failedEmpty || hasFallback) {
            if (searchValid) searchUpdatedMs else lastUpdatedMs
        } else nowMs,
        isLoading = false, isCached = hasFallback,
        sourceCount = count, providerFailures = failures, errorMessage = message
    ) else copy(
        items = combined,
        searchResults = emptyList(), isLoading = false,
        isCached = hasFallback, catalogIsCached = hasFallback, sourceCount = count,
        providerFailures = failures, errorMessage = message,
        lastUpdatedMs = if (failedEmpty || hasFallback) lastUpdatedMs else nowMs
    )
}

fun MediaCard.matchesQuery(query: String): Boolean =
    title.contains(query.trim(), ignoreCase = true) ||
        synopsis.contains(query.trim(), ignoreCase = true) ||
        genres.any { it.contains(query.trim(), ignoreCase = true) }

object FeedFilterHelper {
    /**
     * Enforces strict section boundaries: no items of another section may leak in.
     */
    fun enforceIsolation(items: List<MediaCard>, targetSection: FeedSection): List<MediaCard> {
        return items.filter { it.belongsTo(targetSection) }
            .distinctBy { it.providerId to it.id }
    }

    /**
     * Generates a user-facing label for the catalog status banner.
     */
    fun formatStatusBanner(isCached: Boolean, sourceCount: Int, failureCount: Int): String {
        val parts = mutableListOf<String>()
        if (isCached) {
            parts.add("Cached snapshot")
        }
        if (sourceCount > 0) {
            parts.add("$sourceCount sources configured")
        }
        if (failureCount > 0) {
            parts.add("$failureCount source(s) unavailable")
        }
        return if (parts.isEmpty()) "Catalog unavailable" else parts.joinToString(" • ")
    }
}
