package com.cinator.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinator.app.data.CloudStreamRepoParser
import com.cinator.app.data.LiveNetworkAdapters
import com.cinator.app.data.NuvioManifestParser
import com.cinator.app.data.SectionStateCoordinator
import com.cinator.app.domain.BdixHostStatus
import com.cinator.app.domain.DownloadEntry
import com.cinator.app.domain.DownloadStatus
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.ProviderDescriptor
import com.cinator.app.domain.SectionStoreState
import com.cinator.app.domain.SourceClass
import com.cinator.app.domain.StreamSource
import com.cinator.app.downloads.CinatorDownloadManager
import com.cinator.app.playback.CinatorPlayerPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF09090B)
private val CardColor = Color(0xFF18181B)
private val ElevatedCard = Color(0xFF222227)
private val Accent = Color(0xFFE50914)
private val MutedText = Color(0xFFA1A1AA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CinatorApp() }
    }
}

private data class ActivePlayback(
    val title: String,
    val stream: StreamSource,
    val localFilePath: String? = null
)

@Composable
fun CinatorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember { SectionStateCoordinator(adultInitiallyEnabled = false) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var homeState by remember { mutableStateOf(coordinator.getState(FeedSection.HOME)) }
    var animeState by remember { mutableStateOf(coordinator.getState(FeedSection.ANIME)) }
    var kdramaState by remember { mutableStateOf(coordinator.getState(FeedSection.KDRAMA)) }
    var liveTvState by remember { mutableStateOf(coordinator.getState(FeedSection.LIVETV)) }
    var adultState by remember { mutableStateOf(coordinator.getState(FeedSection.ADULT)) }
    var adultEnabled by remember { mutableStateOf(coordinator.isAdultEnabled) }

    var selectedCard by remember { mutableStateOf<MediaCard?>(null) }
    var activePlayback by remember { mutableStateOf<ActivePlayback?>(null) }
    val downloads = remember { mutableStateListOf<DownloadEntry>() }
    var bdixResults by remember { mutableStateOf<List<BdixHostStatus>>(emptyList()) }
    var bdixProbing by remember { mutableStateOf(false) }

    fun refreshSection(section: FeedSection, customQuery: String? = null) {
        scope.launch {
            when (section) {
                FeedSection.HOME -> homeState = homeState.copy(isLoading = true, errorMessage = null)
                FeedSection.ANIME -> animeState = animeState.copy(isLoading = true, errorMessage = null)
                FeedSection.KDRAMA -> kdramaState = kdramaState.copy(isLoading = true, errorMessage = null)
                FeedSection.LIVETV -> liveTvState = liveTvState.copy(isLoading = true, errorMessage = null)
                FeedSection.ADULT -> return@launch
            }
            try {
                val fetched = withContext(Dispatchers.IO) {
                    if (section == FeedSection.LIVETV) {
                        LiveNetworkAdapters.fetchIptvChannels()
                    } else {
                        LiveNetworkAdapters.fetchTvMazeSectionCatalog(section, customQuery)
                    }
                }
                val baseCurated = SectionStateCoordinator.defaultCuratedItems(section)
                val merged = baseCurated + fetched
                val updated = coordinator.updateSectionItems(section, merged)
                when (section) {
                    FeedSection.HOME -> homeState = updated
                    FeedSection.ANIME -> animeState = updated
                    FeedSection.KDRAMA -> kdramaState = updated
                    FeedSection.LIVETV -> liveTvState = updated
                    FeedSection.ADULT -> adultState = updated
                }
            } catch (e: Exception) {
                val errState = coordinator.recordSectionError(
                    section,
                    "Live sync failed (${e.message ?: e.javaClass.simpleName}). Showing isolated local catalog."
                )
                when (section) {
                    FeedSection.HOME -> homeState = errState
                    FeedSection.ANIME -> animeState = errState
                    FeedSection.KDRAMA -> kdramaState = errState
                    FeedSection.LIVETV -> liveTvState = errState
                    FeedSection.ADULT -> adultState = errState
                }
            }
        }
    }

    fun triggerDownload(card: MediaCard, stream: StreamSource) {
        scope.launch {
            val finalEntry = withContext(Dispatchers.IO) {
                CinatorDownloadManager.downloadDirectMp4(context, card, stream) { progress ->
                    scope.launch {
                        val idx = downloads.indexOfFirst { it.id == progress.id }
                        if (idx >= 0) downloads[idx] = progress else downloads.add(0, progress)
                    }
                }
            }
            val idx = downloads.indexOfFirst { it.id == finalEntry.id }
            if (idx >= 0) downloads[idx] = finalEntry else downloads.add(0, finalEntry)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            Scaffold(
                containerColor = Background,
                bottomBar = {
                    NavigationBar(
                        containerColor = Color(0xFF111113),
                        modifier = Modifier.navigationBarsPadding()
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
                                onClick = { selectedTab = index },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .statusBarsPadding()
                ) {
                    activePlayback?.let { pb ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            CinatorPlayerPanel(
                                title = pb.title,
                                stream = pb.stream,
                                localOverridePath = pb.localFilePath,
                                onClose = { activePlayback = null }
                            )
                        }
                    }

                    selectedCard?.let { card ->
                        MediaDetailSheet(
                            card = card,
                            onPlayStream = { stream ->
                                activePlayback = ActivePlayback(card.title, stream)
                            },
                            onDownloadStream = { stream ->
                                triggerDownload(card, stream)
                            },
                            onOpenExternal = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            onClose = { selectedCard = null }
                        )
                    }

                    when (selectedTab) {
                        0 -> IsolatedSectionScreen(
                            state = homeState,
                            heroSubtitle = "Movies & Series only • Direct Launch (No Login / No Profiles)",
                            onSearchChange = { q ->
                                homeState = coordinator.updateSearch(FeedSection.HOME, q)
                            },
                            onSyncLive = { refreshSection(FeedSection.HOME, homeState.searchQuery) },
                            onSelectCard = { selectedCard = it },
                            onQuickPlay = { card, stream ->
                                activePlayback = ActivePlayback(card.title, stream)
                            }
                        )

                        1 -> IsolatedSectionScreen(
                            state = animeState,
                            heroSubtitle = "Anime Feed Only • Isolated Store, Search & Providers",
                            onSearchChange = { q ->
                                animeState = coordinator.updateSearch(FeedSection.ANIME, q)
                            },
                            onSyncLive = { refreshSection(FeedSection.ANIME, animeState.searchQuery) },
                            onSelectCard = { selectedCard = it },
                            onQuickPlay = { card, stream ->
                                activePlayback = ActivePlayback(card.title, stream)
                            }
                        )

                        2 -> IsolatedSectionScreen(
                            state = kdramaState,
                            heroSubtitle = "K-Drama & Asian Drama Only • Isolated Store & Search",
                            onSearchChange = { q ->
                                kdramaState = coordinator.updateSearch(FeedSection.KDRAMA, q)
                            },
                            onSyncLive = { refreshSection(FeedSection.KDRAMA, kdramaState.searchQuery) },
                            onSelectCard = { selectedCard = it },
                            onQuickPlay = { card, stream ->
                                activePlayback = ActivePlayback(card.title, stream)
                            }
                        )

                        else -> MoreHubScreen(
                            liveTvState = liveTvState,
                            adultState = adultState,
                            adultEnabled = adultEnabled,
                            downloads = downloads,
                            bdixResults = bdixResults,
                            bdixProbing = bdixProbing,
                            onSyncLiveTv = { refreshSection(FeedSection.LIVETV) },
                            onSelectCard = { selectedCard = it },
                            onPlayStream = { title, stream, localPath ->
                                activePlayback = ActivePlayback(title, stream, localPath)
                            },
                            onToggleAdultOptIn = { enabled ->
                                adultEnabled = enabled
                                adultState = coordinator.setAdultOptIn(enabled)
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

@Composable
private fun IsolatedSectionScreen(
    state: SectionStoreState,
    heroSubtitle: String,
    onSearchChange: (String) -> Unit,
    onSyncLive: () -> Unit,
    onSelectCard: (MediaCard) -> Unit,
    onQuickPlay: (MediaCard, StreamSource) -> Unit
) {
    val displayedItems = if (state.searchQuery.isNotBlank()) state.searchResults else state.items
    val playableItems = displayedItems.filter { it.streams.isNotEmpty() }
    val metadataItems = displayedItems.filter { it.streams.isEmpty() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Cinator • ${state.section.displayName}",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = heroSubtitle, color = MutedText, fontSize = 12.sp)
            }
            Button(
                onClick = onSyncLive,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.isLoading) "Syncing..." else "Live Sync", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MutedText) },
            placeholder = {
                Text("Search only in ${state.section.displayName} (${state.cacheKey})...", color = MutedText, fontSize = 13.sp)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        state.errorMessage?.let { err ->
            Text(
                text = err,
                color = Color(0xFFFBBF24),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Hero feature banner for the first playable item in this section
        displayedItems.firstOrNull()?.let { hero ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF450A0A), Color(0xFF18181B), Color(0xFF09090B))
                        )
                    )
                    .clickable { onSelectCard(hero) }
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        text = "${state.section.displayName.uppercase()} FEATURED • ${hero.providerName}",
                        color = Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(hero.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(hero.subtitle, color = Color.LightGray, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(hero.synopsis, color = MutedText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        hero.streams.firstOrNull()?.let { firstStream ->
                            Button(
                                onClick = { onQuickPlay(hero, firstStream) },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Play Now", fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(onClick = { onSelectCard(hero) }) {
                            Text("Details & Sources", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (playableItems.isNotEmpty()) {
            MediaRail(
                title = "Verified In-App Playable (${state.section.displayName})",
                items = playableItems,
                onSelectCard = onSelectCard
            )
        }

        if (metadataItems.isNotEmpty()) {
            MediaRail(
                title = "Live Catalog Metadata (${state.section.displayName})",
                items = metadataItems,
                onSelectCard = onSelectCard
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "Scoped Providers (${state.section.displayName} • ${state.providers.size})",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        state.providers.forEach { provider ->
            ProviderBadgeRow(provider)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MediaRail(
    title: String,
    items: List<MediaCard>,
    onSelectCard: (MediaCard) -> Unit
) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.forEach { item ->
                Column(
                    modifier = Modifier
                        .width(165.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardColor)
                        .clickable { onSelectCard(item) }
                        .padding(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(105.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF3F3F46), Color(0xFF18181B))
                                )
                            )
                            .padding(8.dp)
                    ) {
                        Text(
                            text = if (item.streams.isNotEmpty()) "PLAYABLE" else "METADATA",
                            color = if (item.streams.isNotEmpty()) Color(0xFF4ADE80) else Color(0xFF60A5FA),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(item.subtitle, color = MutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.rating?.let { r ->
                        Text("★ $r", color = Color(0xFFFBBF24), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDetailSheet(
    card: MediaCard,
    onPlayStream: (StreamSource) -> Unit,
    onDownloadStream: (StreamSource) -> Unit,
    onOpenExternal: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ElevatedCard)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("${card.section.displayName} • ${card.providerName}", color = Accent, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onClose) {
                Text("Close", color = Color.White, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(card.synopsis, color = Color.LightGray, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        if (card.streams.isNotEmpty()) {
            Text("Verified Streams: Play / Seek / Download", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            card.streams.forEach { stream ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardColor)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stream.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${stream.protocol} • ${stream.quality}", color = MutedText, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { onPlayStream(stream) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text("Play", fontSize = 12.sp)
                    }
                    if (CinatorDownloadManager.canDownloadStream(stream)) {
                        OutlinedButton(onClick = { onDownloadStream(stream) }) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("MP4", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            Text(
                text = "Metadata-only entry (${card.providerName}). No unauthorized stream scraping is performed.",
                color = MutedText,
                fontSize = 12.sp
            )
            card.externalUrl?.let { url ->
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onOpenExternal(url) }) {
                    Text("Open Official Page", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProviderBadgeRow(provider: ProviderDescriptor) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardColor)
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(provider.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                text = provider.sourceClass.name,
                color = if (provider.canPlayInApp) Color(0xFF4ADE80) else MutedText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text("${provider.protocol} • ${provider.endpoint}", color = MutedText, fontSize = 11.sp)
        Text(provider.capabilityNote, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MoreHubScreen(
    liveTvState: SectionStoreState,
    adultState: SectionStoreState,
    adultEnabled: Boolean,
    downloads: List<DownloadEntry>,
    bdixResults: List<BdixHostStatus>,
    bdixProbing: Boolean,
    onSyncLiveTv: () -> Unit,
    onSelectCard: (MediaCard) -> Unit,
    onPlayStream: (String, StreamSource, String?) -> Unit,
    onToggleAdultOptIn: (Boolean) -> Unit,
    onRunBdixProbe: () -> Unit
) {
    val sampleNuvioValidation = remember {
        NuvioManifestParser.parseManifestJson(
            """{"id":"org.cinator.contract","name":"Nuvio Stremio Contract","version":"1.0.0","resources":["catalog","stream"],"types":["movie","series"],"catalogs":[{"type":"movie","id":"top"}]}"""
        ).getOrNull()
    }
    val sampleCsValidation = remember {
        CloudStreamRepoParser.parseRepoJson(
            """{"name":"CloudStream Contract Descriptor","manifestVersion":1,"pluginLists":["https://raw.githubusercontent.com/recloudstream/extensions/master/plugins.json"]}"""
        ).getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("More • Isolated Sections & Engine Tools", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // 1. Isolated Live TV Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardColor)
                .padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LiveTv, contentDescription = null, tint = Accent)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live TV (Isolated M3U / HLS Store)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Public IPTV-org #EXTM3U parser • Live HLS playback", color = MutedText, fontSize = 11.sp)
                }
                Button(
                    onClick = onSyncLiveTv,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text(if (liveTvState.isLoading) "Loading..." else "Load M3U", fontSize = 12.sp)
                }
            }
            liveTvState.errorMessage?.let { err ->
                Text(err, color = Color(0xFFFBBF24), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            liveTvState.items.take(8).forEach { channel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ElevatedCard)
                        .clickable { onSelectCard(channel) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(channel.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(channel.subtitle, color = MutedText, fontSize = 11.sp)
                    }
                    channel.streams.firstOrNull()?.let { stream ->
                        OutlinedButton(onClick = { onPlayStream(channel.title, stream, null) }) {
                            Text("Play Live", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 2. Offline Downloads Manager
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardColor)
                .padding(14.dp)
        ) {
            Text("Offline Downloads (${downloads.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Enabled only for authorized direct MP4 streams with HTTP range support.", color = MutedText, fontSize = 11.sp)
            if (downloads.isEmpty()) {
                Text(
                    text = "No offline files yet. Open a playable card in Home, Anime, or K-Drama and tap MP4 to download.",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                downloads.forEach { dl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElevatedCard)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(dl.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${dl.section.displayName} • ${dl.status} • ${dl.bytesDownloaded / 1024} KB",
                                color = MutedText,
                                fontSize = 11.sp
                            )
                        }
                        if (dl.status == DownloadStatus.COMPLETED) {
                            Button(
                                onClick = {
                                    onPlayStream(
                                        dl.title,
                                        StreamSource(
                                            id = dl.id,
                                            label = "Offline MP4",
                                            url = dl.sourceUrl,
                                            protocol = com.cinator.app.domain.StreamProtocol.MP4,
                                            quality = "Local File",
                                            isDownloadable = false,
                                            providerId = "local"
                                        ),
                                        dl.localFilePath
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                Text("Play Offline", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3. Opt-in Local BDIX / FTP Wi-Fi Host Probe
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardColor)
                .padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BDIX / Local FTP Discovery (Opt-In)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Bounded HTTP HEAD probe of declared BDIX hosts (2.5s timeout).", color = MutedText, fontSize = 11.sp)
                }
                OutlinedButton(onClick = onRunBdixProbe, enabled = !bdixProbing) {
                    Text(if (bdixProbing) "Probing..." else "Probe Hosts", color = Color.White, fontSize = 12.sp)
                }
            }
            bdixResults.forEach { host ->
                Text(
                    text = "• ${host.hostName} (${host.endpoint}): ${host.note} (${host.latencyMs}ms)",
                    color = if (host.reachable) Color(0xFF4ADE80) else MutedText,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 4. Nuvio & CloudStream Data-Contract Inspector
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardColor)
                .padding(14.dp)
        ) {
            Text("Nuvio & CloudStream Contract Mode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                text = "Manifests are validated as JSON data contracts. Arbitrary .cs3/DEX plugin bytecode execution is disabled.",
                color = MutedText,
                fontSize = 11.sp
            )
            sampleNuvioValidation?.let { m ->
                Text(
                    text = "✓ Nuvio Contract: ${m.name} v${m.version} (resources=${m.resources.joinToString()})",
                    color = Color(0xFF4ADE80),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            sampleCsValidation?.let { cs ->
                Text(
                    text = "✓ CloudStream Contract: ${cs.name} (policy=${cs.executionPolicy})",
                    color = Color(0xFF4ADE80),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 5. Isolated Adult Content Section (Disabled by default)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardColor)
                .padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Adult Content (18+ Isolated Store)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = "Disabled by default. Never mixes into Home, Anime, or K-Drama. Disabling wipes the section store.",
                        color = MutedText,
                        fontSize = 11.sp
                    )
                }
                Switch(checked = adultEnabled, onCheckedChange = onToggleAdultOptIn)
            }
            if (adultEnabled) {
                Spacer(Modifier.height(8.dp))
                adultState.items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElevatedCard)
                            .padding(10.dp)
                    ) {
                        Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.synopsis, color = MutedText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
