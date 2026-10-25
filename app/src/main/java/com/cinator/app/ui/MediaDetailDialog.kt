package com.cinator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import com.cinator.app.ui.theme.CinatorAccent
import com.cinator.app.ui.theme.CinatorBorder
import com.cinator.app.ui.theme.CinatorCard
import com.cinator.app.ui.theme.CinatorElevatedCard
import com.cinator.app.ui.theme.CinatorMetadataBlue
import com.cinator.app.ui.theme.CinatorMutedText
import com.cinator.app.ui.theme.CinatorPlayableGreen
import com.cinator.app.ui.theme.CinatorWarningYellow

/**
 * Modal scrollable dialog presenting media metadata, stream sources, and download options.
 * Presenting details modally prevents stacking giant player + detail sheet + feed beyond screen height.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaDetailDialog(
    card: MediaCard,
    onPlayStream: (StreamSource) -> Unit,
    onDownloadStream: (StreamSource) -> Unit,
    onOpenExternal: (String) -> Unit,
    onClose: () -> Unit,
    downloadBlocked: Boolean = false
) {
    val backdrop = card.backdropUrl ?: card.posterUrl

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = CinatorCard,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header Image with dismiss button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(CinatorElevatedCard)
                ) {
                    if (!backdrop.isNullOrBlank()) {
                        AsyncImage(
                imageLoader = rememberSafeImageLoader(androidx.compose.ui.platform.LocalContext.current),
                            model = backdrop,
                            contentDescription = card.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x44000000),
                                        Color(0xAA18181B),
                                        CinatorCard
                                    )
                                )
                            )
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x88000000))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CinatorAccent)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = card.section.displayName.uppercase(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = card.providerName,
                                color = CinatorMutedText,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = card.title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Meta info row
                Column(Modifier.padding(16.dp)) {
                    if (card.subtitle.isNotBlank()) {
                        Text(text = card.subtitle, color = Color.LightGray, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        card.year?.let { y ->
                            Text(text = y, color = Color.Gray, fontSize = 12.sp)
                        }
                        card.rating?.let { r ->
                            Text(text = "★ $r", color = CinatorWarningYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (card.genres.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            card.genres.forEach { genre ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CinatorElevatedCard)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(text = genre, color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    if (card.synopsis.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = card.synopsis,
                            color = CinatorMutedText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // Stream Sources Section
                    if (card.streams.isNotEmpty()) {
                        Text(
                            text = "Available Streams (${card.streams.size})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(8.dp))

                        card.streams.forEach { stream ->
                            val canDownload = !stream.isLive && com.cinator.app.downloads.CinatorDownloadManager.canDownloadStream(stream)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = CinatorElevatedCard),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = stream.label,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(
                                                        if (stream.isLive) CinatorAccent else CinatorPlayableGreen
                                                    )
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = if (stream.isLive) "LIVE" else stream.quality,
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Protocol: ${stream.protocol.name}" +
                                                (stream.subtitleLang?.let { " • Subs: $it" } ?: ""),
                                            color = CinatorMutedText,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            onClose()
                                            onPlayStream(stream)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CinatorAccent),
                                        modifier = Modifier.padding(end = 4.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Play", fontSize = 11.sp)
                                    }

                                    if (canDownload) {
                                        OutlinedButton(
                                            onClick = { onDownloadStream(stream) },
                                            enabled = !downloadBlocked,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(2.dp))
                                            Text(if (downloadBlocked) "Queued / saved" else "Save", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CinatorElevatedCard)
                                .border(1.dp, CinatorBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Metadata Catalog Entry • In-app stream not attached to this entry.",
                                color = CinatorMetadataBlue,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // External Link (if present)
                    card.externalUrl?.let { url ->
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onOpenExternal(url) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open External Reference", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
