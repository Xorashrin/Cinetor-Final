package com.cinator.app.downloads

import com.cinator.app.data.SectionStateCoordinator
import com.cinator.app.domain.DownloadEntry
import com.cinator.app.domain.DownloadStatus
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Deterministic IO tests: real temp files, explicitly simulated HTTP responses; no live network. */
class CinatorDownloadManagerTest {
    @get:Rule val temporary = TemporaryFolder(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))
    private val card get() = SectionStateCoordinator.defaultCuratedItems(FeedSection.HOME).single()
    private val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(4) + "isommp42".toByteArray()

    private class Response(
        url: URL,
        val body: ByteArray,
        val status: Int = 200,
        val fields: Map<String, List<String>> = mapOf("Content-Type" to listOf("video/mp4"), "Content-Length" to listOf(body.size.toString())),
        val beforeInput: () -> Unit = {},
        val chunkSize: Int = Int.MAX_VALUE,
        val beforeRead: () -> Unit = {}
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderFields() = fields
        override fun getHeaderField(name: String): String? = fields.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        override fun getInputStream(): InputStream {
            beforeInput()
            return object : ByteArrayInputStream(body) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    beforeRead()
                    return super.read(b, off, minOf(len, chunkSize))
                }
            }
        }
    }

    private fun runDownload(
        selected: MediaCard = card,
        directory: () -> File = { temporary.root },
        progress: (DownloadEntry) -> Unit = {},
        factory: (URL) -> HttpURLConnection = { Response(it, mp4) }
    ): DownloadEntry = CinatorDownloadManager.downloadToFilesDirectory(directory, selected, selected.streams.single(), progress, factory)

    @Test fun ineligibleSourceNeverTouchesContextOrNetwork() {
        val invalid = card.copy(streams = listOf(card.streams.single().copy(isDownloadable = false)))
        val result = runDownload(invalid, directory = { error("Context must not be read") }, factory = { error("No network") })
        assertEquals(DownloadStatus.FAILED, result.status)
        assertTrue(result.localFilePath.isEmpty())
    }

    @Test fun contextFailureBecomesFailedEntryRatherThanEscaping() {
        val result = runDownload(directory = { throw SecurityException("files unavailable") }, factory = { error("No network") })
        assertEquals(DownloadStatus.FAILED, result.status)
        assertEquals("files unavailable", result.errorMessage)
    }

    @Test fun completesOnlyAtFinalPathAfterWritingValidatedBody() {
        val updates = mutableListOf<DownloadEntry>()
        lateinit var response: Response
        val result = runDownload(progress = { updates.add(it); assertFalse(File(it.localFilePath).exists()) }, factory = {
            Response(it, mp4).also { response = it }
        })
        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertArrayEquals(mp4, File(result.localFilePath).readBytes())
        assertEquals(mp4.size.toLong(), result.bytesDownloaded)
        assertEquals(result.totalBytes, File(result.localFilePath).length())
        assertFalse(File(result.localFilePath + ".part").exists())
        assertEquals(1, updates.size)
        assertEquals(DownloadStatus.DOWNLOADING, updates.single().status)
        assertFalse(response.instanceFollowRedirects)
        assertEquals("identity", response.getRequestProperty("Accept-Encoding"))
        assertTrue(response.disconnected)
    }

    @Test fun restartsStalePartWithoutRangeAndPreservesOldFinalUntilCommit() {
        val original = runDownload()
        val target = File(original.localFilePath)
        File(target.path + ".part").writeText("stale partial data")
        val newBody = mp4 + ByteArray(40) { 7 }
        val result = runDownload(factory = {
            Response(it, newBody, beforeInput = { assertArrayEquals(mp4, target.readBytes()) }).also { response ->
                assertNull(response.getRequestProperty("Range"))
                assertNull(response.getRequestProperty("If-Range"))
            }
        })
        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertArrayEquals(newBody, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test fun rejectsBadTransportOrBodyWithoutReplacingPreviouslyCompletedFile() {
        val original = runDownload()
        val target = File(original.localFilePath)
        val variants = listOf<(URL) -> Response>(
            { Response(it, mp4, status = 206) },
            { Response(it, mp4, fields = mapOf("Content-Type" to listOf("text/html"), "Content-Length" to listOf("24"))) },
            { Response(it, mp4, fields = mapOf("Content-Type" to listOf("video/mp4"))) },
            { Response(it, mp4, fields = mapOf("Content-Type" to listOf("video/mp4"), "Content-Length" to listOf("24", "24"))) },
            { Response(it, mp4, fields = mapOf("Content-Type" to listOf("video/mp4"), "Content-Length" to listOf((DownloadPolicy.MAX_BYTES + 1).toString()))) },
            { Response(it, mp4, fields = mapOf("Content-Type" to listOf("video/mp4"), "Content-Length" to listOf("40"))) },
            { Response(it, mp4 + ByteArray(1), fields = mapOf("Content-Type" to listOf("video/mp4"), "Content-Length" to listOf("24"))) },
            { Response(it, ByteArray(24)) },
            { Response(it, mp4, fields = mapOf("Content-Type" to listOf("video/mp4"), "Content-Length" to listOf("24"), "Transfer-Encoding" to listOf("chunked"))) },
            { Response(it, mp4, fields = mapOf("Content-Type" to listOf("video/mp4"), "Content-Length" to listOf("24"), "Content-Encoding" to listOf("gzip"))) }
        )
        variants.forEach { factory ->
            val result = runDownload(factory = factory)
            assertEquals(result.errorMessage, DownloadStatus.FAILED, result.status)
            assertArrayEquals(mp4, target.readBytes())
            assertFalse(File(target.path + ".part").exists())
        }
    }

    @Test fun validatesRedirectBeforeOpeningDestination() {
        val opened = mutableListOf<Response>()
        val result = runDownload(factory = {
            Response(it, byteArrayOf(), 302, mapOf("Location" to listOf("https://127.0.0.1/private"))).also(opened::add)
        })
        assertEquals(DownloadStatus.FAILED, result.status)
        assertEquals(1, opened.size)
        assertTrue(opened.all { it.disconnected && !it.instanceFollowRedirects })
    }

    @Test fun followsSafeRelativeRedirectAndBoundsLoops() {
        val urls = mutableListOf<String>()
        val result = runDownload(factory = {
            urls.add(it.toString())
            if (urls.size == 1) Response(it, byteArrayOf(), 307, mapOf("Location" to listOf("/final.mp4"))) else Response(it, mp4)
        })
        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertEquals("https://interactive-examples.mdn.mozilla.net/final.mp4", urls.last())
        val count = AtomicInteger()
        val loop = runDownload(factory = {
            count.incrementAndGet()
            Response(it, byteArrayOf(), 302, mapOf("Location" to listOf("/loop.mp4")))
        })
        assertEquals(DownloadStatus.FAILED, loop.status)
        assertTrue(count.get() <= DownloadPolicy.MAX_REDIRECTS + 1)
    }

    @Test fun cooperativeInterruptionCancelsAndRemovesPart() {
        try {
            val result = runDownload(progress = { Thread.currentThread().interrupt() })
            assertEquals(DownloadStatus.CANCELLED, result.status)
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(File(result.localFilePath).exists())
            assertFalse(File(result.localFilePath + ".part").exists())
        } finally { Thread.interrupted() }
    }

    @Test fun atomicMoveFailureLeavesNoCompletedEntryOrPart() {
        val result = runDownload(progress = {
            File(it.localFilePath).mkdirs()
            File(it.localFilePath, "keep").writeText("existing directory")
        })
        assertEquals(DownloadStatus.FAILED, result.status)
        assertFalse(File(result.localFilePath + ".part").exists())
        assertEquals("existing directory", File(result.localFilePath, "keep").readText())
    }

    @Test fun interruptionAfterBytesHaveBeenWrittenDeletesPartialFile() {
        val reads = AtomicInteger()
        try {
            val result = runDownload(factory = { Response(it, mp4, chunkSize = 8, beforeRead = {
                if (reads.incrementAndGet() == 2) Thread.currentThread().interrupt()
            }) })
            assertEquals(DownloadStatus.CANCELLED, result.status)
            assertEquals(2, reads.get())
            assertFalse(File(result.localFilePath).exists())
            assertFalse(File(result.localFilePath + ".part").exists())
        } finally { Thread.interrupted() }
    }

    @Test fun progressDoesNotFireForEveryTinyRead() {
        val progress = AtomicInteger()
        val result = runDownload(progress = { progress.incrementAndGet() }, factory = { Response(it, mp4, chunkSize = 1) })
        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertEquals(1, progress.get())
    }

    @Test fun differentTargetsCanTransferWhileOneTargetIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstResult = AtomicReference<DownloadEntry>()
        val first = Thread { firstResult.set(runDownload(factory = {
            Response(it, mp4, beforeInput = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        })) }
        first.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val other = card.copy(id = "different-target")
            assertEquals(DownloadStatus.COMPLETED, runDownload(other).status)
        } finally { release.countDown(); first.join(5000) }
        assertEquals(DownloadStatus.COMPLETED, firstResult.get().status)
    }

    @Test fun interruptedDuplicateDoesNotCancelTheOwner() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ownerResult = AtomicReference<DownloadEntry>()
        val waiterResult = AtomicReference<DownloadEntry>()
        val owner = Thread { ownerResult.set(runDownload(factory = {
            Response(it, mp4, beforeInput = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        })) }
        val waiter = Thread { waiterResult.set(runDownload(factory = { error("Duplicate must not connect") })) }
        owner.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            waiter.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (waiter.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.WAITING, waiter.state)
            waiter.interrupt()
            waiter.join(3000)
            assertEquals(DownloadStatus.CANCELLED, waiterResult.get().status)
        } finally { release.countDown(); owner.join(5000); waiter.join(5000) }
        assertEquals(DownloadStatus.COMPLETED, ownerResult.get().status)
        assertArrayEquals(mp4, File(ownerResult.get().localFilePath).readBytes())
    }

    @Test fun factoryAndCallbackFailuresReleaseTheTargetForRetry() {
        assertEquals(DownloadStatus.FAILED, runDownload(factory = { throw java.io.IOException("connection failed") }).status)
        val failed = runDownload(progress = { throw IllegalStateException("observer failed") })
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertFalse(File(failed.localFilePath + ".part").exists())
        assertEquals(DownloadStatus.COMPLETED, runDownload().status)
    }

    @Test fun reentrantCallbackCannotDeadlockItsOwnDownload() {
        lateinit var duplicate: DownloadEntry
        val result = runDownload(progress = { duplicate = runDownload(factory = { error("No second connection") }) })
        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertEquals(DownloadStatus.FAILED, duplicate.status)
        assertTrue(duplicate.errorMessage.orEmpty().contains("already active"))
    }

    @Test fun concurrentDirectoryCreationDoesNotFailSeparateTargets() {
        val ready = CountDownLatch(12)
        val release = CountDownLatch(1)
        val results = (0 until 12).map { AtomicReference<DownloadEntry>() }
        val threads = results.mapIndexed { index, result -> Thread {
            result.set(runDownload(card.copy(id = "target-$index"), directory = {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                temporary.root
            }))
        } }
        threads.forEach(Thread::start)
        try { assertTrue(ready.await(3, TimeUnit.SECONDS)) } finally { release.countDown() }
        threads.forEach { it.join(5000) }
        assertTrue(results.map { it.get() }.toString(), results.all { it.get()?.status == DownloadStatus.COMPLETED })
    }

    @Test fun concurrentDuplicateTargetJoinsOneTransfer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opened = AtomicInteger()
        val results = arrayOf(AtomicReference<DownloadEntry>(), AtomicReference<DownloadEntry>())
        val factory: (URL) -> HttpURLConnection = {
            opened.incrementAndGet()
            Response(it, mp4, beforeInput = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        }
        val first = Thread { results[0].set(runDownload(factory = factory)) }
        val second = Thread { results[1].set(runDownload(factory = factory)) }
        first.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            second.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (second.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.WAITING, second.state)
        } finally { release.countDown(); first.join(5000); second.join(5000) }
        assertEquals(1, opened.get())
        assertEquals(DownloadStatus.COMPLETED, results[0].get().status)
        assertEquals(results[0].get(), results[1].get())
    }
}
