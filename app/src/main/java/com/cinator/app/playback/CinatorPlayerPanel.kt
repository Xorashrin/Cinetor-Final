package com.cinator.app.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.cinator.app.data.UrlPolicyValidator
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

object PlaybackResumeStore {
    private const val PREFS_NAME = "cinator_playback_resume"

    fun getSavedPositionMs(context: Context, streamId: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(streamId, 0L).coerceAtLeast(0L)

    fun savePositionMs(context: Context, streamId: String, positionMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(streamId, positionMs.coerceAtLeast(0L)).apply()
    }
}

/** Fullscreen changes only the view target. Neither it nor a fallback builds another player. */
@OptIn(UnstableApi::class)
@Composable
fun CinatorPlayerPanel(
    title: String,
    stream: StreamSource,
    localOverridePath: String? = null,
    onClose: () -> Unit,
    fallbackSources: List<StreamSource> = emptyList()
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Freeze this session's alternatives: recomposition must not reset the attempt budget.
    val session = remember(context, stream, localOverridePath) {
        PlaybackSession(context, stream, localOverridePath, fallbackSources.toList())
    }
    var fullscreen by remember(session) { mutableStateOf(false) }

    DisposableEffect(session) {
        session.start(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { session.release() }
    }
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.onStart()
                Lifecycle.Event.ON_STOP -> session.onStop()
                Lifecycle.Event.ON_DESTROY -> session.release()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session) {
        while (isActive) {
            session.refresh()
            delay(500L)
        }
    }
    LaunchedEffect(session) {
        while (isActive) {
            delay(15_000L)
            session.checkpoint()
        }
    }
    BackHandler(enabled = !fullscreen) { onClose() }

    if (fullscreen) {
        // No inline PlayerView remains attached behind the dialog.
        Text("Playing fullscreen", color = Color.LightGray, modifier = Modifier.padding(12.dp))
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            FullscreenWindowEffect()
            BackHandler { fullscreen = false }
            PlayerChrome(title, session, true, { fullscreen = false }, onClose)
        }
    } else {
        PlayerChrome(title, session, false, { fullscreen = true }, onClose)
    }
}

