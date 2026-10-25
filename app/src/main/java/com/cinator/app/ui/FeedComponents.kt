package com.cinator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinator.app.domain.MediaCard
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
fun HeroBanner(
    item: MediaCard,
    onPlay: (StreamSource) -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = item.backdropUrl ?: item.posterUrl
    val primaryStream = item.streams.firstOrNull()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CinatorElevatedCard)
            .clickable { onDetails() }
    ) {
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                imageLoader = rememberSafeImageLoader(androidx.compose.ui.platform.LocalContext.current),
                model = backdrop,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Netflix-style gradient shadow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x9909090B),
                            Color(0xF509090B)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "FEATURED • ${item.section.displayName.uppercase()}",
                    color = CinatorAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.providerName,
                    color = CinatorMutedText,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.synopsis.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.synopsis,
                    color = CinatorMutedText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (primaryStream != null) {
                    Button(
                        onClick = { onPlay(primaryStream) },
                        colors = ButtonDefaults.buttonColors(containerColor = CinatorAccent)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (primaryStream.isLive) "Watch Live" else "Play Now",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                OutlinedButton(
                    onClick = onDetails,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Details & Sources", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun MediaCardItem(
    item: MediaCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val poster = item.posterUrl ?: item.backdropUrl
    val hasStreams = item.streams.isNotEmpty()
    val isLive = item.streams.any { it.isLive }

    Column(
        modifier = modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CinatorCard)
            .border(1.dp, CinatorBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CinatorElevatedCard)
        ) {
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                imageLoader = rememberSafeImageLoader(androidx.compose.ui.platform.LocalContext.current),
                    model = poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF27272A), Color(0xFF18181B))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.title.take(1),
                        color = CinatorMutedText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Status tag on top-left
            val badgeColor = when {
                isLive -> CinatorAccent
                hasStreams -> CinatorPlayableGreen
                else -> CinatorMetadataBlue
            }
            val badgeText = when {
                isLive -> "LIVE"
                hasStreams -> "STREAM"
                else -> "METADATA"
            }

            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor.copy(alpha = 0.9f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badgeText,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = item.providerName,
            color = CinatorMutedText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!item.year.isNullOrBlank() || !item.rating.isNullOrBlank()) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item.year?.let { y ->
                    Text(text = y, color = Color.Gray, fontSize = 10.sp)
                }
                item.rating?.let { r ->
                    Text(text = "★ $r", color = CinatorWarningYellow, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun MediaRail(
    title: String,
    items: List<MediaCard>,
    onSelectCard: (MediaCard) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "(${items.size})",
                color = CinatorMutedText,
                fontSize = 12.sp
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            items(items, key = { "${it.providerId}:${it.id}" }) { card ->
                MediaCardItem(item = card, onClick = { onSelectCard(card) })
            }
        }
    }
}

@Composable
fun SectionStatusBar(
    isCached: Boolean,
    sourceCount: Int,
    failures: List<String>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isCached) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF27272A))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Cached snapshot", color = Color.LightGray, fontSize = 11.sp)
                }
            }
            if (sourceCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("$sourceCount configured", color = CinatorMetadataBlue, fontSize = 11.sp)
                }
            }
            if (failures.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF451A03))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${failures.size} failed",
                        color = CinatorWarningYellow,
                        fontSize = 11.sp
                    )
                }
            }
        }
        if (failures.isNotEmpty()) {
            Text(
                text = "Provider issues: ${failures.take(2).joinToString("; ")}",
                color = CinatorWarningYellow,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun SectionSearchBar(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CinatorMutedText) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = CinatorMutedText)
                }
            }
        },
        placeholder = { Text(placeholder, color = CinatorMutedText, fontSize = 13.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CinatorCard,
            unfocusedContainerColor = CinatorCard,
            focusedBorderColor = CinatorAccent,
            unfocusedBorderColor = CinatorBorder,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        modifier = modifier.fillMaxWidth()
    )
}
