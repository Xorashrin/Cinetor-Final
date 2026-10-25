package com.cinator.app.downloads

import android.content.Context
import com.cinator.app.data.UrlPolicyValidator
import com.cinator.app.domain.DownloadEntry
import com.cinator.app.domain.DownloadStatus
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.StreamSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

/**
 * Blocking foreground task; call off the UI thread. No WorkManager, background service,
 * process-death recovery, or resume: without a persisted strong validator every retry starts
 * at byte zero. Old legacy filenames are never trusted. Only a verified .part is published.
 *
 * Cancellation is cooperative thread interruption (including duplicate waiters). A plain
 * coroutine withContext(IO) does not interrupt its thread; callers needing cancellation must
 * use runInterruptible or interrupt the worker. Network waits are bounded by IO timeouts;
 * the elapsed budget is checked between operations, not a hard DNS/filesystem deadline.
 */
object CinatorDownloadManager {
    private data class InFlight(val owner: Thread, val task: FutureTask<DownloadEntry>)
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    fun canDownloadStream(stream: StreamSource): Boolean = DownloadPolicy.canDownloadStream(stream)

    /** Builds a local transport source without replacing its canonical remote identity. */
    internal fun offlineSource(entry: DownloadEntry): StreamSource? {
        val contentId = entry.contentId?.takeIf { it.isNotBlank() } ?: return null
        val providerId = entry.providerId?.takeIf { it.isNotBlank() } ?: return null
        val streamId = entry.streamId?.takeIf { it.isNotBlank() } ?: return null
        if (entry.status != DownloadStatus.COMPLETED || entry.localFilePath.isBlank()) return null
        return StreamSource(
            id = streamId,
            label = "Offline MP4",
            url = entry.localFilePath,
            protocol = com.cinator.app.domain.StreamProtocol.MP4,
            quality = "Local Storage",
            isDownloadable = false,
            providerId = providerId,
            contentId = contentId,
            section = entry.section
        )
    }

    fun downloadDirectMp4(
        context: Context,
        card: MediaCard,
        stream: StreamSource,
        onProgress: (DownloadEntry) -> Unit
    ): DownloadEntry = downloadToFilesDirectory({ context.filesDir }, card, stream, onProgress)

    /** Injection seam for real-file/simulated-HTTP JVM regression tests. */
    internal fun downloadToFilesDirectory(
        filesDirectory: () -> File,
        card: MediaCard,
        stream: StreamSource,
        onProgress: (DownloadEntry) -> Unit,
        openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
    ): DownloadEntry {
        var entry = DownloadEntry(
            id = "dl_invalid", mediaId = card.id, title = card.title, section = card.section,
            sourceUrl = stream.url, localFilePath = "", bytesDownloaded = 0L, totalBytes = 0L,
            status = DownloadStatus.FAILED,
            contentId = stream.contentId, providerId = stream.providerId, streamId = stream.id
        )
        return try {
            val key = DownloadPolicy.targetKey(card, stream)
            entry = entry.copy(id = "dl_$key")
            DownloadPolicy.rejectionReason(card, stream)?.let { return entry.copy(errorMessage = it) }
            checkInterrupted()
            // Context access and directory creation are inside the failure boundary.
            val root = filesDirectory().canonicalFile
            val dir = File(root, "downloads/v2/${card.section.name.lowercase(Locale.ROOT)}")
            require(dir.canonicalPath.startsWith(root.path + File.separator)) { "Invalid download directory" }
            // mkdirs() may return false because another target created this directory.
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create download directory")
            val target = File(dir, "$key.mp4")
            entry = entry.copy(localFilePath = target.absolutePath)
            val lockKey = target.absolutePath
            val candidate = InFlight(Thread.currentThread(), FutureTask {
                transfer(entry, target, onProgress, openConnection)
            })
            val existing = inFlight.putIfAbsent(lockKey, candidate)
            if (existing != null) {
                // Never deadlock if an observer recursively starts its own target.
                require(existing.owner !== Thread.currentThread()) { "Download already active on this thread" }
                existing.task.get().copy(title = card.title)
            } else {
                try {
                    // No process-wide monitor is held over IO or callbacks. Duplicates join
                    // this task, not a second writer; different targets transfer independently.
                    candidate.task.run()
                    candidate.task.get()
                } finally {
                    inFlight.remove(lockKey, candidate)
                }
            }
        } catch (e: Exception) {
            failure(entry, e)
        }
    }