@OptIn(UnstableApi::class)
private class PlaybackSession(
    private val context: Context,
    private val primary: StreamSource,
    private val localPath: String?,
    alternatives: List<StreamSource>
) {
    val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
    }
    private val intent = PlaybackIntent()
    private val handler = Handler(player.applicationLooper)
    private val fallback = PlaybackFallback(primary, if (localPath == null) alternatives else emptyList())
    private val resumeKey = playbackResumeKey(primary)
    private val downloadDirectory = File(context.filesDir, "downloads")
    private val httpClient = safePlaybackClient()
    private var targetView: PlayerView? = null
    private var started = false
    private var released = false
    private var handlingFailure = false
    private var attempted = 0
    private var bufferStartedMs = 0L
    private var lastSavedPosition: Long? = null
    private var pendingSwitchNotice: String? = null

    var currentSource by mutableStateOf(primary)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var terminalFailure by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var seekable by mutableStateOf(false)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var trackParameters by mutableStateOf(player.trackSelectionParameters)
        private set
    val offline: Boolean get() = localPath != null
    val live: Boolean get() = !offline && (currentSource.isLive || player.isCurrentMediaItemLive)

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            intent.observePlayWhenReady(playWhenReady)
            this@PlaybackSession.playWhenReady = playWhenReady
            if (!playWhenReady) checkpoint()
        }

        override fun onEvents(player: Player, events: Player.Events) { refresh() }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                pendingSwitchNotice?.let { message = "$it Switched to another source for this title." }
                pendingSwitchNotice = null
            }
            if (playbackState == Player.STATE_ENDED) checkpoint()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Do not expose exception URLs, authorization headers or signed query strings.
            failCurrent("Source failed (${error.errorCodeName}).")
        }
    }

    fun start(inForeground: Boolean) {
        if (started || released) return
        started = true
        if (!inForeground) intent.onStop()
        player.addListener(listener)
        val resume = resumeKey?.let { PlaybackResumeStore.getSavedPositionMs(context, it) } ?: 0L
        if (offline) {
            val file = localPath?.let { localPlaybackFile(downloadDirectory, it) }
            if (file == null) {
                terminalFailure = true
                message = "Offline file is missing or outside this app's downloads. No network fallback was attempted."
            } else {
                prepareSource(primary, resume, file)
            }
        } else {
            if (!UrlPolicyValidator.isSafeHttpsUrl(primary.url) || !safePlaybackHeaders(primary.headers)) {
                message = "Selected source rejected by playback safety policy. Only same-title alternatives are allowed."
            }
            nextSource(resume)
        }
    }

    fun onStop() {
        if (released) return
        // Mark stopped BEFORE pause so its listener does not erase the user's intent.
        intent.onStop()
        checkpoint()
        player.pause()
        bufferStartedMs = 0L
    }

    fun onStart() {
        if (released) return
        intent.onStart()
        if (!terminalFailure) player.playWhenReady = intent.shouldPlay
    }

    fun togglePlayback() {
        if (released || terminalFailure) return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition()
            intent.observePlayWhenReady(true)
        } else {
            intent.toggle(player.playWhenReady) // isPlaying is false while buffering, even when playing is requested.
        }
        player.playWhenReady = intent.shouldPlay
        refresh()
    }

    fun seek(position: Long) {
        if (released || terminalFailure || !player.isCurrentMediaItemSeekable || player.duration <= 0L) return
        player.seekTo(position.coerceIn(0L, player.duration))
        refresh()
    }

    fun cycleSpeed() {
        if (released || terminalFailure || live) return
        val next = when (player.playbackParameters.speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player.playbackParameters = PlaybackParameters(next)
        refresh()
    }

    fun selectTrack(type: Int, group: Tracks.Group? = null, index: Int = 0, off: Boolean = false) {
        if (released || !player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type).setTrackTypeDisabled(type, off)
        if (group != null) {
            val current = player.currentTracks.groups.firstOrNull { it.mediaTrackGroup == group.mediaTrackGroup }
                ?: return
            if (index !in 0 until current.length || !current.isTrackSupported(index)) return
            builder.setOverrideForType(TrackSelectionOverride(current.mediaTrackGroup, listOf(index)))
        }
        player.trackSelectionParameters = builder.build()
        refresh()
    }

    fun attach(view: PlayerView) {
        if (released || view === targetView) return
        PlayerView.switchTargetView(player, targetView, view)
        targetView = view
    }

    fun detach(view: PlayerView) {
        if (view === targetView) {
            PlayerView.switchTargetView(player, view, null)
            targetView = null
        } else {
            view.player = null
        }
    }

    fun refresh() {
        if (released) return
        positionMs = player.currentPosition.coerceAtLeast(0L)
        durationMs = player.duration.takeIf { it > 0L } ?: 0L
        playWhenReady = player.playWhenReady
        playbackState = player.playbackState
        speed = player.playbackParameters.speed
        seekable = player.isCurrentMediaItemSeekable && durationMs > 0L
        tracks = player.currentTracks
        trackParameters = player.trackSelectionParameters
        targetView?.keepScreenOn = player.isPlaying
        if (playbackState == Player.STATE_BUFFERING && playWhenReady && !terminalFailure) {
            val now = SystemClock.elapsedRealtime()
            if (bufferStartedMs == 0L) bufferStartedMs = now
            if (now - bufferStartedMs >= 30_000L) failCurrent("Source stalled for 30 seconds.")
        } else {
            bufferStartedMs = 0L
        }
    }

    fun checkpoint() {
        if (released || live || player.currentMediaItem == null) return
        val key = resumeKey ?: return
        // Do not overwrite a good checkpoint with an unprepared/failed source's reset position.
        if (player.playbackState == Player.STATE_IDLE) return
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0L)
        if (position == lastSavedPosition) return
        PlaybackResumeStore.savePositionMs(context, key, position)
        lastSavedPosition = position
    }

    fun release() {
        if (released) return
        checkpoint()
        targetView?.let { detach(it) }
        released = true
        handler.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        player.release()
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun nextSource(resume: Long) {
        val next = fallback.next()
        if (next == null) {
            terminalFailure = true
            intent.observePlayWhenReady(false)
            player.pause()
            message = (message?.plus(" ") ?: "") +
                "No playable source remains. Eligible same-title sources are exhausted or unavailable; unrelated content was not substituted."
            return
        }
        attempted++
        if (next.url != primary.url) {
            val notice = pendingSwitchNotice ?: message ?: "Selected source was unavailable."
            pendingSwitchNotice = notice
            message = "$notice Trying same-title source $attempted (maximum ${PlaybackFallback.MAX_ATTEMPTS})."
        }
        prepareSource(next, resume)
    }

    private fun prepareSource(source: StreamSource, resume: Long, localFile: File? = null) {
        currentSource = source
        tracks = Tracks.EMPTY
        bufferStartedMs = 0L
        try {
            val factory = if (localFile != null) {
                DataSource.Factory { CheckedDataSource(FileDataSource(), downloadDirectory) }
            } else {
                val client = httpClient.newBuilder().addInterceptor(SafeStreamRequests(source.url, source.headers)).build()
                val network = OkHttpDataSource.Factory(client).setUserAgent("Cinator/0.3")
                DataSource.Factory { CheckedDataSource(network.createDataSource()) }
            }
            val item = MediaItem.Builder()
                .setMediaId(resumeKey ?: "unidentified-session")
                .setUri(localFile?.let(Uri::fromFile) ?: Uri.parse(source.url))
                .setMimeType(if (localFile == null && source.protocol == StreamProtocol.HLS_M3U8)
                    MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
                .build()
            val mediaSource = DefaultMediaSourceFactory(factory)
                .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(1) {
                    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                        if (info.errorCount > 1) C.TIME_UNSET else super.getRetryDelayMsFor(info)
                }).createMediaSource(item)
            // Group overrides belong to a manifest, not to the fallback manifest. Keep text Off/preferences.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
            player.setMediaSource(mediaSource, if (source.isLive && localFile == null) C.TIME_UNSET else resume.coerceAtLeast(0L))
            player.playWhenReady = intent.shouldPlay
            player.prepare()
            refresh()
        } catch (_: Exception) {
            failCurrent("Source could not be prepared.")
        }
    }

    private fun failCurrent(reason: String) {
        if (released || handlingFailure || terminalFailure) return
        handlingFailure = true
        val resume = player.currentPosition.coerceAtLeast(0L)
        val notice = "${currentSource.label.take(80)}: $reason"
        message = notice
        pendingSwitchNotice = notice
        checkpoint()
        player.stop()
        // Do not reenter ExoPlayer from an error/state listener; allow its event batch to finish.
        handler.post {
            if (!released) {
                handlingFailure = false
                if (offline) {
                    terminalFailure = true
                    player.pause()
                    message = "$notice Offline playback failed; no network fallback was attempted."
                } else {
                    nextSource(resume)
                }
            }
        }
    }
}

