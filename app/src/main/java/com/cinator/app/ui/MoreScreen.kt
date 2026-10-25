package com.cinator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinator.app.domain.BdixHostStatus
import com.cinator.app.domain.DownloadEntry
import com.cinator.app.domain.DownloadStatus
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.data.EmbeddedCatalog
import com.cinator.app.domain.StreamSource
import com.cinator.app.ui.theme.CinatorAccent
import com.cinator.app.ui.theme.CinatorBorder
import com.cinator.app.ui.theme.CinatorCard
import com.cinator.app.ui.theme.CinatorElevatedCard
import com.cinator.app.ui.theme.CinatorMetadataBlue
import com.cinator.app.ui.theme.CinatorMutedText
import com.cinator.app.ui.theme.CinatorPlayableGreen
import com.cinator.app.ui.theme.CinatorWarningYellow

@Composable
fun MoreHubScreen(
    liveTvState: FeedUiState,
    adultState: FeedUiState,
    adultEnabled: Boolean,
    downloads: List<DownloadEntry>,
    bdixResults: List<BdixHostStatus>,
    bdixProbing: Boolean,
    catalog: EmbeddedCatalog,
    sectionStates: List<FeedUiState>,
    references: ReferenceSnapshot?,
    referencesError: String?,
    onLiveTvQueryChange: (String) -> Unit,
    onSyncLiveTv: () -> Unit,
    onSelectCard: (MediaCard) -> Unit,
    onPlayStream: (title: String, stream: StreamSource, localPath: String?) -> Unit,
    onToggleAdultOptIn: (Boolean) -> Unit,
    onRunBdixProbe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 1. Live TV Section (Strictly in More tab)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinatorCard)
                .border(1.dp, CinatorBorder, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LiveTv, contentDescription = null, tint = CinatorAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live TV Channels", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = "Public M3U & HLS Streams • Download is disabled on live feeds",
                        color = CinatorMutedText,
                        fontSize = 11.sp
                    )
                }
                OutlinedButton(
                    onClick = onSyncLiveTv,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (liveTvState.isLoading) "Syncing..." else "Sync", fontSize = 11.sp)
                }
            }

            SectionStatusBar(
                isCached = liveTvState.isCached,
                sourceCount = liveTvState.sourceCount,
                failures = liveTvState.providerFailures,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            SectionSearchBar(liveTvState.searchQuery, "Search all fetched channels", onLiveTvQueryChange)
            liveTvState.errorMessage?.let { Text(it, color = CinatorWarningYellow, fontSize = 12.sp) }
            if (!liveTvState.isLoading && liveTvState.activeDisplayItems.isEmpty()) {
                Text("Live TV unavailable: no matching channels from embedded adapters.", color = CinatorMutedText)
            }
            MediaRail(
                title = "All matching channels • swipe to browse",
                items = liveTvState.activeDisplayItems,
                onSelectCard = onSelectCard
            )
        }

        Spacer(Modifier.height(14.dp))

        // 2. Downloads Manager
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinatorCard)
                .border(1.dp, CinatorBorder, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, contentDescription = null, tint = CinatorPlayableGreen, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Offline Downloads", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = "Keep the app open while saving. Background completion is not guaranteed. Downloads shown for this session.",
                        color = CinatorMutedText,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (downloads.isEmpty()) {
                Text(
                    text = "No downloads queued or saved yet. Playable MP4 entries can be saved from card details.",
                    color = CinatorMutedText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                downloads.forEach { entry ->
                    DownloadRowItem(entry = entry, onPlayOffline = { title, stream, path ->
                        onPlayStream(title, stream, path)
                    })
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. System Status & Source Inventory (Honest metadata, no bypasses)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinatorCard)
                .border(1.dp, CinatorBorder, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Text("Sources & System Transparency", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                text = "Built-in static descriptors. Reference items are metadata guides only; no scraping engines or code injection.",
                color = CinatorMutedText,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            catalog.sources.forEach { source ->
                val state = sectionStates.firstOrNull { it.section == source.section }
                val issue = state?.providerFailures?.firstOrNull { it.startsWith(source.id + " ") || it.startsWith(source.id + ":") }
                val status = when {
                    !source.enabled -> "Disabled in bundle"
                    source.section == FeedSection.ADULT && !adultEnabled -> "Opt-in required"
                    state?.isLoading == true -> "Loading / searching"
                    issue != null -> "Provider issue: $issue"
                    state?.isCached == true -> "Saved snapshot only; not reverified"
                    state?.activeDisplayItems?.any { it.providerId == source.id } == true -> "Catalog entries loaded; playback not verified"
                    state?.errorMessage != null -> state.errorMessage
                    else -> "Enabled; no current entries / not verified"
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(source.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${source.section.displayName} • ${source.engine} • $status", color = CinatorMetadataBlue, fontSize = 11.sp)
                    Text(source.capabilityNote, color = CinatorMutedText, fontSize = 11.sp)
                    source.endpoints.forEach { Text(it, color = CinatorMutedText, fontSize = 10.sp) }
                }
            }
            if (catalog.sources.isEmpty()) Text("Source bundle unavailable.", color = CinatorWarningYellow)
        }

        Spacer(Modifier.height(14.dp))
        ReferenceInspector(references, referencesError)

        Spacer(Modifier.height(14.dp))

        // 4. BDIX Host Connectivity Check
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinatorCard)
                .border(1.dp, CinatorBorder, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("BDIX Host Connectivity", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = "Declared local ISP mirror check. Validates HTTPS reachability safely.",
                        color = CinatorMutedText,
                        fontSize = 11.sp
                    )
                }
                OutlinedButton(
                    onClick = onRunBdixProbe,
                    enabled = !bdixProbing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(if (bdixProbing) "Checking..." else "Probe", fontSize = 11.sp)
                }
            }
            if (bdixResults.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                bdixResults.forEach { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(r.hostName, color = Color.White, fontSize = 12.sp)
                        Text(
                            text = if (r.reachable) "Reachable (${r.latencyMs}ms)" else r.note,
                            color = if (r.reachable) CinatorPlayableGreen else CinatorWarningYellow,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 5. Adult Content (18+ Isolated Store, Disabled by default)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinatorCard)
                .border(1.dp, CinatorBorder, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Adult Content (18+ Isolated Store)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = "Disabled by default. Never contaminates Home, Anime, or K-Drama. Disabling wipes the section cache.",
                        color = CinatorMutedText,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = adultEnabled,
                    onCheckedChange = onToggleAdultOptIn,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = CinatorAccent
                    )
                )
            }
            adultState.errorMessage?.let { Text(it, color = CinatorWarningYellow, fontSize = 12.sp) }
            if (adultEnabled) {
                Spacer(Modifier.height(10.dp))
                SectionStatusBar(
                    isCached = adultState.isCached,
                    sourceCount = adultState.sourceCount,
                    failures = adultState.providerFailures
                )
                if (adultState.items.isNotEmpty()) {
                    MediaRail(
                        title = "Adult Catalog Entries (Opt-In)",
                        items = adultState.items,
                        onSelectCard = onSelectCard
                    )
                } else {
                    Text(
                        text = if (catalog.sources.none { it.section == FeedSection.ADULT && it.enabled })
                            "Adult catalog unavailable. This bundle supplies no active adult adapter; opt-in does not install providers."
                        else "Adult catalog unavailable: no entries returned by the embedded adapters.",
                        color = CinatorMutedText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRowItem(
    entry: DownloadEntry,
    onPlayOffline: (title: String, stream: StreamSource, localPath: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CinatorElevatedCard)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${entry.section.displayName} • ${entry.status.name}" +
                        (if (entry.totalBytes > 0L) " (${entry.bytesDownloaded / 1024 / 1024}MB / ${entry.totalBytes / 1024 / 1024}MB)" else ""),
                    color = CinatorMutedText,
                    fontSize = 11.sp
                )
            }
            if (entry.status == DownloadStatus.COMPLETED) {
                Button(
                    onClick = {
                        val stream = StreamSource(
                            id = "offline_${entry.id}",
                            label = "Offline MP4",
                            url = entry.localFilePath,
                            protocol = com.cinator.app.domain.StreamProtocol.MP4,
                            quality = "Local Storage",
                            isDownloadable = false,
                            providerId = "local-file",
                            contentId = entry.mediaId,
                            section = entry.section
                        )
                        onPlayOffline(entry.title, stream, entry.localFilePath)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CinatorPlayableGreen)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Play Offline", fontSize = 11.sp)
                }
            }
        }
        if (entry.status == DownloadStatus.DOWNLOADING && entry.totalBytes > 0L) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (entry.bytesDownloaded.toFloat() / entry.totalBytes.toFloat()).coerceIn(0f, 1f) },
                color = CinatorAccent,
                trackColor = Color(0xFF27272A),
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
        }
        entry.errorMessage?.let { err ->
            Spacer(Modifier.height(4.dp))
            Text(text = err, color = CinatorWarningYellow, fontSize = 11.sp)
        }
    }
}
