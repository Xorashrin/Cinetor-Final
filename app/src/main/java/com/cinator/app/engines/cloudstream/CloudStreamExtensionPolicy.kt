package com.cinator.app.engines.cloudstream

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class CloudStreamRepositoryDescriptor(
    val name: String,
    val description: String,
    val manifestVersion: Int,
    val pluginLists: List<String>
)

data class CloudStreamPluginDescriptor(
    val url: String,
    val status: Int,
    val version: Int,
    val apiVersion: Int,
    val name: String,
    val internalName: String,
    val authors: List<String>,
    val language: String?,
    val tvTypes: List<String>
)

data class CloudStreamEmbeddedDigestPin(
    val internalName: String,
    val url: String,
    val version: Int,
    val sha256: String
)

data class CloudStreamArchiveEntry(val path: String, val uncompressedBytes: Long)

data class CloudStreamEmbeddedInspection(
    val sha256: String,
    val entries: List<CloudStreamArchiveEntry>,
    val manifestName: String,
    val pluginClassName: String
) {
    val runtimeStatus: String get() = "BLOCKED_SECURITY"
    val executionAllowed: Boolean get() = false
}

/**
 * Metadata only. No transport, filesystem writes, class loading or execution.
 *
 * Schema reference: upstream RepositoryManager.Repository/SitePlugin and
 * BasePlugin.Manifest. This is independently implemented validation, not a host.
 * URL checks are syntactic only: they neither resolve DNS nor authorize network I/O.
 * Pins must come from reviewed build-embedded configuration, NEVER repository
 * fileHash fields. A digest proves byte identity, not signatures, rights or safety.
 * Successful inspection admits metadata inventory only; DEX stays BLOCKED_SECURITY.
 * Caller supplies already-embedded bytes; no download/install API is exposed.
 */
