package com.cinator.app.playback

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PlaybackFallbackTest {
    @get:Rule val temporary = TemporaryFolder(File(System.getenv("TMPDIR") ?: System.getProperty("user.home")))
    private fun source(url: String = "https://cdn.example.org/episode.mp4") = StreamSource(
        id = "primary", label = "Primary", url = url, protocol = StreamProtocol.MP4,
        quality = "Unknown", isDownloadable = false, providerId = "provider",
        contentId = "series:episode-1", section = FeedSection.ANIME
    )

    @Test fun `redirect targets stay HTTPS and DNS answers must be public`() {
        val base = "https://cdn.example.org/path/master.m3u8"
        assertEquals("https://cdn.example.org/video.m3u8", playbackRedirectUrl(base, "../video.m3u8"))
        assertEquals("https://mirror.example.org/a", playbackRedirectUrl(base, "https://mirror.example.org/a"))
        listOf("http://cdn.example.org/a", "https://127.0.0.1/a", "https://[::1]/a",
            "https://169.254.169.254/a", "https://user:pass@example.org/a", " https://example.org/a").forEach {
            assertNull(playbackRedirectUrl(base, it))
        }
        listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "192.168.1.1", "::1", "fc00::1", "fe80::1").forEach {
            assertFalse(isPublicPlaybackAddress(java.net.InetAddress.getByName(it)))
        }
        assertTrue(isPublicPlaybackAddress(java.net.InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicPlaybackAddress(java.net.InetAddress.getByName("2001:4860:4860::8888")))
    }

    @Test fun `unsafe headers cannot be sent by a fallback source`() {
        val unsafe = listOf(
            mapOf("Host" to "other.example.org"), mapOf("Cookie" to "a=b\r\nHost: bad"),
            mapOf("Bad Header" to "value"), mapOf("Range" to "bytes=999-"),
            mapOf("Authorization" to "a".repeat(4097))
        )
        unsafe.forEach { headers ->
            assertNull(PlaybackFallback(source().copy(headers = headers)).next())
        }
        val valid = source().copy(headers = mapOf("Authorization" to "Bearer supplied-token", "Referer" to "https://example.org/"))
        assertEquals(valid, PlaybackFallback(valid).next())
    }

    @Test fun `buffering pause intent survives lifecycle stop and restart`() {
        val intent = PlaybackIntent()
        // Buffering means isPlaying == false but playWhenReady == true.
        intent.toggle(playWhenReady = true)
        assertFalse(intent.shouldPlay)
        intent.onStop()
        intent.observePlayWhenReady(false)
        intent.onStart()
        assertFalse("A paused user must remain paused", intent.shouldPlay)
        intent.toggle(playWhenReady = false)
        assertTrue(intent.shouldPlay)
        intent.onStop()
        intent.observePlayWhenReady(false)
        intent.onStop()
        assertFalse(intent.shouldPlay)
        intent.onStart()
        assertTrue("Only a previously playing user resumes", intent.shouldPlay)
        intent.observePlayWhenReady(false)
        assertFalse("Audio focus/noisy pauses are respected", intent.shouldPlay)
    }

    @Test fun `offline playback only opens a readable file within app downloads`() {
        val downloads = temporary.newFolder("downloads")
        val file = File(downloads, "anime/episode.mp4").apply { parentFile.mkdirs(); writeText("test") }
        val outside = temporary.newFile("secret.mp4")
        val sibling = temporary.newFolder("downloads-other")
        val siblingFile = File(sibling, "secret.mp4").apply { writeText("test") }
        assertEquals(file.canonicalFile, localPlaybackFile(downloads, file.absolutePath))
        assertNull(localPlaybackFile(downloads, outside.absolutePath))
        assertNull(localPlaybackFile(downloads, siblingFile.absolutePath))
        assertNull(localPlaybackFile(downloads, File(downloads, "../secret.mp4").absolutePath))
        assertNull(localPlaybackFile(downloads, downloads.absolutePath))
        assertNull(localPlaybackFile(downloads, File(downloads, "missing.mp4").absolutePath))
        assertNull(localPlaybackFile(downloads, "anime/episode.mp4"))
        assertNull(localPlaybackFile(downloads, file.toURI().toString()))
        val link = File(downloads, "escape.mp4")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(localPlaybackFile(downloads, link.absolutePath))
    }

    @Test fun `resume belongs to content and section rather than provider URL`() {
        val primary = source()
        val mirror = primary.copy(id = "new-id", providerId = "other", url = "https://other.example.org/a.mp4")
        assertNotNull(playbackResumeKey(primary))
        assertEquals(playbackResumeKey(primary), playbackResumeKey(mirror))
        assertNotEquals(playbackResumeKey(primary), playbackResumeKey(primary.copy(section = FeedSection.HOME)))
        assertNotEquals(playbackResumeKey(primary), playbackResumeKey(primary.copy(contentId = "series:episode-2")))
        assertNull(playbackResumeKey(primary.copy(contentId = null)))
        assertNull(playbackResumeKey(primary.copy(contentId = " ")))
        assertNull(playbackResumeKey(primary.copy(section = null)))
        assertNull(playbackResumeKey(primary.copy(isLive = true)))
    }

    @Test fun `URL aliases cannot bypass the attempt cap or replay the primary`() {
        val primary = source(url = "https://cdn.example.org/episode.mp4")
        val next = primary.copy(url = "https://cdn.example.org/next.mp4")
        val fallback = PlaybackFallback(primary, listOf(
            primary.copy(url = "https://CDN.example.org:443/episode.mp4"), next
        ))
        assertEquals(primary, fallback.next())
        assertEquals(next, fallback.next())
        assertNull(fallback.next())
    }

    @Test fun `unsafe source URLs are skipped before an attempt`() {
        val primary = source(url = "http://cdn.example.org/plain.mp4")
        val valid = source(url = "https://cdn.example.org/good.mp4").copy(id = "good")
        val fallback = PlaybackFallback(primary, listOf(
            primary.copy(url = "HTTPS://"),
            primary.copy(url = "https://user:pass@example.org/private.mp4"), valid
        ))
        assertEquals(valid, fallback.next())
        assertNull(fallback.next())
    }

    @Test fun `each distinct URL is attempted once within a hard cap`() {
        val primary = source()
        val second = primary.copy(id = "second", url = "https://mirror.example.org/2.mp4")
        val third = primary.copy(id = "third", url = "https://mirror.example.org/3.mp4")
        val fallback = PlaybackFallback(primary, listOf(
            primary.copy(id = "duplicate", headers = mapOf("X-Test" to "ignored")),
            second, second.copy(id = "same-url"), third,
            third.copy(url = "https://mirror.example.org/4.mp4")
        ), maxAttempts = 999)
        assertEquals(listOf(primary, second, third), generateSequence { fallback.next() }.toList())
        repeat(5) { assertNull("Exhaustion is terminal", fallback.next()) }
        assertEquals(listOf(primary), generateSequence { PlaybackFallback(primary).next() }.take(1).toList())
        val limited = PlaybackFallback(primary, listOf(second), maxAttempts = 1)
        assertEquals(primary, limited.next())
        assertNull(limited.next())
        assertThrows(IllegalArgumentException::class.java) { PlaybackFallback(primary, maxAttempts = 0) }
    }

    @Test fun `unknown or blank identity cannot authorize a fallback`() {
        val unknowns = listOf(
            source().copy(contentId = null), source().copy(contentId = ""),
            source().copy(contentId = "   "), source().copy(section = null)
        )
        unknowns.forEach { primary ->
            val fallback = PlaybackFallback(primary, listOf(primary.copy(url = "https://mirror.example.org/a.mp4")))
            assertEquals(primary, fallback.next())
            assertNull("Unknown identity must not match another unknown", fallback.next())
        }
    }

    @Test fun `fallback never substitutes a different title section or live mode`() {
        val primary = source()
        val same = primary.copy(id = "mirror", url = "https://mirror.example.org/episode.mp4")
        val candidates = listOf(
            same.copy(contentId = "cc0:flower"),
            same.copy(section = FeedSection.HOME),
            same.copy(isLive = true), same
        )
        val fallback = PlaybackFallback(primary, candidates)
        assertEquals(primary, fallback.next())
        assertEquals(same, fallback.next())
        assertNull(fallback.next())
    }
}
