package com.cinator.app.downloads

import com.cinator.app.domain.DownloadEntry
import com.cinator.app.domain.DownloadStatus
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Real temporary-file transfers with explicitly simulated HTTP, not live provider evidence. */
class DownloadIdentityPropagationTest {
    @get:Rule val temporary = TemporaryFolder(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
    private val contentId = "blender:bf1f3fb5-b119-4f9f-9930-8e20e892b898"
    private val stream = StreamSource(
        id = "$contentId:720", label = "720p", url = "https://video.blender.org/download/videos/big-buck-bunny.mp4",
        protocol = StreamProtocol.MP4, quality = "720p", isDownloadable = true,
        providerId = "blender-open-films", contentId = contentId, section = FeedSection.HOME
    )
    private val card = MediaCard(
        id = "big-buck-bunny", title = "Big Buck Bunny", subtitle = "", synopsis = "",
        section = FeedSection.HOME, providerId = stream.providerId, providerName = "Blender Open Films",
        streams = listOf(stream), kind = MediaKind.MOVIE, contentId = contentId
    )
    private val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(4) + "isommp42".toByteArray()
    private class Response(url: URL, private val body: ByteArray) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getHeaderFields() = mapOf("Content-Type" to listOf("video/mp4"), "Content-Length" to listOf(body.size.toString()))
        override fun getInputStream() = ByteArrayInputStream(body)
    }
    private fun assertIdentity(entry: DownloadEntry) {
        assertEquals(card.id, entry.mediaId)
        assertEquals(contentId, entry.contentId)
        assertEquals(stream.providerId, entry.providerId)
        assertEquals(stream.id, entry.streamId)
        assertNotEquals(entry.mediaId, entry.contentId)
    }

    @Test fun canonicalIdentitiesSurviveProgressAndCompletedFilePublication() {
        val progress = mutableListOf<DownloadEntry>()
        val result = CinatorDownloadManager.downloadToFilesDirectory(
            { temporary.root }, card, stream, { progress.add(it) }, { Response(it, mp4) }
        )
        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertArrayEquals(mp4, File(result.localFilePath).readBytes())
        assertEquals("dl_${DownloadPolicy.targetKey(card, stream)}", result.id)
        assertTrue(progress.isNotEmpty())
        progress.forEach(::assertIdentity)
        assertIdentity(result)
    }

    private fun completedEntry() = DownloadEntry(
        id = "download-key", mediaId = card.id, title = card.title, section = card.section,
        sourceUrl = stream.url, localFilePath = File(temporary.root, "saved.mp4").path,
        bytesDownloaded = 24, totalBytes = 24, status = DownloadStatus.COMPLETED,
        contentId = contentId, providerId = stream.providerId, streamId = stream.id
    )

    @Test fun offlineSourcePreservesCanonicalIdentityWithoutUsingCardOrDownloadIds() {
        val entry = completedEntry()
        val offline = requireNotNull(CinatorDownloadManager.offlineSource(entry))
        assertEquals(stream.id, offline.id)
        assertEquals(stream.contentId, offline.contentId)
        assertEquals(stream.providerId, offline.providerId)
        assertEquals(stream.section, offline.section)
        assertEquals(entry.localFilePath, offline.url)
        assertEquals(StreamProtocol.MP4, offline.protocol)
        assertFalse(offline.isDownloadable)
        assertFalse(offline.isLive)
        assertTrue(offline.headers.isEmpty())
    }

    @Test fun offlineSourceRejectsMissingIdentitiesInsteadOfGuessingLegacyValues() {
        val entry = completedEntry()
        for (missing in listOf(null, "", "  ")) {
            assertNull(CinatorDownloadManager.offlineSource(entry.copy(contentId = missing)))
            assertNull(CinatorDownloadManager.offlineSource(entry.copy(providerId = missing)))
            assertNull(CinatorDownloadManager.offlineSource(entry.copy(streamId = missing)))
        }
        assertNull(CinatorDownloadManager.offlineSource(entry.copy(localFilePath = "")))
        DownloadStatus.values().filter { it != DownloadStatus.COMPLETED }.forEach {
            assertNull(CinatorDownloadManager.offlineSource(entry.copy(status = it)))
        }
    }

    @Test fun failedAndCancelledTransfersKeepIdentities() {
        val failed = CinatorDownloadManager.downloadToFilesDirectory(
            { throw SecurityException("storage unavailable") }, card, stream, {}, { error("No network") }
        )
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertIdentity(failed)
        try {
            val cancelled = CinatorDownloadManager.downloadToFilesDirectory(
                { temporary.root }, card, stream, { Thread.currentThread().interrupt() }, { Response(it, mp4) }
            )
            assertEquals(DownloadStatus.CANCELLED, cancelled.status)
            assertIdentity(cancelled)
        } finally { Thread.interrupted() }
    }
}