class CloudStreamExtensionPolicy(
    embeddedAllowlist: Set<CloudStreamEmbeddedDigestPin> = emptySet()
) {
    private val embeddedAllowlist = embeddedAllowlist.toSet()

    init {
        this.embeddedAllowlist.forEach { pin ->
            text(pin.internalName, 256)
            https(text(pin.url, 2048), ".cs3")
            require(pin.version >= 0 && pin.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid embedded SHA-256 pin" }
        }
        require(embeddedAllowlist.map { Triple(it.internalName, it.url, it.version) }.distinct().size == embeddedAllowlist.size) {
            "Duplicate embedded identity pin"
        }
    }

    val runtimeStatus: String get() = "BLOCKED_SECURITY"

    fun inspectEmbedded(plugin: CloudStreamPluginDescriptor, archive: ByteArray): CloudStreamEmbeddedInspection {
        require(archive.size in 22..MAX_ARCHIVE_BYTES) { "Archive exceeds bounded input" }
        val snapshot = archive.copyOf()
        require(hasExactEndOfCentralDirectory(snapshot)) { "Malformed or trailing ZIP data" }
        val digest = MessageDigest.getInstance("SHA-256").digest(snapshot)
            .joinToString("") { "%02x".format(it) }
        val pin = embeddedAllowlist.singleOrNull {
            it.internalName == plugin.internalName && it.url == plugin.url && it.version == plugin.version
        } ?: error("Extension is not embedded and identity-pinned")
        require(MessageDigest.isEqual(digest.toByteArray(), pin.sha256.toByteArray())) { "Digest mismatch" }

        val entries = mutableListOf<CloudStreamArchiveEntry>()
        val paths = mutableSetOf<String>()
        var totalExpanded = 0L
        var manifestBytes: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(snapshot)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entries.size < MAX_ENTRIES) { "Too many ZIP entries" }
                val path = safeArchivePath(entry.name)
                require(!entry.isDirectory && paths.add(path.lowercase(java.util.Locale.ROOT))) { "Directory or duplicate ZIP entry" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var entryBytes = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entryBytes += count
                    totalExpanded += count
                    require(entryBytes <= MAX_ENTRY_BYTES && totalExpanded <= MAX_EXPANDED_BYTES) { "ZIP expansion limit" }
                    if (path == MANIFEST_PATH) {
                        require(entryBytes <= MAX_MANIFEST_BYTES) { "Manifest too large" }
                        output.write(buffer, 0, count)
                    }
                }
                val compressed = entry.compressedSize
                require(compressed <= 0 || entryBytes <= compressed * MAX_COMPRESSION_RATIO + 1024) { "Suspicious compression ratio" }
                if (path == MANIFEST_PATH) {
                    require(manifestBytes == null) { "Duplicate manifest" }
                    manifestBytes = output.toByteArray()
                }
                entries += CloudStreamArchiveEntry(path, entryBytes)
                zip.closeEntry()
            }
        }
        val manifestRaw = manifestBytes ?: error("Missing manifest.json")
        require(entries.isNotEmpty()) { "Empty archive" }
        val manifestText = Charsets.UTF_8.newDecoder()
            .decode(java.nio.ByteBuffer.wrap(manifestRaw)).toString()
        checkJsonDepth(manifestText)
        val manifest = JSONObject(manifestText)
        val manifestName = text(manifest.get("name"), 256)
        val manifestVersion = exactInt(manifest, "version", 0..Int.MAX_VALUE)
        val pluginClassName = text(manifest.get("pluginClassName"), 512)
        require(manifestName == plugin.name && manifestVersion == plugin.version) { "Manifest/descriptor mismatch" }
        require(pluginClassName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) {
            "Invalid plugin class metadata"
        }
        return CloudStreamEmbeddedInspection(digest, entries.toList(), manifestName, pluginClassName)
    }

    fun parsePlugins(raw: String): List<CloudStreamPluginDescriptor> {
        require(raw.length <= 512 * 1024) { "Plugin list too large" }
        checkJsonDepth(raw)
        val array = org.json.JSONArray(raw)
        require(array.length() in 1..1024) { "Invalid plugin count" }
        val result = (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val url = https(text(obj.get("url"), 2048), ".cs3")
            val status = exactInt(obj, "status", 0..3)
            val version = exactInt(obj, "version", 0..Int.MAX_VALUE)
            val apiVersion = exactInt(obj, "apiVersion", 1..1)
            val name = text(obj.get("name"), 256)
            val internalName = text(obj.get("internalName"), 256)
            val authors = stringList(obj, "authors", 32, 128)
            val language = if (obj.has("language") && obj.get("language") != JSONObject.NULL)
                text(obj.get("language"), 35) else null
            val tvTypes = if (obj.has("tvTypes") && obj.get("tvTypes") != JSONObject.NULL)
                stringList(obj, "tvTypes", 64, 64) else emptyList()
            CloudStreamPluginDescriptor(url, status, version, apiVersion, name, internalName, authors, language, tvTypes)
        }
        require(result.map { it.internalName }.distinct().size == result.size) { "Duplicate internalName" }
        require(result.map { it.url }.distinct().size == result.size) { "Duplicate plugin URL" }
        return result
    }

    fun parseRepository(raw: String): CloudStreamRepositoryDescriptor {
        require(raw.length <= 65536) { "Repository too large" }
        checkJsonDepth(raw)
        val obj = JSONObject(raw)
        val name = text(obj.get("name"), 256)
        val description = if (obj.has("description")) text(obj.get("description"), 4096, true) else ""
        val version = obj.get("manifestVersion")
        require(version is Int && version == 1) { "Unsupported repository manifest version" }
        val lists = obj.getJSONArray("pluginLists")
        require(lists.length() in 1..32) { "Invalid plugin list count" }
        val urls = (0 until lists.length()).map { https(text(lists.get(it), 2048)) }
        require(urls.distinct().size == urls.size) { "Duplicate plugin list" }
        return CloudStreamRepositoryDescriptor(name, description, version, urls)
    }

    private fun text(value: Any, max: Int, empty: Boolean = false): String {
        require(value is String && value.length <= max && (empty || value.isNotBlank()) &&
            value == value.trim() && value.none { it.isISOControl() }) { "Invalid text field" }
        return value
    }

    private fun checkJsonDepth(raw: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "Metadata nesting limit" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Unbalanced JSON" } }
            }
        }
        require(!quoted && depth == 0) { "Incomplete JSON" }
    }

    private fun exactInt(obj: JSONObject, key: String, range: IntRange): Int {
        val value = obj.get(key)
        require(value is Int && value in range) { "Invalid $key" }
        return value
    }

    private fun stringList(obj: JSONObject, key: String, maxCount: Int, maxText: Int): List<String> {
        val array = obj.getJSONArray(key)
        require(array.length() <= maxCount) { "Too many $key" }
        val values = (0 until array.length()).map { text(array.get(it), maxText) }
        require(values.distinct().size == values.size) { "Duplicate $key" }
        return values
    }

    private fun https(raw: String, requiredSuffix: String? = null): String {
        val uri = URI(raw)
        val host = uri.host ?: error("Missing host")
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
            uri.rawQuery == null && uri.rawFragment == null) { "HTTPS descriptor URL required" }
        require(host.length <= 253 && host.split('.').size >= 2 &&
            host.split('.').all { it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) } &&
            host.last().isLetter() && host.substringAfterLast('.').lowercase() !in
            setOf("local", "localhost", "internal", "lan", "home", "onion") && !host.endsWith(".home.arpa", true)) {
            "Non-public DNS host"
        }
        val path = uri.rawPath
        require(requiredSuffix == null || path.endsWith(requiredSuffix)) { "Unexpected extension suffix" }
        require(path.length in 1..1024 && path.startsWith('/') &&
            path.matches(Regex("/[A-Za-z0-9._~!$&'()+,;=@/-]*")) && path.split('/').size <= 64 &&
            path.drop(1).split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe bounded path" }
        return raw
    }

    private fun safeArchivePath(raw: String): String {
        require(raw.length in 1..512 && raw.none { it.isISOControl() } &&
            !raw.startsWith('/') && !raw.contains('\\') && !raw.contains('%') && !raw.contains(':')) {
            "Unsafe ZIP path"
        }
        val segments = raw.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "ZIP traversal" }
        return raw
    }

    private fun hasExactEndOfCentralDirectory(bytes: ByteArray): Boolean {
        val first = (bytes.size - 65557).coerceAtLeast(0)
        for (i in bytes.size - 22 downTo first) {
            if (bytes[i].toInt() and 0xff == 0x50 && bytes[i + 1].toInt() and 0xff == 0x4b &&
                bytes[i + 2].toInt() and 0xff == 0x05 && bytes[i + 3].toInt() and 0xff == 0x06) {
                val commentLength = (bytes[i + 20].toInt() and 0xff) or ((bytes[i + 21].toInt() and 0xff) shl 8)
                return i + 22 + commentLength == bytes.size
            }
        }
        return false
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 2 * 1024 * 1024L
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024L
        const val MAX_MANIFEST_BYTES = 64 * 1024L
        const val MAX_ENTRIES = 128
        const val MAX_COMPRESSION_RATIO = 100L
        const val MANIFEST_PATH = "manifest.json"
    }
}
