package com.cinator.app.playback

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cinator.app.domain.StreamSource
import kotlinx.coroutines.delay

object PlaybackResumeStore {
    private const val PREFS_NAME = "cinator_playback_resume"

    fun getSavedPositionMs(context: Context, streamId: String): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(streamId, 0L)
    }

    fun savePositionMs(context: Context, streamId: String, positionMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(streamId, positionMs.coerceAtLeast(0L)).apply()
    }
}

@Composable
fun CinatorPlayerPanel(
    title: String,
    stream: StreamSource,
    localOverridePath: String? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val effectiveUri = localOverridePath ?: stream.url
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var playerError by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(effectiveUri) {
        ExoPlayer.Builder(context).build().apply {
            val savedPos = PlaybackResumeStore.getSavedPositionMs(context, stream.id)
            setMediaItem(MediaItem.fromUri(effectiveUri))
            prepare()
            if (savedPos > 1000L) {
                seekTo(savedPos)
            }
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = "Playback error (${error.errorCodeName}): ${error.localizedMessage ?: "stream unavailable"}"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            PlaybackResumeStore.savePositionMs(context, stream.id, exoPlayer.currentPosition)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            PlaybackResumeStore.savePositionMs(context, stream.id, positionMs)
            delay(500L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF141418))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "${stream.label} • ${stream.quality}" +
                        (if (localOverridePath != null) " • OFFLINE FILE" else ""),
                    color = Color(0xFFA1A1AA),
                    fontSize = 12.sp
                )
            }
            OutlinedButton(onClick = onClose) {
                Text("Close", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        )

        if (playerError != null) {
            Text(
                text = playerError!!,
                color = Color(0xFFF87171),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (durationMs > 0L) {
            Slider(
                value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                onValueChange = { newPos ->
                    exoPlayer.seekTo(newPos.toLong())
                    positionMs = newPos.toLong()
                },
                valueRange = 0f..durationMs.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(formatMs(positionMs), color = Color.LightGray, fontSize = 12.sp)
            Text(
                text = stream.subtitleLang?.let { "Subtitles: $it" } ?: "Audio: Default",
                color = Color(0xFFA1A1AA),
                fontSize = 12.sp
            )
            Text(if (durationMs > 0L) formatMs(durationMs) else "LIVE", color = Color.LightGray, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                    exoPlayer.seekTo(target)
                    positionMs = target
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("-10s", color = Color.White, fontSize = 12.sp)
            }
            Button(
                onClick = {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isPlaying) "Pause" else "Play", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    val maxPos = if (durationMs > 0L) durationMs else Long.MAX_VALUE
                    val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxPos)
                    exoPlayer.seekTo(target)
                    positionMs = target
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("+10s", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    val nextSpeed = when (playbackSpeed) {
                        1.0f -> 1.25f
                        1.25f -> 1.5f
                        1.5f -> 2.0f
                        else -> 1.0f
                    }
                    playbackSpeed = nextSpeed
                    exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("${playbackSpeed}x", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%02d:%02d".format(min, sec)
}
