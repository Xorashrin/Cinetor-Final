package com.cinator.app.downloads

import android.content.Context
import com.cinator.app.data.UrlPolicyValidator
import com.cinator.app.domain.DownloadEntry
import com.cinator.app.domain.DownloadStatus
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object CinatorDownloadManager {
    fun canDownloadStream(stream: StreamSource): Boolean {
        return stream.isDownloadable &&
            stream.protocol == StreamProtocol.MP4 &&
            UrlPolicyValidator.isSafeHttpsUrl(stream.url)
    }

    fun downloadDirectMp4(
        context: Context,
        card: MediaCard,
        stream: StreamSource,
        onProgress: (DownloadEntry) -> Unit
    ): DownloadEntry {
        val dir = File(context.filesDir, "downloads/${card.section.name.lowercase()}").apply { mkdirs() }
        val safeName = card.id.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".mp4"
        val targetFile = File(dir, safeName)
        val entryId = "dl_${card.id}_${stream.id}"

        if (!canDownloadStream(stream)) {
            return DownloadEntry(
                id = entryId,
                mediaId = card.id,
                title = card.title,
                section = card.section,
                sourceUrl = stream.url,
                localFilePath = targetFile.absolutePath,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                status = DownloadStatus.FAILED,
                errorMessage = "Download not permitted or unsupported stream protocol"
            )
        }

        val existingBytes = if (targetFile.exists()) targetFile.length() else 0L
        val conn = (URL(stream.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Cinator/0.2")
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        return try {
            val code = conn.responseCode
            val appending = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val contentLength = conn.contentLengthLong.coerceAtLeast(0L)
            val totalBytes = if (appending) existingBytes + contentLength else contentLength
            var downloaded = if (appending) existingBytes else 0L

            onProgress(
                DownloadEntry(
                    id = entryId,
                    mediaId = card.id,
                    title = card.title,
                    section = card.section,
                    sourceUrl = stream.url,
                    localFilePath = targetFile.absolutePath,
                    bytesDownloaded = downloaded,
                    totalBytes = totalBytes,
                    status = DownloadStatus.DOWNLOADING
                )
            )

            conn.inputStream.use { input ->
                FileOutputStream(targetFile, appending).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(
                            DownloadEntry(
                                id = entryId,
                                mediaId = card.id,
                                title = card.title,
                                section = card.section,
                                sourceUrl = stream.url,
                                localFilePath = targetFile.absolutePath,
                                bytesDownloaded = downloaded,
                                totalBytes = totalBytes,
                                status = DownloadStatus.DOWNLOADING
                            )
                        )
                    }
                }
            }

            DownloadEntry(
                id = entryId,
                mediaId = card.id,
                title = card.title,
                section = card.section,
                sourceUrl = stream.url,
                localFilePath = targetFile.absolutePath,
                bytesDownloaded = downloaded,
                totalBytes = if (totalBytes > 0L) totalBytes else downloaded,
                status = DownloadStatus.COMPLETED
            )
        } catch (e: Exception) {
            DownloadEntry(
                id = entryId,
                mediaId = card.id,
                title = card.title,
                section = card.section,
                sourceUrl = stream.url,
                localFilePath = targetFile.absolutePath,
                bytesDownloaded = if (targetFile.exists()) targetFile.length() else 0L,
                totalBytes = 0L,
                status = DownloadStatus.FAILED,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        } finally {
            conn.disconnect()
        }
    }
}
