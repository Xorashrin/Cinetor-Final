package com.cinator.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cinator.app.compat.CompatibilityMatrixParser
import com.cinator.app.data.CatalogCache
import com.cinator.app.data.CatalogRepository
import com.cinator.app.data.EmbeddedCatalog
import com.cinator.app.data.EmbeddedCatalogParser
import com.cinator.app.data.LiveNetworkAdapters
import com.cinator.app.domain.BdixHostStatus
import com.cinator.app.downloads.DownloadPolicy
import kotlinx.coroutines.runInterruptible
import com.cinator.app.ui.DownloadUiGate
import com.cinator.app.domain.DownloadStatus
import kotlinx.coroutines.channels.Channel
import com.cinator.app.domain.DownloadEntry
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.StreamSource
import com.cinator.app.downloads.CinatorDownloadManager
import com.cinator.app.playback.CinatorPlayerPanel
import com.cinator.app.ui.FeedFilterHelper
import com.cinator.app.ui.FeedUiState
import com.cinator.app.ui.ForegroundDownloadJobs
import com.cinator.app.ui.HeroBanner
import com.cinator.app.providers.BundledPlaybackProviders
import com.cinator.app.providers.PlaybackProvider
import com.cinator.app.providers.ProviderResolver
import com.cinator.app.providers.ResolveRequest
import com.cinator.app.providers.EpisodeRef
import com.cinator.app.ui.ResolvedMediaDialog
import com.cinator.app.ui.ResolvedSelectionState
import com.cinator.app.ui.MediaDetailDialog
import com.cinator.app.ui.MediaRail
import com.cinator.app.ui.MoreHubScreen
import com.cinator.app.ui.SearchDebounceCoordinator
import com.cinator.app.ui.SectionSearchBar
import com.cinator.app.ui.SectionStatusBar
import com.cinator.app.ui.theme.CinatorAccent
import com.cinator.app.ui.theme.CinatorBackground
import com.cinator.app.ui.theme.CinatorCard
import com.cinator.app.ui.theme.CinatorMutedText
import com.cinator.app.ui.theme.CinatorTheme
import com.cinator.app.ui.ReferenceCatalog
import com.cinator.app.ui.MAX_UI_STALE_MS
import com.cinator.app.ui.afterCatalogResult
import com.cinator.app.ui.shouldPersistCatalog
import com.cinator.app.ui.matchesQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CinatorTheme {
                CinatorApp()
            }
        }
    }
}

private data class ActivePlayback(
    val title: String,
    val stream: StreamSource,
    val localFilePath: String? = null,
    val fallbackSources: List<StreamSource> = emptyList()
)

