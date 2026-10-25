package com.cinator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import com.cinator.app.downloads.DownloadPolicy
import com.cinator.app.providers.EpisodeRef
import com.cinator.app.providers.ProviderDetails
import com.cinator.app.ui.theme.CinatorAccent
import com.cinator.app.ui.theme.CinatorCard
import com.cinator.app.ui.theme.CinatorElevatedCard
import com.cinator.app.ui.theme.CinatorErrorRed
import com.cinator.app.ui.theme.CinatorMutedText

/**
 * Provider results only: never resolves, plays or downloads on composition.
 * The caller owns requests, generation checks, errors and details.card.streams.
 * onEpisode also announces the first/default episode (without resolving it).
 * Keep the card identity stable when publishing episode streams. The caller must
 * cancel/discard late responses when content or the selected episode changes.
 * DownloadPolicy is only a UI hint; the caller must authorize the download again.
 */
@Composable
fun ResolvedMediaDialog(
    details: ProviderDetails,
    loading: Boolean,
    error: String?,
    onEpisode: (EpisodeRef) -> Unit,
    onResolve: () -> Unit,
    onPlay: (StreamSource) -> Unit,
    onDownload: (StreamSource) -> Unit,
    onClose: () -> Unit
) {
    val card = details.card
    val contentKey = listOf(card.section.name, card.providerId, card.id, card.contentId.orEmpty())
        .joinToString("") { "${it.length}:$it" }
    var picker by remember(contentKey) {
        mutableStateOf(ResolvedDetailsState.initial(contentKey, details.episodes))
    }
    val selection = picker.forContent(contentKey, details.episodes)
    SideEffect { if (picker != selection) picker = selection }
    val selectedEpisode = selection.selectedEpisode(details.episodes)
    val notifyEpisode by rememberUpdatedState(onEpisode)
    LaunchedEffect(contentKey, selectedEpisode?.id, selectedEpisode?.contentId) {
        selectedEpisode?.let(notifyEpisode)
    }
    val streams = selection.visibleStreams(card, details.episodes, loading || error != null)
    val imageLoader = rememberSafeImageLoader(LocalContext.current)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 720.dp),
            shape = RoundedCornerShape(20.dp),
            color = CinatorCard,
            contentColor = Color.White
        ) {
            Column {
                // Always reachable, including on small screens and with long episode lists.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Provider details", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close provider details")
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        val artwork = card.backdropUrl ?: card.posterUrl
                        if (!artwork.isNullOrBlank()) {
                            Box(Modifier.fillMaxWidth().height(180.dp).background(CinatorElevatedCard)) {
                                AsyncImage(
                                    model = artwork,
                                    imageLoader = imageLoader,
                                    contentDescription = "Artwork for ${card.title}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(card.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("${card.providerName} · ${card.section.displayName}", color = CinatorMutedText)
                            if (card.subtitle.isNotBlank()) Text(card.subtitle)
                            val metadata = listOfNotNull(card.year, card.rating?.let { "Rating: $it" })
                            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), color = CinatorMutedText)
                            if (card.genres.isNotEmpty()) Text(card.genres.joinToString(" · "), color = CinatorMutedText)
                            if (card.synopsis.isNotBlank()) Text(card.synopsis, fontSize = 14.sp, lineHeight = 21.sp)
                        }
                    }
                    if (details.episodes.isNotEmpty()) {
                        item {
                            Text("Episodes (${details.episodes.size})", modifier = Modifier.padding(horizontal = 16.dp),
                                fontWeight = FontWeight.Bold)
                        }
                        items(details.episodes) { episode ->
                            val selected = episode.id == selectedEpisode?.id
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                    .background(if (selected) CinatorElevatedCard else CinatorCard, RoundedCornerShape(10.dp))
                                    .selectable(selected = selected, role = Role.RadioButton) {
                                        picker = selection.select(episode, details.episodes)
                                        notifyEpisode(episode)
                                    }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(episode.title.ifBlank { "Untitled episode" })
                                    val position = listOfNotNull(episode.season?.let { "Season $it" },
                                        episode.number?.let { "Episode $it" })
                                    if (position.isNotEmpty()) Text(position.joinToString(" · "), color = CinatorMutedText, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            selectedEpisode?.let { Text("Selected: ${it.title}", color = CinatorMutedText) }
                            Button(
                                onClick = onResolve,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = CinatorAccent)
                            ) { Text(if (error != null) "Retry: Resolve sources" else "Resolve sources") }
                            if (loading) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("Loading provider results…", color = CinatorMutedText)
                                }
                            }
                            if (error != null) Text(error.ifBlank { "Provider request failed. Please retry." },
                                color = CinatorErrorRed, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            if (!loading && error == null && streams.isEmpty()) {
                                Text("No playable sources loaded for this selection. Resolve sources to check availability.",
                                    color = CinatorMutedText)
                            }
                            if (streams.isNotEmpty()) Text("Available sources (${streams.size})", fontWeight = FontWeight.Bold)
                        }
                    }
                    items(streams) { stream ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            color = CinatorElevatedCard, shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stream.label.ifBlank { "Provider source" }, color = Color.White, fontWeight = FontWeight.Bold)
                                val protocol = if (stream.protocol == StreamProtocol.MP4) "MP4" else "HLS"
                                Text(listOfNotNull(protocol, stream.quality.takeIf { it.isNotBlank() },
                                    "LIVE".takeIf { stream.isLive }, stream.subtitleLang?.let { "Subtitles: $it" })
                                    .joinToString(" · "), color = CinatorMutedText, fontSize = 12.sp)
                                Button(
                                    onClick = { onPlay(stream) }, enabled = !loading,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = CinatorAccent)
                                ) { Text("Play") }
                                if (DownloadPolicy.canDownloadStream(stream)) {
                                    OutlinedButton(onClick = { onDownload(stream) }, enabled = !loading,
                                        modifier = Modifier.fillMaxWidth()) { Text("Download MP4") }
                                } else Text("Download unavailable for this source", color = CinatorMutedText, fontSize = 12.sp)
                            }
                        }
                    }
                    item { Box(Modifier.height(12.dp)) }
                }
            }
        }
    }
}