    private fun transfer(
        initial: DownloadEntry,
        target: File,
        onProgress: (DownloadEntry) -> Unit,
        openConnection: (URL) -> HttpURLConnection
    ): DownloadEntry {
        val part = File(target.path + ".part")
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        var entry = initial
        fun checkActive() {
            checkInterrupted()
            DownloadPolicy.checkElapsed((System.nanoTime() - started) / 1_000_000)
        }
        return try {
            var currentUrl = initial.sourceUrl
            var redirects = 0
            var responseCode: Int
            while (true) {
                checkActive()
                require(UrlPolicyValidator.isSafeHttpsUrl(currentUrl)) { "Unsafe download URL" }
                val conn = openConnection(URI(currentUrl).toURL())
                connection = conn // Also close if configuration/response parsing throws.
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "Cinator/0.3")
                conn.setRequestProperty("Accept", "video/mp4")
                conn.setRequestProperty("Accept-Encoding", "identity")
                // Intentionally no Range/If-Range: no reliable persisted entity validator.
                responseCode = conn.responseCode
                checkActive()
                if (responseCode !in setOf(301, 302, 303, 307, 308)) break
                val next = DownloadPolicy.redirectTarget(currentUrl, singleHeader(conn, "Location"), redirects)
                conn.disconnect()
                connection = null
                currentUrl = next
                redirects++
            }
            val conn = checkNotNull(connection)
            require(singleHeader(conn, "Transfer-Encoding") == null) { "Transfer-encoded downloads are unsupported" }
            val expected = DownloadPolicy.responseLength(
                responseCode, singleHeader(conn, "Content-Type"), singleHeader(conn, "Content-Length"),
                singleHeader(conn, "Content-Encoding"), singleHeader(conn, "Content-Range")
            )
            entry = entry.copy(totalBytes = expected, status = DownloadStatus.DOWNLOADING)
            if (part.exists() && !part.delete()) throw IOException("Cannot discard stale partial download")
            if (target.parentFile!!.usableSpace < expected) throw IOException("Insufficient download storage")
            var downloaded = 0L
            val prefix = ByteArray(24)
            var prefixCount = 0
            var lastProgress = System.nanoTime()
            onProgress(entry)
            checkActive()
            conn.inputStream.use { input ->
                FileOutputStream(part, false).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        checkActive()
                        if (count == -1) break
                        if (count == 0) continue
                        val next = DownloadPolicy.checkedByteCount(downloaded, count, expected)
                        if (prefixCount < prefix.size) {
                            val copyCount = minOf(count, prefix.size - prefixCount)
                            buffer.copyInto(prefix, prefixCount, 0, copyCount)
                            prefixCount += copyCount
                        }
                        output.write(buffer, 0, count)
                        downloaded = next
                        entry = entry.copy(bytesDownloaded = downloaded)
                        val now = System.nanoTime()
                        if (DownloadPolicy.progressDue((now - lastProgress) / 1_000_000)) {
                            onProgress(entry)
                            lastProgress = now
                        }
                    }
                    DownloadPolicy.verifyComplete(downloaded, expected, prefix.copyOf(prefixCount))
                    output.flush()
                    output.fd.sync()
                }
            }
            require(part.length() == expected) { "Partial file length mismatch after flush" }
            checkActive()
            // Same directory/filesystem, atomic replacement only. No copy/delete fallback
            // that could expose incomplete bytes. Preserve any previous good final on failure.
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            entry.copy(status = DownloadStatus.COMPLETED)
        } catch (e: Exception) {
            failure(entry, e)
        } finally {
            runCatching { connection?.disconnect() }
            // Interrupted/failed bytes cannot be resumed or mistaken for a completed file.
            runCatching { Files.deleteIfExists(part.toPath()) }
        }
    }

    private fun singleHeader(connection: HttpURLConnection, name: String): String? {
        val values = connection.headerFields.entries.filter { it.key?.equals(name, ignoreCase = true) == true }
            .flatMap { it.value }
        require(values.size <= 1) { "Ambiguous $name header" }
        return values.singleOrNull()
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Download cancelled")
    }

    private fun failure(entry: DownloadEntry, error: Exception): DownloadEntry {
        val cancelled = error is InterruptedException || Thread.currentThread().isInterrupted
        if (cancelled) Thread.currentThread().interrupt()
        return entry.copy(
            bytesDownloaded = 0L,
            status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED,
            errorMessage = if (cancelled) "Download cancelled" else error.message ?: error.javaClass.simpleName
        )
    }
}