@Composable
fun CinatorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val debounceCoordinator = remember { SearchDebounceCoordinator(scope, debounceDelayMs = 350L) }

    val catalogResult = remember {
        runCatching {
            context.assets.open("embedded_catalog.json").bufferedReader().use {
                EmbeddedCatalogParser.parse(it.readText())
            }
        }
    }
    val catalog = catalogResult.getOrElse { EmbeddedCatalog(sources = emptyList()) }
    val catalogError = catalogResult.exceptionOrNull()?.let {
        "Embedded source bundle unavailable: ${it.message ?: it.javaClass.simpleName}"
    }
    val referencesResult = remember {
        runCatching {
            context.assets.open("embedded_directory_snapshot.json").bufferedReader().use {
                ReferenceCatalog.parse(it.readText())
            }
        }
    }

    val compatibilityResult = remember {
        runCatching {
            context.assets.open("provider_compatibility.json").bufferedReader().use {
                CompatibilityMatrixParser.parse(it.readText())
            }
        }
    }

    // The tiny audited asset contains only registry data; provider network work runs on IO below.
    val providersResult = remember {
        runCatching {
            context.assets.open("open_films.json").bufferedReader().use {
                BundledPlaybackProviders.fromJson(it.readText())
            }
        }
    }
    val providers = providersResult.getOrElse { emptyList() }
    val providerError = providersResult.exceptionOrNull()?.let { "Open film provider bundle unavailable" }
    val repository = remember(catalog, providers) {
        CatalogRepository(bundle = catalog, playbackProviders = providers)
    }
    val resolver = remember(providers) { ProviderResolver(providers) }
    val cache = remember { CatalogCache(File(context.filesDir, "catalog_cache")) }

    var selectedTab by remember { mutableIntStateOf(0) }

    var homeState by remember { mutableStateOf(FeedUiState(section = FeedSection.HOME)) }
    var animeState by remember { mutableStateOf(FeedUiState(section = FeedSection.ANIME)) }
    var kdramaState by remember { mutableStateOf(FeedUiState(section = FeedSection.KDRAMA)) }
    var liveTvState by remember { mutableStateOf(FeedUiState(section = FeedSection.LIVETV)) }
    var adultState by remember { mutableStateOf(FeedUiState(section = FeedSection.ADULT)) }
    var adultEnabled by remember { mutableStateOf(false) }
    var adultWipeJob by remember { mutableStateOf<Job?>(null) }

    var resolvedSelection by remember { mutableStateOf(ResolvedSelectionState()) }
    val selectedCard = resolvedSelection.selectedCard
    var providerRequestJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose { providerRequestJob?.cancel() }
    }
    var activePlayback by remember { mutableStateOf<ActivePlayback?>(null) }
    val downloads = remember { mutableStateListOf<DownloadEntry>() }
    val downloadGate = remember { DownloadUiGate() }
    val downloadLifecycle = LocalLifecycleOwner.current.lifecycle
    val downloadJobs = remember(downloadLifecycle) { ForegroundDownloadJobs() }
    DisposableEffect(downloadLifecycle, downloadJobs) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> downloadJobs.setForeground(true)
                Lifecycle.Event.ON_STOP -> downloadJobs.setForeground(false)
                Lifecycle.Event.ON_DESTROY -> downloadJobs.close()
                else -> Unit
            }
        }
        downloadJobs.setForeground(downloadLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        downloadLifecycle.addObserver(observer)
        onDispose {
            downloadLifecycle.removeObserver(observer)
            downloadJobs.close()
        }
    }
    var bdixResults by remember { mutableStateOf<List<BdixHostStatus>>(emptyList()) }
    var bdixProbing by remember { mutableStateOf(false) }

    fun registeredProvider(card: MediaCard): PlaybackProvider? =
        providers.singleOrNull { it.info.id == card.providerId }?.takeIf {
            it.info.enabled && it.info.section == card.section && it.info.capabilities.details
        }

    fun hasProvider(card: MediaCard) = card.providerId == "blender-open-films" ||
        providers.any { it.info.id == card.providerId }

    fun loadSelectedDetails() {
        val request = resolvedSelection
        val card = request.selectedCard ?: return
        val provider = registeredProvider(card)
        if (provider == null) {
            resolvedSelection = resolvedSelection.fail(request.generation, "Provider unavailable for this selection")
            return
        }
        providerRequestJob?.cancel()
        providerRequestJob = scope.launch {
            try {
                val details = runInterruptible(Dispatchers.IO) { provider.details(card) }
                ensureActive()
                resolvedSelection = resolvedSelection.receiveDetails(request.generation, details)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                resolvedSelection = resolvedSelection.fail(request.generation, "Provider details unavailable. Please retry.")
            }
        }
    }

    fun selectCard(card: MediaCard?) {
        providerRequestJob?.cancel()
        resolvedSelection = resolvedSelection.select(card)
        if (card != null) {
            activePlayback = null
            if (hasProvider(card)) loadSelectedDetails()
        }
    }

    fun selectEpisode(episode: EpisodeRef) {
        val next = resolvedSelection.selectEpisode(episode)
        if (next != resolvedSelection) {
            providerRequestJob?.cancel()
            resolvedSelection = next
        }
    }

    fun resolveSelection() {
        if (!resolvedSelection.detailsLoaded) {
            // A failed details request is retried with a fresh token as well.
            val card = resolvedSelection.selectedCard ?: return
            selectCard(card)
            return
        }
        providerRequestJob?.cancel()
        resolvedSelection = resolvedSelection.beginResolve()
        val request = resolvedSelection
        val card = request.details?.card ?: return
        providerRequestJob = scope.launch {
            try {
                val sources = runInterruptible(Dispatchers.IO) {
                    resolver.resolve(ResolveRequest(card, request.episode))
                }
                ensureActive()
                resolvedSelection = resolvedSelection.receiveStreams(request.generation, sources)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                resolvedSelection = resolvedSelection.fail(request.generation, "Provider resolution unavailable. Please retry.")
            }
        }
    }

    fun setSectionState(section: FeedSection, transform: (FeedUiState) -> FeedUiState) {
        when (section) {
            FeedSection.HOME -> homeState = transform(homeState)
            FeedSection.ANIME -> animeState = transform(animeState)
            FeedSection.KDRAMA -> kdramaState = transform(kdramaState)
            FeedSection.LIVETV -> liveTvState = transform(liveTvState)
            FeedSection.ADULT -> adultState = transform(adultState)
        }
    }

    fun loadSection(section: FeedSection, customQuery: String = "", debounce: Boolean = false) {
        if (section == FeedSection.ADULT && !adultEnabled) return
        val query = customQuery.trim()
        setSectionState(section) { current ->
            current.copy(searchQuery = customQuery, isLoading = true, errorMessage = catalogError,
                isCached = if (query.isBlank()) current.catalogIsCached else current.isCached,
                searchResults = if (current.searchQuery.trim() == query) current.searchResults else emptyList(),
                searchUpdatedMs = if (current.searchQuery.trim() == query) current.searchUpdatedMs else 0L)
        }
        debounceCoordinator.request(section, query, debounce) { generation, requestedQuery ->
            fun isCurrent() = debounceCoordinator.isLatestGeneration(section, generation) &&
                (section != FeedSection.ADULT || adultEnabled)
            try {
                if (section == FeedSection.ADULT) adultWipeJob?.join()
                var cacheReadError: String? = null
                val cached = try { withContext(Dispatchers.IO) {
                    ensureActive()
                    val file = File(context.filesDir, "catalog_cache/${section.cacheKey}.json")
                    val stamp = file.lastModified()
                    val age = System.currentTimeMillis() - stamp
                    if (stamp > 0 && age in 0..MAX_UI_STALE_MS) {
                        FeedFilterHelper.enforceIsolation(cache.read(section), section) to stamp
                    } else emptyList<MediaCard>() to 0L
                } } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    cacheReadError = "Saved catalog unavailable: ${error.message}"
                    emptyList<MediaCard>() to 0L
                }
                if (!isCurrent()) return@request
                if (cached.first.isNotEmpty()) setSectionState(section) { current ->
                    if (current.items.isNotEmpty()) current else current.copy(
                        items = cached.first, lastUpdatedMs = cached.second, isCached = true, catalogIsCached = true,
                        searchResults = if (requestedQuery.isNotBlank())
                            cached.first.filter { it.matchesQuery(requestedQuery) } else emptyList())
                }
                val loaded = runInterruptible(Dispatchers.IO) {
                    repository.load(section, requestedQuery)
                }
                val result = loaded.copy(failures = loaded.failures + listOfNotNull(catalogError, providerError))
                if (!isCurrent()) return@request
                val scopedItems = FeedFilterHelper.enforceIsolation(result.items, section)
                setSectionState(section) {
                    val next = it.afterCatalogResult(scopedItems, result.failures, result.sourceCount, System.currentTimeMillis())
                    if (cacheReadError == null) next else next.copy(errorMessage = listOfNotNull(next.errorMessage, cacheReadError).joinToString(" • "))
                }
                if (shouldPersistCatalog(requestedQuery, scopedItems, result.failures)) {
                    try {
                        withContext(Dispatchers.IO) {
                            ensureActive()
                            cache.prepare(section, scopedItems).use { prepared ->
                                ensureActive()
                                debounceCoordinator.commitIfLatestSuspending(section, generation) {
                                    ensureActive()
                                    prepared.publish()
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (isCurrent()) setSectionState(section) {
                            it.copy(errorMessage = "Catalog loaded; cache save failed: ${error.message}")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent()) setSectionState(section) {
                    it.afterCatalogResult(emptyList(), listOf(error.message ?: "Catalog load failed"),
                        catalog.sources.count { source -> source.section == section && source.enabled } +
                            providers.count { it.info.section == section && it.info.enabled },
                        System.currentTimeMillis())
                }
            }
        }
    }

    fun onSearchQueryChange(section: FeedSection, newQuery: String) {
        // Clear is a new immediate unfiltered request, not just cancellation of the debounce.
        loadSection(section, newQuery, debounce = newQuery.isNotBlank())
    }

    fun triggerDownload(card: MediaCard, stream: StreamSource) {
        if (DownloadPolicy.rejectionReason(card, stream) != null) return
        val targetKey = DownloadPolicy.targetKey(card, stream)
        val target = File(context.filesDir, "downloads/v2/${card.section.name.lowercase(java.util.Locale.ROOT)}/$targetKey.mp4")
        if (downloads.any { it.localFilePath == target.absolutePath && it.status == DownloadStatus.COMPLETED }) return
        if (!downloadGate.begin(target.absolutePath)) return
        val queued = DownloadEntry("dl_$targetKey", card.id, card.title, card.section,
            stream.url, target.absolutePath, 0L, 0L, DownloadStatus.QUEUED,
            contentId = card.contentId, providerId = stream.providerId, streamId = stream.id)
        fun update(entry: DownloadEntry) {
            val index = downloads.indexOfFirst { it.id == entry.id }
            if (index < 0) downloads.add(0, entry) else downloads[index] = entry
        }
        update(queued)
        downloadJobs.launch(scope) {
            val progressChannel = Channel<DownloadEntry>(Channel.CONFLATED)
            val progressJob = launch { for (progress in progressChannel) update(progress) }
            var terminalEntry: DownloadEntry? = null
            try {
                terminalEntry = runInterruptible(Dispatchers.IO) {
                    CinatorDownloadManager.downloadDirectMp4(context, card, stream) { progress ->
                        if (downloadGate.acceptProgress(target.absolutePath, android.os.SystemClock.elapsedRealtime())) {
                            progressChannel.trySend(progress)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                terminalEntry = (downloads.firstOrNull { it.id == queued.id } ?: queued).copy(
                    status = DownloadStatus.CANCELLED,
                    errorMessage = "Download cancelled; downloads run only while the app is in the foreground"
                )
                throw cancelled
            } catch (error: Exception) {
                terminalEntry = queued.copy(status = DownloadStatus.FAILED,
                    errorMessage = error.message ?: "Download failed")
            } finally {
                // Drain the child before terminal delivery so stale progress cannot replace it.
                // Cancellation must still release admission, but only after the worker is done.
                progressChannel.cancel()
                try {
                    withContext(NonCancellable) { progressJob.cancelAndJoin() }
                } finally {
                    downloadGate.finish(target.absolutePath)
                    terminalEntry?.let { update(it) }
                }
            }
        } ?: run {
            // A click delivered after ON_STOP must not leave a queued row or a held gate.
            downloadGate.finish(target.absolutePath)
            update(queued.copy(status = DownloadStatus.CANCELLED,
                errorMessage = "Downloads run only while the app is in the foreground"))
        }
    }

    fun wipeAdultCache() {
        val previousWipe = adultWipeJob
        adultWipeJob = scope.launch {
            try {
                previousWipe?.join()
                withContext(Dispatchers.IO) {
                    debounceCoordinator.withSectionCommit(FeedSection.ADULT) {
                        cache.clear(FeedSection.ADULT)
                    }
                    check(!File(context.filesDir, "catalog_cache/${FeedSection.ADULT.cacheKey}.json").exists()) {
                        "Adult snapshot could not be deleted"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                adultState = adultState.copy(errorMessage = "Adult cache wipe failed: ${error.message}")
            }
        }
    }

    // Auto-load once on startup across standard sections.
    LaunchedEffect(Unit) {
        wipeAdultCache()
        listOf(FeedSection.HOME, FeedSection.ANIME, FeedSection.KDRAMA, FeedSection.LIVETV).forEach { section ->
            loadSection(section)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = CinatorBackground) {
        Scaffold(
            containerColor = CinatorBackground,
            bottomBar = {
                NavigationBar(
                    containerColor = CinatorCard,
                    modifier = Modifier
                ) {
                    val tabs = listOf(
                        "Home" to Icons.Default.Home,
                        "Anime" to Icons.Default.Movie,
                        "K-Drama" to Icons.Default.Tv,
                        "More" to Icons.Default.Settings
                    )
                    tabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = {
                                if (selectedTab != index) {
                                    selectCard(null)
                                    activePlayback = null
                                    selectedTab = index
                                }
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CinatorAccent,
                                selectedTextColor = CinatorAccent,
                                unselectedIconColor = CinatorMutedText,
                                unselectedTextColor = CinatorMutedText,
                                indicatorColor = Color(0xFF27272A)
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                listOfNotNull(catalogError, providerError).forEach {
                    Text(it, color = Color(0xFFFBBF24), modifier = Modifier.padding(16.dp))
                }
                // Media3 ExoPlayer playback panel with fallback sources
                activePlayback?.let { pb ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        CinatorPlayerPanel(
                            title = pb.title,
                            stream = pb.stream,
                            localOverridePath = pb.localFilePath,
                            onClose = { activePlayback = null },
                            fallbackSources = pb.fallbackSources
                        )
                    }
                }

                // Modal detail dialog (prevents stacking player + detail + feed beyond screen)
                selectedCard?.let { card ->
                    if (hasProvider(card)) {
                        val rendered = resolvedSelection
                        rendered.details?.let { details ->
                            ResolvedMediaDialog(
                                details = details, loading = rendered.loading, error = rendered.error,
                                onEpisode = { episode ->
                                    if (rendered.generation == resolvedSelection.generation) selectEpisode(episode)
                                },
                                onResolve = {
                                    if (rendered.generation == resolvedSelection.generation) resolveSelection()
                                },
                                onPlay = { stream ->
                                    if (rendered.generation == resolvedSelection.generation) {
                                        resolvedSelection.actionCard(stream)?.let { actual ->
                                            val fallbacks = actual.streams.filter { it != stream &&
                                                it.contentId == stream.contentId && it.providerId == stream.providerId &&
                                                it.section == stream.section && it.isLive == stream.isLive }
                                            selectCard(null)
                                            activePlayback = ActivePlayback(actual.title, stream, fallbackSources = fallbacks)
                                        }
                                    }
                                },
                                onDownload = { stream ->
                                    if (rendered.generation == resolvedSelection.generation) {
                                        resolvedSelection.actionCard(stream)?.let { triggerDownload(it, stream) }
                                    }
                                },
                                onClose = {
                                    if (rendered.generation == resolvedSelection.generation) selectCard(null)
                                }
                            )
                        }
                    } else MediaDetailDialog(
                        card = card,
                        downloadBlocked = card.streams.any { stream ->
                            val id = "dl_${DownloadPolicy.targetKey(card, stream)}"
                            downloads.any { it.id == id && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED) }
                        },
                        onPlayStream = { stream ->
                            selectCard(null)
                            val fallbacks = card.streams.filter { it.id != stream.id && it.section == card.section }
                            activePlayback = ActivePlayback(
                                title = card.title,
                                stream = stream,
                                fallbackSources = fallbacks
                            )
                        },
                        onDownloadStream = { stream -> triggerDownload(card, stream) },
                        onOpenExternal = { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                        onClose = { selectCard(null) }
                    )
                }

                // Isolated screens per tab
                when (selectedTab) {
                    0 -> SectionFeedView(
                        state = homeState,
                        heroSubtitle = "Movies & Series • Direct Launch (No Login / No Profiles)",
                        onQueryChange = { q -> onSearchQueryChange(FeedSection.HOME, q) },
                        onSync = { loadSection(FeedSection.HOME, homeState.searchQuery) },
                        onSelectCard = { selectCard(it) },
                        onQuickPlay = { stream ->
                            val hero = homeState.heroItem
                            if (hero != null && hasProvider(hero)) {
                                selectCard(hero) // Cached/catalog streams must be freshly resolved before playback.
                            } else {
                                val fallbacks = hero?.streams?.filter { it.id != stream.id && it.section == FeedSection.HOME } ?: emptyList()
                                selectCard(null)
                                activePlayback = ActivePlayback(hero?.title ?: "Stream", stream, fallbackSources = fallbacks)
                            }
                        }
                    )

                    1 -> SectionFeedView(
                        state = animeState,
                        heroSubtitle = "Anime Catalog & Streams • Isolated Store & Search",
                        onQueryChange = { q -> onSearchQueryChange(FeedSection.ANIME, q) },
                        onSync = { loadSection(FeedSection.ANIME, animeState.searchQuery) },
                        onSelectCard = { selectCard(it) },
                        onQuickPlay = { stream ->
                            val hero = animeState.heroItem
                            if (hero != null && hasProvider(hero)) {
                                selectCard(hero) // Cached/catalog streams must be freshly resolved before playback.
                            } else {
                                val fallbacks = hero?.streams?.filter { it.id != stream.id && it.section == FeedSection.ANIME } ?: emptyList()
                                selectCard(null)
                                activePlayback = ActivePlayback(hero?.title ?: "Anime Stream", stream, fallbackSources = fallbacks)
                            }
                        }
                    )

                    2 -> SectionFeedView(
                        state = kdramaState,
                        heroSubtitle = "Korean Scripted Drama • Isolated Store & Search",
                        onQueryChange = { q -> onSearchQueryChange(FeedSection.KDRAMA, q) },
                        onSync = { loadSection(FeedSection.KDRAMA, kdramaState.searchQuery) },
                        onSelectCard = { selectCard(it) },
                        onQuickPlay = { stream ->
                            val hero = kdramaState.heroItem
                            if (hero != null && hasProvider(hero)) {
                                selectCard(hero) // Cached/catalog streams must be freshly resolved before playback.
                            } else {
                                val fallbacks = hero?.streams?.filter { it.id != stream.id && it.section == FeedSection.KDRAMA } ?: emptyList()
                                selectCard(null)
                                activePlayback = ActivePlayback(hero?.title ?: "Drama Stream", stream, fallbackSources = fallbacks)
                            }
                        }
                    )

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            item {
                                MoreHubScreen(
                                    liveTvState = liveTvState,
                                    adultState = adultState,
                                    adultEnabled = adultEnabled,
                                    downloads = downloads.filter { adultEnabled || it.section != FeedSection.ADULT },
                                    bdixResults = bdixResults,
                                    bdixProbing = bdixProbing,
                                    catalog = catalog,
                                    playbackProviders = providers.map { it.info },
                                    sectionStates = listOf(homeState, animeState, kdramaState, liveTvState, adultState),
                                    references = referencesResult.getOrNull(),
                                    referencesError = referencesResult.exceptionOrNull()?.message,
                                    compatibilityMatrix = compatibilityResult.getOrNull(),
                                    compatibilityError = compatibilityResult.exceptionOrNull()?.let {
                                        it.message ?: it.javaClass.simpleName
                                    },
                                    onLiveTvQueryChange = { onSearchQueryChange(FeedSection.LIVETV, it) },
                                    onSyncLiveTv = { loadSection(FeedSection.LIVETV, liveTvState.searchQuery) },
                                    onSelectCard = { selectCard(it) },
                                    onPlayStream = { title, stream, path ->
                                        selectCard(null)
                                        activePlayback = ActivePlayback(title, stream, localFilePath = path)
                                    },
                                    onToggleAdultOptIn = { enabled ->
                                        adultEnabled = enabled
                                        if (enabled) {
                                            loadSection(FeedSection.ADULT)
                                        } else {
                                            debounceCoordinator.cancel(FeedSection.ADULT)
                                            selectCard(null)
                                            activePlayback = null
                                            adultState = FeedUiState(section = FeedSection.ADULT)
                                            wipeAdultCache()
                                        }
                                    },
                                    onRunBdixProbe = {
                                        scope.launch {
                                            bdixProbing = true
                                            bdixResults = withContext(Dispatchers.IO) {
                                                LiveNetworkAdapters.probeDeclaredBdixHosts()
                                            }
                                            bdixProbing = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionFeedView(
    state: FeedUiState,
    heroSubtitle: String,
    onQueryChange: (String) -> Unit,
    onSync: () -> Unit,
    onSelectCard: (MediaCard) -> Unit,
    onQuickPlay: (StreamSource) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Header row with Sync button
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Cinator • ${state.section.displayName}",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(heroSubtitle, color = CinatorMutedText, fontSize = 11.sp)
                    }
                    Button(
                        onClick = onSync,
                        colors = ButtonDefaults.buttonColors(containerColor = CinatorAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (state.isLoading) "Syncing..." else "Sync", fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))

                SectionSearchBar(
                    query = state.searchQuery,
                    placeholder = "Search only in ${state.section.displayName}...",
                    onQueryChange = onQueryChange
                )

                Spacer(Modifier.height(6.dp))

                SectionStatusBar(
                    isCached = state.isCached,
                    sourceCount = state.sourceCount,
                    failures = state.providerFailures
                )

                state.errorMessage?.let { err ->
                    Text(text = err, color = Color(0xFFFBBF24), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        if (!state.isLoading && state.activeDisplayItems.isEmpty()) {
            item { Text(state.errorMessage ?: "Catalog unavailable: no entries from embedded adapters.",
                color = CinatorMutedText, modifier = Modifier.padding(16.dp)) }
        }

        // Real resolved-provider entries remain accessible without scrolling through metadata rails.
        val openFilms = state.activeDisplayItems.filter { it.providerId == "blender-open-films" }
        if (openFilms.isNotEmpty()) {
            item {
                MediaRail(title = "Blender Open Films • resolve to watch", items = openFilms, onSelectCard = onSelectCard)
            }
        }

        // Hero banner
        state.heroItem?.let { hero ->
            item {
                HeroBanner(
                    item = hero,
                    onPlay = onQuickPlay,
                    onDetails = { onSelectCard(hero) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Playable in-app streams rail
        if (state.playableItems.isNotEmpty()) {
            item {
                MediaRail(
                    title = "Streamable in ${state.section.displayName}",
                    items = state.playableItems,
                    onSelectCard = onSelectCard
                )
            }
        }

        // Catalog metadata entries rail
        if (state.metadataItems.isNotEmpty()) {
            item {
                MediaRail(
                    title = "Catalog Entries (${state.section.displayName})",
                    items = state.metadataItems,
                    onSelectCard = onSelectCard
                )
            }
        }
    }
}