/** Uses the existing Media3 transport adapter with explicit, bounded redirects and public DNS. */
private fun safePlaybackClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .proxy(Proxy.NO_PROXY)
    .dns(object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublicPlaybackAddress(it) }) {
                throw UnknownHostException("Playback target is not a public address")
            }
            return addresses
        }
    }).build()

private class SafeStreamRequests(sourceUrl: String, private val headers: Map<String, String>) : Interceptor {
    private val sourceHost = URI(sourceUrl).host

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        for (hop in 0..4) {
            if (!UrlPolicyValidator.isSafeHttpsUrl(request.url.toString())) throw IOException("Unsafe playback request")
            val builder = request.newBuilder()
            // No provider credentials leak to a cross-origin HLS segment, key, or redirect.
            (headers.keys + listOf("Authorization", "Cookie", "Proxy-Authorization")).forEach(builder::removeHeader)
            if (request.url.host.equals(sourceHost, ignoreCase = true)) {
                headers.forEach { (name, value) -> builder.header(name, value) }
            }
            val response = chain.proceed(builder.build())
            if (response.code !in setOf(301, 302, 303, 307, 308)) {
                if (!response.isSuccessful) {
                    // Media3 otherwise reads an unbounded error body. Keep status/range metadata only.
                    response.close()
                    return response.newBuilder().body(ByteArray(0).toResponseBody(null)).build()
                }
                return response
            }
            val location = response.header("Location")
            response.close()
            if (hop == 4) throw IOException("Playback redirect limit exceeded")
            val target = location?.let { playbackRedirectUrl(request.url.toString(), it) }
                ?: throw IOException("Unsafe playback redirect")
            request = request.newBuilder().url(target).build()
        }
        throw IOException("Playback redirect limit exceeded")
    }
}

