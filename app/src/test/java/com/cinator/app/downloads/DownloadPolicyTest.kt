package com.cinator.app.downloads

import com.cinator.app.data.SectionStateCoordinator
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) {
            // Expected policy rejection.
        }
    }

    @Test fun targetHashPreservesEveryIdentityComponentWithoutFilenameCollisions() {
        val card = sample()
        val stream = card.streams.single()
        val key = DownloadPolicy.targetKey(card, stream)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertEquals(key, DownloadPolicy.targetKey(card.copy(title = "Renamed"), stream.copy()))
        val changed = listOf(
            DownloadPolicy.targetKey(card.copy(id = "different"), stream),
            DownloadPolicy.targetKey(card.copy(section = FeedSection.ANIME), stream),
            DownloadPolicy.targetKey(card.copy(providerId = "different"), stream),
            DownloadPolicy.targetKey(card.copy(contentId = "different"), stream),
            DownloadPolicy.targetKey(card, stream.copy(id = "different")),
            DownloadPolicy.targetKey(card, stream.copy(providerId = "different")),
            DownloadPolicy.targetKey(card, stream.copy(contentId = "different")),
            DownloadPolicy.targetKey(card, stream.copy(section = FeedSection.ANIME)),
            DownloadPolicy.targetKey(card, stream.copy(url = stream.url + "?token=one")),
            DownloadPolicy.targetKey(card, stream.copy(url = stream.url + "?token=two"))
        )
        assertFalse(changed.contains(key))
        assertEquals(changed.size, changed.toSet().size)
        assertNotEquals(DownloadPolicy.targetKey(card.copy(id = "a/b"), stream),
            DownloadPolicy.targetKey(card.copy(id = "a_b"), stream))
        assertNotEquals(DownloadPolicy.targetKey(card.copy(id = "a:b"), stream.copy(id = "c")),
            DownloadPolicy.targetKey(card.copy(id = "a"), stream.copy(id = "b:c")))
    }

    @Test fun completeResponseRequiresFullUnencodedMp4WithKnownBoundedLength() {
        assertEquals(24L, DownloadPolicy.responseLength(200, "video/mp4; charset=binary", "24", null, null))
        assertEquals(24L, DownloadPolicy.responseLength(200, "VIDEO/MP4", "24", "identity", null))
        rejects { DownloadPolicy.responseLength(206, "video/mp4", "24", null, "bytes 0-23/24") }
        rejects { DownloadPolicy.responseLength(204, "video/mp4", "24", null, null) }
        rejects { DownloadPolicy.responseLength(200, "text/html", "24", null, null) }
        rejects { DownloadPolicy.responseLength(200, null, "24", null, null) }
        rejects { DownloadPolicy.responseLength(200, "application/octet-stream", "24", null, null) }
        rejects { DownloadPolicy.responseLength(200, "video/mp4", "24", "gzip", null) }
        rejects { DownloadPolicy.responseLength(200, "video/mp4", "24", null, "bytes 0-23/24") }
        listOf(null, "", "-1", "0", "23", "24,24", "+24", "9223372036854775808", (DownloadPolicy.MAX_BYTES + 1).toString()).forEach { length ->
            rejects { DownloadPolicy.responseLength(200, "video/mp4", length, null, null) }
        }
        assertEquals(DownloadPolicy.MAX_BYTES,
            DownloadPolicy.responseLength(200, "video/mp4", DownloadPolicy.MAX_BYTES.toString(), null, null))
    }

    @Test fun validatesEveryRedirectWithoutDowngradeOrUnboundedHops() {
        val base = "https://media.example.com/folder/a.mp4"
        assertEquals("https://media.example.com/final.mp4?token=a%2Fb", DownloadPolicy.redirectTarget(base, "../final.mp4?token=a%2Fb", 0))
        assertEquals("https://cdn.example.com/a.mp4", DownloadPolicy.redirectTarget(base, "//cdn.example.com/a.mp4", 4))
        listOf(null, "", " ", "https://localhost/a", "https://10.0.0.1/a", "http://example.com/a", "https://user@example.com/a", "https://example.com:8443/a", "https://example.com/a%0d").forEach {
            rejects { DownloadPolicy.redirectTarget(base, it, 0) }
        }
        rejects { DownloadPolicy.redirectTarget(base, "/a.mp4", DownloadPolicy.MAX_REDIRECTS) }
        rejects { DownloadPolicy.redirectTarget("http://example.com", "/a.mp4", 0) }
    }

    @Test fun rejectsOverrunsTruncationOverflowAndMislabelledBodies() {
        assertEquals(24L, DownloadPolicy.checkedByteCount(8, 16, 24))
        rejects { DownloadPolicy.checkedByteCount(24, 1, 24) }
        rejects { DownloadPolicy.checkedByteCount(DownloadPolicy.MAX_BYTES, 1, DownloadPolicy.MAX_BYTES) }
        rejects { DownloadPolicy.checkedByteCount(Long.MAX_VALUE, 1, 24) }
        rejects { DownloadPolicy.checkedByteCount(0, -1, 24) }
        val prefix = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(4) + "isommp42".toByteArray()
        DownloadPolicy.verifyComplete(24, 24, prefix)
        rejects { DownloadPolicy.verifyComplete(23, 24, prefix) }
        rejects { DownloadPolicy.verifyComplete(25, 24, prefix) }
        rejects { DownloadPolicy.verifyComplete(24, 24, "<html>not mp4 at all</html>".toByteArray()) }
        rejects { DownloadPolicy.verifyComplete(24, 24, prefix.copyOf(8)) }
        rejects { DownloadPolicy.verifyComplete(24, 24, prefix.copyOf().apply { this[3] = 32 }) }
    }

    @Test fun elapsedBudgetAndProgressIntervalsAreBounded() {
        DownloadPolicy.checkElapsed(DownloadPolicy.MAX_DURATION_MS - 1)
        rejects { DownloadPolicy.checkElapsed(DownloadPolicy.MAX_DURATION_MS) }
        assertFalse(DownloadPolicy.progressDue(249))
        assertTrue(DownloadPolicy.progressDue(250))
    }

    private fun sample(): MediaCard = SectionStateCoordinator.defaultCuratedItems(FeedSection.HOME).single()

    @Test fun requiresTheExactDeclaredSourceAndMatchingContentIdentity() {
        val card = sample()
        val stream = card.streams.single()
        assertNull(DownloadPolicy.rejectionReason(card, stream.copy()))
        assertNotNull(DownloadPolicy.rejectionReason(card, stream.copy(url = "https://example.com/other.mp4")))
        assertNotNull(DownloadPolicy.rejectionReason(card.copy(streams = emptyList()), stream))
        assertNotNull(DownloadPolicy.rejectionReason(card.copy(contentId = "other"), stream))
        assertNotNull(DownloadPolicy.rejectionReason(card.copy(contentId = null), stream))
        assertNotNull(DownloadPolicy.rejectionReason(card.copy(section = FeedSection.ANIME), stream))
        assertNotNull(DownloadPolicy.rejectionReason(card.copy(providerId = "other"), stream))
        assertNotNull(DownloadPolicy.rejectionReason(card.copy(streams = listOf(stream, stream.copy(url = "https://example.com/other.mp4"))), stream))
    }

    @Test fun refusesLiveUndeclaredUnsafeAndExternalReferenceDownloads() {
        val card = sample()
        val stream = card.streams.single()
        val blocked = listOf(
            stream.copy(isDownloadable = false), stream.copy(isLive = true),
            stream.copy(protocol = StreamProtocol.HLS_M3U8), stream.copy(section = null),
            stream.copy(contentId = null), stream.copy(contentId = ""), stream.copy(id = ""),
            stream.copy(url = "http://example.com/a.mp4"), stream.copy(url = "https://127.0.0.1/a.mp4"),
            stream.copy(headers = mapOf("Authorization" to "not-supported")),
            stream.copy(providerId = "vidsrc-ref"), stream.copy(providerId = "tvmaze-home")
        )
        blocked.forEach {
            assertFalse(it.toString(), DownloadPolicy.canDownloadStream(it))
            assertNotNull(DownloadPolicy.rejectionReason(card.copy(providerId = it.providerId, streams = listOf(it)), it))
        }
        assertNotNull(DownloadPolicy.rejectionReason(card.copy(kind = MediaKind.LIVE_TV), stream))
        val live = stream.copy(section = FeedSection.LIVETV)
        assertNotNull(DownloadPolicy.rejectionReason(card.copy(section = FeedSection.LIVETV, streams = listOf(live)), live))
    }
}
