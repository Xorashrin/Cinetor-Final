package com.cinator.app

import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cinator.app.data.BoundedHttpClient
import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.DownloadStatus
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import com.cinator.app.downloads.CinatorDownloadManager
import com.cinator.app.engines.nuvio.runtime.IsolatedJsResult
import com.cinator.app.providers.AndroidBundledPlaybackProviders
import com.cinator.app.providers.ProviderResolver
import com.cinator.app.providers.ResolveRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Live, opt-in-by-test-selection integration test; unavailable Internet is a FAILURE, not a skip.
 *
 * Uses the real Android bundled provider, isolated native resolver and downloader with the target
 * application's Context. Native candidate identity must match the source used throughout playback.
 * A test-owned PlayerView is attached above MainActivity. It proves Media3 playback and API-level
 * controls, NOT navigation through Compose cards, the app's player chrome, fullscreen, or fallback.
 * Evidence is written incrementally to JSON files under targetContext.filesDir/provider-e2e, including on
 * failure. No playback metrics are inferred from HTTP success, timers, fixtures or ffprobe output.
 *
 * One audited 110,916,740-byte full film is downloaded. Streaming is separately capped at 32 MiB;
 * playback buffers are kept small. The whole test has a 20-minute deadline. The offline player has
 * only a FileDataSource, with an exact-file guard; it cannot fall back to an HTTP transport. This
 * is player-level isolation, not a claim that the device or the unrelated Activity is offline.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ProviderPlaybackE2ETest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 1_200_000L)
    fun reviewedFullFilm_providerPlaybackControlsDownloadAndOffline() {
        val evidence = Evidence(File(context.filesDir, "provider-e2e"))
        var scenario: ActivityScenario<MainActivity>? = null
        var session: PlayerSession? = null
        try {
            evidence.stage("android_lossless_numeric_admission") { rejectsRoundedApiNumbersBeforeNativeAdmission() }
            val http = ObservedHttp(evidence)
            val nativeResults = CopyOnWriteArrayList<IsolatedJsResult>()
            val (manifest, providers) = evidence.stage("bundled_manifest") {
                val raw = context.assets.open("open_films.json").bufferedReader().use { it.readText() }
                val root = JSONObject(raw)
                val films = root.getJSONArray("films")
                val pinned = (0 until films.length()).map { films.getJSONObject(it) }
                    .single { it.getString("id") == FILM_ID }
                check(pinned.getString("uuid") == UUID) { "Primary audited film identity changed" }
                check(pinned.getString("title") == "Big Buck Bunny")
                check(pinned.getLong("contentLength") == FILM_BYTES)
                check(pinned.getLong("contentLength") <= 110L * 1024 * 1024)
                check(pinned.getLong("durationMs") > 20_000)
                check(pinned.getString("apiUrl") == "https://video.blender.org/api/v1/videos/$UUID")
                val primary = Film(
                    pinned.getString("streamUrl"), pinned.getInt("height"),
                    pinned.getLong("contentLength"), pinned.getLong("durationMs"),
                    if (pinned.has("sha256") && !pinned.isNull("sha256")) pinned.getString("sha256") else null
                )
                primary.sha256?.let { check(it.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid full SHA-256 pin" } }
                val registered = AndroidBundledPlaybackProviders.fromContext(context, http, onNativeResult = { result ->
                    // Diagnostics only: keep the real broker result, never create or replace one.
                    nativeResults.add(result)
                    evidence.nativeResult(result)
                })
                evidence.observe("film", obj(
                    "id" to FILM_ID, "uuid" to UUID, "contentId" to CONTENT_ID,
                    "expectedBytes" to primary.bytes, "expectedDurationMs" to primary.durationMs,
                    "streamUrl" to primary.url, "pinnedSha256" to primary.sha256
                ))
                primary to registered
            }
            val provider = providers.single { it.info.id == PROVIDER_ID }
            val card = evidence.stage("catalog") {
                val cards = provider.catalog()
                val selected = cards.single { it.id == FILM_ID }
                verifyIdentity(selected)
                evidence.observe("catalog", obj("count" to cards.size, "selectedId" to selected.id))
                selected
            }
            evidence.stage("search") {
                val matches = provider.catalog("Big Buck Bunny")
                check(matches.any { it.id == card.id && it.contentId == CONTENT_ID }) { "Exact film search missed audited card" }
                check(matches.all { it.providerId == PROVIDER_ID && it.section == FeedSection.HOME })
                evidence.observe("search", obj("query" to "Big Buck Bunny", "resultCount" to matches.size))
            }
            val details = evidence.stage("details") {
                provider.details(card).also {
                    verifyIdentity(it.card)
                    check(it.card.id == card.id && it.episodes.isEmpty()) { "Film details identity/type changed" }
                    evidence.observe("details", obj("title" to it.card.title, "contentId" to it.card.contentId))
                }
            }
            val source = evidence.stage("resolve") {
                val nativeStart = nativeResults.size
                val resolved = ProviderResolver(providers).resolve(ResolveRequest(details.card))
                // Only callbacks from this resolve count; an earlier/separate JNI probe is not evidence.
                val observed = nativeResults.toList().drop(nativeStart)
                evidence.observe("nativeResolution", obj(
                    "observedCallCount" to observed.size, "appUid" to context.applicationInfo.uid,
                    "appPid" to Process.myPid()
                ))
                check(observed.isNotEmpty()) { "Production provider resolution made no observed native calls" }
                val candidates = observed.flatMap { result ->
                    check(result.uid >= 0 && result.uid != context.applicationInfo.uid) { "Resolver did not use an isolated service UID" }
                    check(result.pid > 0 && result.pid != Process.myPid()) { "Resolver ran in the app process" }
                    check(result.engineVersion == EXPECTED_ENGINE_VERSION) { "Unexpected native engine: ${result.engineVersion}" }
                    val rows = JSONArray(result.json)
                    check(rows.length() <= 1) { "Native resolver returned multiple candidates for one quality" }
                    (0 until rows.length()).map { rows.getJSONObject(it) }.onEach { candidate ->
                        check(candidate.getString("uuid") == UUID) { "Native resolver returned a different film" }
                        check(candidate.getInt("height") in 144..2160 && candidate.getLong("size") > 0)
                    }
                }
                check(resolved.isNotEmpty()) { "Live provider resolution returned no usable film (network/availability failure)" }
                check(resolved.size <= ProviderResolver.MAX_SOURCES)
                check(resolved.all { stream -> candidates.any { candidate ->
                    stream.contentId == "blender:${candidate.getString("uuid")}" &&
                        stream.id == "${stream.contentId}:${candidate.getInt("height")}" &&
                        stream.quality == "${candidate.getInt("height")}p" && stream.url == candidate.getString("url")
                } }) { "A resolved source has no matching native UUID/quality/URL output" }
                val selected = resolved.singleOrNull { it.url == manifest.url }
                    ?: error("Resolved sources do not contain the exact audited full-film rendition")
                val nativeCandidate = candidates.singleOrNull {
                    it.getString("uuid") == UUID && it.getInt("height") == manifest.height && it.getString("url") == selected.url
                } ?: error("Selected rendition is missing or duplicated in observed native output")
                check(nativeCandidate.getLong("size") == manifest.bytes) { "Native candidate differs from audited full-film size" }
                check(selected.id == "$CONTENT_ID:${manifest.height}")
                check(selected.quality == "${manifest.height}p")
                check(selected.contentId == CONTENT_ID && selected.providerId == PROVIDER_ID)
                check(selected.section == FeedSection.HOME && !selected.isLive)
                check(selected.protocol == StreamProtocol.MP4 && selected.isDownloadable)
                check(selected.headers.isEmpty()) { "Audited public film unexpectedly requires request headers" }
                check(http.successfulCalls.get() > 0) { "No successful real provider HTTP calls were observed" }
                evidence.observe("resolve", obj(
                    "sourceCount" to resolved.size, "streamId" to selected.id, "url" to selected.url,
                    "contentId" to selected.contentId, "quality" to selected.quality,
                    "nativeObservedCallCount" to observed.size, "matchedNativeCandidate" to nativeCandidate
                ))
                selected
            }
            scenario = evidence.stage("activity_launch") { ActivityScenario.launch(MainActivity::class.java) }
            val onlineBytes = AtomicLong()
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Cinator-Provider-E2E/1")
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(false)
            val onlineFactory = DataSource.Factory {
                val delegate = httpFactory.createDataSource()
                object : DataSource by delegate {
                    override fun open(dataSpec: DataSpec): Long {
                        check(dataSpec.uri.toString() == source.url) { "Player attempted another source" }
                        return delegate.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = delegate.read(buffer, offset, length)
                        if (count > 0 && onlineBytes.addAndGet(count.toLong()) > 32L * 1024 * 1024) {
                            throw IOException("Online playback exceeded the separate 32 MiB read budget")
                        }
                        return count
                    }
                }
            }
            session = createSession(scenario, source.url, onlineFactory)
            exercisePlayback("online", session, manifest, evidence)
            session.close()
            session = null
            evidence.observe("onlineTransport", obj(
                "bytesRead" to onlineBytes.get(), "byteLimit" to 32L * 1024 * 1024,
                "streamId" to source.id, "sourceUrl" to source.url
            ))
            check(onlineBytes.get() > 0) { "Online player did not read network bytes" }

            val file = evidence.stage("full_download") { downloadAndVerify(details.card.copy(streams = listOf(source)), source, manifest, evidence) }
            val offlineFactory = ExactFileFactory(file)
            evidence.stage("offline_rejects_network_uri") {
                val transport = offlineFactory.createDataSource()
                var rejected = false
                try {
                    transport.open(DataSpec.Builder().setUri(source.url).build())
                } catch (_: IOException) {
                    rejected = true
                } finally {
                    transport.close()
                }
                check(rejected && offlineFactory.rejectedOpens.get() == 1) { "Offline factory accepted a network URI" }
            }
            session = createSession(scenario, Uri.fromFile(file).toString(), offlineFactory)
            exercisePlayback("offline", session, manifest, evidence)
            session.close()
            session = null
            evidence.stage("offline_transport") {
                check(offlineFactory.fileOpens.get() > 0 && offlineFactory.bytesRead.get() > 0)
                check(offlineFactory.rejectedOpens.get() == 1) { "Offline playback attempted a forbidden URI" }
                evidence.observe("offlineTransport", obj(
                    "implementation" to "FileDataSource only; exact canonical file",
                    "localFile" to file.canonicalPath, "streamId" to source.id, "sourceUrl" to source.url,
                    "fileOpens" to offlineFactory.fileOpens.get(), "bytesRead" to offlineFactory.bytesRead.get(),
                    "negativeProbeRejections" to 1, "playbackForbiddenUriAttempts" to offlineFactory.rejectedOpens.get() - 1,
                    "deviceNetworkDisabled" to false
                ))
            }
            evidence.finish("PASSED")
        } catch (failure: Throwable) {
            evidence.finish("FAILED", failure)
            throw failure
        } finally {
            try {
                session?.close()
            } finally {
                try {
                    scenario?.close()
                } catch (cleanupFailure: Throwable) {
                    evidence.finish("FAILED", cleanupFailure)
                    throw cleanupFailure
                } finally {
                    evidence.flush()
                    Log.i("ProviderPlaybackE2E", "Evidence: ${evidence.file.absolutePath}")
                }
            }
        }
    }

    /** Negative fixture only: no fabricated catalog/playback or successful native result. */
    private fun rejectsRoundedApiNumbersBeforeNativeAdmission() {
        val registry = context.assets.open("open_films.json").bufferedReader().use { it.readText() }
        val film = JSONObject(registry).getJSONArray("films").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == FILM_ID }
        }
        val origin = "https://video.blender.org"
        val base = obj(
            "uuid" to UUID, "isLocal" to true, "isLive" to false,
            "url" to "$origin/videos/watch/$UUID", "duration" to film.getLong("durationMs") / 1000,
            "privacy" to obj("id" to 1), "blacklisted" to false, "downloadEnabled" to true,
            "account" to obj("host" to "video.blender.org", "name" to "blender", "url" to "$origin/accounts/blender"),
            "channel" to obj("host" to "video.blender.org", "name" to "blender_open_movies", "url" to "$origin/video-channels/blender_open_movies"),
            "files" to JSONArray().put(obj("height" to 720, "resolution" to obj("id" to 720),
                "fileUrl" to film.getString("streamUrl"), "size" to FILM_BYTES,
                "hasAudio" to true, "hasVideo" to true))
        ).toString()
        // A valid baseline must reach the executor; otherwise every negative could pass vacuously.
        val reachedExecutor = IllegalStateException("NUMERIC_FIXTURE_POSITIVE_CONTROL")
        var positiveReached = false
        val control = com.cinator.app.providers.BlenderPlaybackProvider(
            registry, object : TextHttpClient { override fun get(url: String) = base },
            com.cinator.app.providers.BlenderCandidateExecutor { _, _, _ ->
                positiveReached = true
                throw reachedExecutor
            }
        )
        try {
            control.resolve(ResolveRequest(control.catalog().single { it.id == FILM_ID }))
            error("Valid numeric fixture did not reach executor")
        } catch (failure: IllegalStateException) {
            check(positiveReached && failure === reachedExecutor) { "Positive fixture failed before executor admission" }
        }
        val duration = film.getLong("durationMs") / 1000
        val edits = listOf(
            "\"height\":720" to "\"height\":720.0000000000000001",
            "\"id\":720" to "\"id\":720.0000000000000001",
            "\"size\":$FILM_BYTES" to "\"size\":110916740.000000001",
            "\"duration\":$duration" to "\"duration\":${duration}.00000000000001"
        )
        // Confirm this platform really has the lossy parser behavior under review.
        check(JSONObject("{\"value\":720.0000000000000001}").getDouble("value") == 720.0)
        for ((old, replacement) in edits) {
            check(base.contains(old)) { "Numeric regression fixture lost its target token" }
            val document = base.replace(old, replacement)
            var admitted = false
            val provider = com.cinator.app.providers.BlenderPlaybackProvider(
                registry, object : TextHttpClient { override fun get(url: String) = document },
                com.cinator.app.providers.BlenderCandidateExecutor { _, _, _ ->
                    admitted = true
                    throw AssertionError("Rounded fractional API literal reached the native executor")
                }
            )
            try {
                val sources = provider.resolve(ResolveRequest(provider.catalog().single { it.id == FILM_ID }))
                check(sources.isEmpty()) { "Invalid API number returned playback sources" }
            } catch (_: IllegalArgumentException) {
                // Rejecting the complete document is also fail-closed.
            }
            check(!admitted) { "Invalid API number was admitted" }
        }
    }

    private fun verifyIdentity(card: MediaCard) {
        check(card.id == FILM_ID && card.contentId == CONTENT_ID && card.providerId == PROVIDER_ID)
        check(card.title == "Big Buck Bunny" && card.section == FeedSection.HOME && card.kind == MediaKind.MOVIE)
    }

    private data class Film(val url: String, val height: Int, val bytes: Long, val durationMs: Long, val sha256: String?)

    private fun downloadAndVerify(card: MediaCard, source: StreamSource, film: Film, evidence: Evidence): File {
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "provider-e2e-download").apply { isDaemon = true } }
        val progress = AtomicInteger()
        val peak = AtomicLong()
        val deadline = SystemClock.elapsedRealtime() + 12 * 60_000L
        val task = worker.submit(Callable {
            CinatorDownloadManager.downloadDirectMp4(context, card, source) { entry ->
                if (SystemClock.elapsedRealtime() >= deadline || Thread.currentThread().isInterrupted) {
                    throw IOException("E2E download exceeded 12-minute budget or was interrupted")
                }
                check(entry.totalBytes == film.bytes) { "HTTP length differs from audited full-film bytes" }
                check(entry.bytesDownloaded in 0..film.bytes) { "Download exceeded audited byte budget" }
                peak.set(maxOf(peak.get(), entry.bytesDownloaded))
                progress.incrementAndGet()
            }
        })
        val entry = try {
            task.get(minOf(12 * 60_000L, evidence.remainingMs()), TimeUnit.MILLISECONDS)
        } finally {
            task.cancel(true)
            worker.shutdownNow()
        }
        evidence.observe("downloadResult", obj(
            "status" to entry.status.name, "bytesDownloaded" to entry.bytesDownloaded,
            "contentId" to entry.contentId, "providerId" to entry.providerId,
            "streamId" to entry.streamId, "sourceUrl" to entry.sourceUrl, "localFile" to entry.localFilePath,
            "totalBytes" to entry.totalBytes, "progressCallbackCount" to progress.get(),
            "largestObservedProgressBytes" to peak.get(), "error" to entry.errorMessage
        ))
        check(entry.status == DownloadStatus.COMPLETED) { "Real downloader failed: ${entry.errorMessage}" }
        check(entry.mediaId == card.id && entry.sourceUrl == source.url)
        check(entry.contentId == CONTENT_ID && entry.providerId == PROVIDER_ID && entry.streamId == source.id) { "Downloader lost provider/content/stream identity" }
        check(entry.bytesDownloaded == film.bytes && entry.totalBytes == film.bytes)
        check(progress.get() > 0) { "No real download progress callbacks" }
        val file = File(entry.localFilePath).canonicalFile
        val downloads = File(context.filesDir, "downloads").canonicalFile
        check(file.path.startsWith(downloads.path + File.separator))
        check(file.isFile && file.length() == film.bytes && !File(file.path + ".part").exists())
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(24)
        var readBytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(evidence.remainingMs() > 0)
                val count = input.read(buffer)
                if (count < 0) break
                if (readBytes < prefix.size) {
                    buffer.copyInto(prefix, readBytes.toInt(), 0, minOf(count, prefix.size - readBytes.toInt()))
                }
                digest.update(buffer, 0, count)
                readBytes += count
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(readBytes == film.bytes)
        check(String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp") { "Downloaded file lacks MP4 ftyp signature" }
        evidence.observe("downloadIntegrity", obj(
            "localFile" to file.path, "fileBytes" to file.length(), "bytesHashed" to readBytes,
            "sha256" to sha256, "expectedSha256" to film.sha256,
            "fullHashPinStatus" to if (film.sha256 == null) "NOT_PINNED" else if (sha256.equals(film.sha256, true)) "MATCHED" else "MISMATCH",
            "prefixHex" to prefix.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        ))
        film.sha256?.let { check(sha256.equals(it, true)) { "Full-file SHA-256 differs from reviewed pin" } }
        return file
    }

    private fun createSession(scenario: ActivityScenario<MainActivity>, uri: String, factory: DataSource.Factory): PlayerSession {
        lateinit var session: PlayerSession
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val view = PlayerView(activity).apply { useController = false }
            val player = ExoPlayer.Builder(activity)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 5_000, 500, 1_000).build())
                .build()
            session = PlayerSession(player, view, root)
            try {
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                view.player = player
                player.addListener(session.listener)
                player.volume = 0f // Real video decoding; this test does not assert audible audio output.
                player.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.VIDEO_MP4).build())
                player.prepare()
                player.play()
            } catch (failure: Throwable) {
                session.close()
                throw failure
            }
        }
        return session
    }

    private fun exercisePlayback(prefix: String, session: PlayerSession, film: Film, evidence: Evidence) {
        evidence.stage("${prefix}_ready_rendered") {
            val ready = await(session, evidence, "READY and real rendered video") {
                it.state == Player.STATE_READY && it.playing && it.renderedFrames > 0 && it.firstFrameEvents > 0 && it.visible
            }
            check(ready.duration > 20_000) { "Playback is a sample or duration is unknown" }
            check(abs(ready.duration - film.durationMs) <= 2_000) { "Duration differs from the reviewed full film" }
            evidence.observe("${prefix}Ready", ready.json())
        }
        evidence.stage("${prefix}_advancing") {
            val before = session.snapshot()
            val after = await(session, evidence, "advancing position and rendered-output counters") {
                it.playing && it.position >= before.position + 2_000 && it.renderedFrames >= before.renderedFrames + 10
            }
            evidence.observe("${prefix}Advance", obj("before" to before.json(), "after" to after.json()))
        }
        evidence.stage("${prefix}_pause") {
            onMain { session.player.pause() }
            await(session, evidence, "paused") { !it.playing && !it.playWhenReady }
            // Allow an already queued output buffer to drain before measuring the paused interval.
            SystemClock.sleep(300)
            val before = session.snapshot()
            SystemClock.sleep(1_500)
            val after = session.snapshot()
            check(!after.playing && !after.playWhenReady && abs(after.position - before.position) <= 100)
            check(after.error == null)
            evidence.observe("${prefix}Pause", obj("before" to before.json(), "after" to after.json()))
        }
        evidence.stage("${prefix}_seek_resume") {
            val before = session.snapshot()
            val target = 30_000L
            onMain { session.player.seekTo(target) }
            val sought = await(session, evidence, "seek discontinuity and requested position") {
                it.seekEvents > before.seekEvents && it.state == Player.STATE_READY && abs(it.position - target) <= 1_000
            }
            check(!sought.playing && !sought.playWhenReady) { "Seek changed paused intent" }
            onMain { session.player.play() }
            val resumed = await(session, evidence, "decoded frames and position after seek") {
                it.playing && it.position >= target + 2_000 && it.renderedFrames >= sought.renderedFrames + 10
            }
            evidence.observe("${prefix}SeekResume", obj("targetMs" to target, "before" to before.json(), "sought" to sought.json(), "resumed" to resumed.json()))
        }
    }

    private fun await(session: PlayerSession, evidence: Evidence, label: String, predicate: (Snapshot) -> Boolean): Snapshot {
        val deadline = SystemClock.elapsedRealtime() + minOf(60_000L, evidence.remainingMs())
        while (true) {
            val value = session.snapshot()
            if (value.error != null) {
                evidence.observe("lastPlaybackFailure", value.json())
                error("Media3 error awaiting $label: ${value.error}")
            }
            if (predicate(value)) return value
            if (SystemClock.elapsedRealtime() >= deadline || Thread.currentThread().isInterrupted) {
                evidence.observe("lastPlaybackFailure", value.json())
                error("Timed out awaiting $label")
            }
            SystemClock.sleep(200)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else instrumentation.runOnMainSync(block)
    }

    private inner class PlayerSession(val player: ExoPlayer, private val view: PlayerView, private val root: ViewGroup) {
        private var firstFrames = 0
        private var seekEvents = 0
        private var failure: String? = null
        private var closed = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrames++ }
            override fun onPlayerError(error: PlaybackException) { failure = error.errorCodeName }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seekEvents++
            }
        }
        fun snapshot(): Snapshot {
            lateinit var snapshot: Snapshot
            onMain {
                val counters = player.videoDecoderCounters
                counters?.ensureUpdated()
                snapshot = Snapshot(
                    SystemClock.elapsedRealtime(), player.playbackState, player.isPlaying, player.playWhenReady,
                    player.currentPosition, player.duration, counters?.renderedOutputBufferCount ?: 0,
                    firstFrames, seekEvents, view.isShown && view.width > 0 && view.height > 0,
                    failure ?: player.playerError?.errorCodeName
                )
            }
            return snapshot
        }
        fun close() = onMain {
            if (!closed) {
                closed = true
                view.player = null
                player.removeListener(listener)
                player.release()
                root.removeView(view)
            }
        }
    }

    private data class Snapshot(
        val elapsedMs: Long, val state: Int, val playing: Boolean, val playWhenReady: Boolean,
        val position: Long, val duration: Long, val renderedFrames: Int, val firstFrameEvents: Int,
        val seekEvents: Int, val visible: Boolean, val error: String?
    ) {
        fun json() = obj(
            "elapsedRealtimeMs" to elapsedMs, "playbackState" to state, "isPlaying" to playing,
            "playWhenReady" to playWhenReady, "positionMs" to position, "durationMs" to duration,
            "renderedOutputBufferCount" to renderedFrames, "firstFrameEvents" to firstFrameEvents,
            "seekDiscontinuities" to seekEvents, "surfaceViewShown" to visible, "playerErrorCode" to error
        )
    }

    private class ExactFileFactory(file: File) : DataSource.Factory {
        private val allowed = file.canonicalFile
        val rejectedOpens = AtomicInteger()
        val fileOpens = AtomicInteger()
        val bytesRead = AtomicLong()
        override fun createDataSource(): DataSource {
            val delegate = FileDataSource()
            return object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long {
                    val uri = dataSpec.uri
                    if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.path == null ||
                        File(uri.path!!).canonicalFile != allowed) {
                        rejectedOpens.incrementAndGet()
                        throw IOException("Offline player forbids network or unrelated file URIs")
                    }
                    return delegate.open(dataSpec).also { fileOpens.incrementAndGet() }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    delegate.read(buffer, offset, length).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }
            }
        }
    }

    /** Observes the real bounded client; never substitutes a response. */
    private class ObservedHttp(private val evidence: Evidence) : TextHttpClient {
        private val delegate = BoundedHttpClient()
        val successfulCalls = AtomicInteger()
        override fun get(url: String): String {
            val start = SystemClock.elapsedRealtime()
            return try {
                delegate.get(url).also {
                    successfulCalls.incrementAndGet()
                    evidence.http(obj("url" to url, "outcome" to "RESPONSE", "elapsedMs" to SystemClock.elapsedRealtime() - start, "utf8Bytes" to it.toByteArray(Charsets.UTF_8).size))
                }
            } catch (failure: Throwable) {
                evidence.http(obj("url" to url, "outcome" to "FAILED", "elapsedMs" to SystemClock.elapsedRealtime() - start, "exception" to failure.javaClass.name))
                throw failure
            }
        }
    }

    private class Evidence(directory: File) {
        private val start = SystemClock.elapsedRealtime()
        val file = File(directory, "provider-playback-${System.currentTimeMillis()}.json")
        private val stages = JSONArray()
        private val requests = JSONArray()
        private val nativeResults = JSONArray()
        private val observations = JSONObject()
        private val root = obj(
            "schemaVersion" to 2, "test" to "ProviderPlaybackE2ETest", "status" to "RUNNING",
            "startedAtEpochMs" to System.currentTimeMillis(), "sdkInt" to Build.VERSION.SDK_INT,
            "deviceModel" to Build.MODEL, "stages" to stages, "httpRequests" to requests,
            "nativeResults" to nativeResults,
            "observations" to observations, "scope" to "Real provider APIs and isolated native resolver, test-overlay Media3, real downloader, file-only Media3",
            "notVerified" to JSONArray(listOf("App catalog/details navigation", "App player chrome", "Fullscreen/orientation", "Source switching/fallback", "Device-wide offline mode", "Audible audio output", "Watching every frame/end credits"))
        )
        init { check(directory.isDirectory || directory.mkdirs()); flush() }
        fun remainingMs(): Long = (1_200_000L - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0)
        fun <T> stage(name: String, action: () -> T): T {
            val item = obj("name" to name, "status" to "RUNNING", "startedElapsedMs" to SystemClock.elapsedRealtime() - start)
            synchronized(this) { stages.put(item); flush() }
            try {
                check(remainingMs() > 0) { "20-minute test budget exhausted" }
                val result = action()
                synchronized(this) { item.put("status", "PASSED") }
                return result
            } catch (failure: Throwable) {
                synchronized(this) { item.put("status", "FAILED").put("exception", failure.javaClass.name).put("message", failure.message) }
                throw failure
            } finally {
                synchronized(this) { item.put("finishedElapsedMs", SystemClock.elapsedRealtime() - start); flush() }
            }
        }
        @Synchronized fun observe(name: String, value: JSONObject) { observations.put(name, value); flush() }
        @Synchronized fun http(value: JSONObject) { requests.put(value); flush() }
        @Synchronized fun nativeResult(result: IsolatedJsResult) {
            val value = obj(
                "uid" to result.uid, "pid" to result.pid,
                "engineVersion" to result.engineVersion, "rawResultJson" to result.json
            )
            nativeResults.put(value)
            flush() // Preserve the actual reply even if parsing fails.
            value.put("candidates", JSONArray(result.json))
            flush()
        }
        @Synchronized fun finish(status: String, failure: Throwable? = null) {
            root.put("status", status).put("elapsedMs", SystemClock.elapsedRealtime() - start)
            if (failure != null) root.put("failure", obj("exception" to failure.javaClass.name, "message" to failure.message))
            flush()
        }
        @Synchronized fun flush() { file.writeText(root.toString(2)) }
    }

    companion object {
        private const val FILM_ID = "big-buck-bunny"
        private const val UUID = "bf1f3fb5-b119-4f9f-9930-8e20e892b898"
        private const val CONTENT_ID = "blender:$UUID"
        private const val PROVIDER_ID = "blender-open-films"
        private const val FILM_BYTES = 110_916_740L
        private const val EXPECTED_ENGINE_VERSION = "QuickJS 2026-06-04 (04be246)"
        private fun obj(vararg entries: Pair<String, Any?>) = JSONObject().apply {
            entries.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
    }
}