@OptIn(UnstableApi::class)
private class CheckedDataSource(
    private val delegate: DataSource,
    private val downloads: File? = null
) : DataSource by delegate {
    override fun open(dataSpec: DataSpec): Long {
        val allowed = if (downloads == null) {
            UrlPolicyValidator.isSafeHttpsUrl(dataSpec.uri.toString())
        } else {
            dataSpec.uri.scheme == "file" && dataSpec.uri.authority.isNullOrEmpty() &&
                dataSpec.uri.path?.let { localPlaybackFile(downloads, it) } != null
        }
        if (!allowed) throw IOException("Playback URI rejected by safety policy")
        return delegate.open(dataSpec)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerChrome(
    title: String,
    session: PlaybackSession,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    var tracksOpen by remember(session) { mutableStateOf(false) }
    var controlsVisible by remember(session, fullscreen) { mutableStateOf(true) }
    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerSurface(session, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "Show or hide playback controls"
            ) { controlsVisible = !controlsVisible })
            if (controlsVisible || session.terminalFailure) {
                PlayerHeader(title, session, true, onToggleFullscreen, onClose,
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding())
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .heightIn(max = 220.dp).background(Color.Black.copy(alpha = 0.8f))
                    .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    PlayerControls(session) { tracksOpen = true }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF141418))) {
            PlayerHeader(title, session, false, onToggleFullscreen, onClose)
            PlayerSurface(session, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(horizontal = 12.dp)) { PlayerControls(session) { tracksOpen = true } }
        }
    }
    if (tracksOpen) TrackPicker(session) { tracksOpen = false }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(session: PlaybackSession, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false // One set of controls, one seek-on-release implementation.
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setKeepContentOnPlayerReset(false)
            }
        },
        modifier = modifier.background(Color.Black),
        onRelease = { session.detach(it) },
        update = { view -> session.attach(view) }
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerHeader(
    title: String,
    session: PlaybackSession,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.8f)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(session.currentSource.label + if (session.offline) " • Offline file" else "",
                color = Color.LightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onToggleFullscreen) {
            Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                if (fullscreen) "Exit fullscreen" else "Fullscreen", tint = Color.White)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close player", tint = Color.White) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerControls(session: PlaybackSession, onTracks: () -> Unit) {
    var dragPosition by remember(session, session.currentSource) { mutableStateOf<Long?>(null) }
    session.message?.let {
        Text(it, color = if (session.terminalFailure) Color(0xFFF87171) else Color(0xFFFBBF24),
            fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (session.seekable && !session.terminalFailure) {
        Slider(
            value = (dragPosition ?: session.positionMs).toFloat().coerceIn(0f, session.durationMs.toFloat()),
            onValueChange = { value -> dragPosition = value.toLong() },
            onValueChangeFinished = {
                dragPosition?.let(session::seek)
                dragPosition = null
            },
            valueRange = 0f..session.durationMs.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatMs(dragPosition ?: session.positionMs), color = Color.LightGray, fontSize = 12.sp)
        Text(when {
            session.terminalFailure -> "Unavailable"
            session.playbackState == Player.STATE_BUFFERING -> if (session.playWhenReady) "Buffering" else "Paused"
            session.playbackState == Player.STATE_ENDED -> "Ended"
            session.playWhenReady -> "Playing"
            else -> "Paused"
        }, color = Color.LightGray, fontSize = 12.sp)
        Text(if (session.live) "LIVE" else if (session.durationMs > 0) formatMs(session.durationMs) else "--:--",
            color = Color.LightGray, fontSize = 12.sp)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { session.seek(session.positionMs - 10_000L) }, enabled = session.seekable && !session.terminalFailure) {
            Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.LightGray)
        }
        val canPause = session.playWhenReady && session.playbackState != Player.STATE_ENDED
        IconButton(onClick = session::togglePlayback, enabled = !session.terminalFailure) {
            Icon(if (canPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (canPause) "Pause" else "Play", tint = Color.White)
        }
        IconButton(onClick = { session.seek(session.positionMs + 10_000L) }, enabled = session.seekable && !session.terminalFailure) {
            Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.LightGray)
        }
        TextButton(onClick = session::cycleSpeed, enabled = !session.live && !session.terminalFailure) {
            Text("${session.speed}×", color = Color.LightGray)
        }
        TextButton(onClick = onTracks) { Text("Tracks", color = Color.LightGray) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TrackPicker(session: PlaybackSession, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback tracks") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                listOf(C.TRACK_TYPE_AUDIO to "Audio", C.TRACK_TYPE_TEXT to "Subtitles", C.TRACK_TYPE_VIDEO to "Video").forEach { (type, label) ->
                    Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    val groups = session.tracks.groups.filter { it.type == type }
                    val disabled = type in session.trackParameters.disabledTrackTypes
                    if (type == C.TRACK_TYPE_TEXT) {
                        Row(Modifier.fillMaxWidth().clickable { session.selectTrack(type, off = true) },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = disabled, onClick = { session.selectTrack(type, off = true) })
                            Text("Off")
                        }
                    }
                    if (groups.isEmpty()) {
                        Text("No ${label.lowercase(Locale.ROOT)} tracks detected", fontSize = 12.sp)
                    } else {
                        val automatic = !disabled && session.trackParameters.overrides.keys.none { it.type == type }
                        Row(Modifier.fillMaxWidth().clickable { session.selectTrack(type) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(automatic, onClick = { session.selectTrack(type) })
                            Text("Auto")
                        }
                        groups.forEachIndexed { groupIndex, group ->
                            for (index in 0 until group.length) {
                                val supported = group.isTrackSupported(index)
                                val selected = !disabled && group.isTrackSelected(index)
                                val select = { session.selectTrack(type, group, index) }
                                Row(Modifier.fillMaxWidth().clickable(enabled = supported, onClick = select),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected, onClick = select, enabled = supported)
                                    Text(trackLabel(group.getTrackFormat(index), type, groupIndex + 1, index + 1) +
                                        if (supported) "" else " (unsupported)", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@OptIn(UnstableApi::class)
private fun trackLabel(format: Format, type: Int, group: Int, index: Int): String {
    val details = buildList {
        format.label?.takeIf { it.isNotBlank() }?.let { add(it) }
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { add(Locale.forLanguageTag(it).displayName) }
        if (type == C.TRACK_TYPE_VIDEO && format.height > 0) add("${format.height}p")
        if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) add("${format.channelCount} channels")
        if (format.bitrate > 0) add("${format.bitrate / 1000} kbps")
        format.sampleMimeType?.let { add(it.substringAfter('/')) }
    }
    return "Track $group.$index" + if (details.isEmpty()) "" else " • ${details.joinToString(" • ")}"
}

@Composable
private fun FullscreenWindowEffect() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(activity, window) {
        val previousOrientation = activity?.requestedOrientation
        val decor = window?.decorView
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        val insets = decor?.let { ViewCompat.getRootWindowInsets(it) }
        val statusWasVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationWasVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        // Activity declares orientation configChanges; this does not recreate the playback session.
        runCatching { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (previousOrientation != null) runCatching { activity?.requestedOrientation = previousOrientation }
            if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
            if (statusWasVisible) controller?.show(WindowInsetsCompat.Type.statusBars())
            else controller?.hide(WindowInsetsCompat.Type.statusBars())
            if (navigationWasVisible) controller?.show(WindowInsetsCompat.Type.navigationBars())
            else controller?.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}

private fun formatMs(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
}
