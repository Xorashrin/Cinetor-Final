package com.cinator.app.engines.nuvio

import java.net.URI
import java.security.MessageDigest

/** A validation-only boundary, NOT a JS host, isolation implementation, or playback adapter.
 * Upstream audit c114d6fb: manifests contain name/version/scrapers; each scraper has
 * id/name/filename plus optional metadata. Stremio resources/catalogs are a different format.
 * Android isolation, signed APK provenance, rights review, quotas and E2E remain unimplemented.
 */
data class NuvioPluginManifest(val name: String, val version: String, val manifestUrl: String,
    val scrapers: List<NuvioPluginDescriptor>)
data class NuvioPluginDescriptor(val id: String, val name: String, val version: String,
    val manifestUrl: String, val repositoryVersion: String, val scriptUrl: String)

/** These are mandatory design limits, not a claim that an isolated runtime exists. */
class NuvioSandboxContract internal constructor() {
    val implementation = "VALIDATION_ONLY_NO_RUNTIME"
    val status = "BLOCKED_SECURITY"
    val directNetwork = false
    val filesystem = false
    val auth = false
    val wasm = false
    val eval = false
    val dom = false
    val settings = false
    val parentMediatedHttpsOnly = true
    val maxInputBytes = 65536
    val maxOutputBytes = 262144
    val maxScriptBytes = 1048576
    val maxMemoryBytes = 16777216
    val maxFetches = 8
    val maxFetchResponseBytes = 131072
    val maxTotalFetchBytes = 524288
    val maxResults = 16
    val deadlineMs = 5000
}
class NuvioSandboxRequest internal constructor(val requestId: String, val pluginId: String,
    val tmdbId: String, val mediaType: String, val season: Int?, val episode: Int?,
    allowedUrls: Set<String>) {
    private val urls = allowedUrls.toSet()
    internal fun sameUrls(other: Set<String>): Boolean = urls == other
}
data class NuvioParentFetchRequest(val requestId: String, val url: String)
data class NuvioParentFetchResponse(val requestId: String, val url: String, val body: String)
data class NuvioSandboxStream(val name: String, val title: String, val url: String)
data class NuvioSandboxResponse(val requestId: String, val tmdbId: String,
    val streams: List<NuvioSandboxStream>)

object NuvioPluginPolicy {
    const val MAX_INPUT_BYTES = 64 * 1024
    const val MAX_SCRIPT_BYTES = 1024 * 1024
    const val MAX_SCRAPERS = 64
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
    private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?(?:\\+[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?")
    private val digestPattern = Regex("[0-9a-f]{64}")
    // This immutable source-embedded table is covered by the eventual APK signature, NOT
    // remote manifests or caller-supplied digests. No audited/authorized embedded plugin yet.
    private val signedEmbeddedDigestAllowlist: Map<NuvioPluginDescriptor, String> = emptyMap()
    fun status(): String = "BLOCKED_SECURITY"

    fun parseManifest(raw: String, manifestUrl: String, allowedOrigins: Set<String>): NuvioPluginManifest {
        require(allowedOrigins.isNotEmpty() && allowedOrigins.size <= 16) { "Explicit origins required" }
        allowedOrigins.forEach { require(safeUrl(it, allowEmptyPath = true).rawPath.isEmpty()) }
        val base = safeUrl(manifestUrl)
        require(origin(base) in allowedOrigins && base.rawPath.endsWith("/manifest.json"))
        val obj = jsonObject(raw, MAX_INPUT_BYTES)
        fields(obj, setOf("name", "version", "scrapers", "description", "author"))
        val name = text(obj["name"], 256)
        val version = version(obj["version"])
        optionalText(obj, "description", 4096); optionalText(obj, "author", 256)
        val entries = obj["scrapers"] as? List<*> ?: invalid()
        require(entries.size in 1..MAX_SCRAPERS)
        val scrapers = entries.map { value ->
            val s = objectValue(value)
            fields(s, setOf("id", "name", "filename", "version", "description", "enabled", "hasSettings",
                "supportedTypes", "supportedPlatforms", "disabledPlatforms", "contentLanguage", "formats", "supportedFormats", "logo"))
            val id = id(s["id"])
            val scraperName = text(s["name"], 256)
            val filename = text(s["filename"], 2048)
            val script = if (filename.startsWith("https://")) safeUrl(filename) else {
                safePath(filename, absolute = false)
                safeUrl(base.resolve(filename).toString())
            }
            require(origin(script) in allowedOrigins && script.rawPath.endsWith(".js"))
            optionalText(s, "description", 4096)
            listOf("enabled", "hasSettings").forEach { key -> if (key in s) require(s[key] is Boolean) }
            listOf("supportedTypes", "supportedPlatforms", "disabledPlatforms", "contentLanguage", "formats", "supportedFormats").forEach { key ->
                if (key in s && s[key] != null) {
                    val values = s[key] as? List<*> ?: invalid(); require(values.size <= 32)
                    values.forEach { text(it, 128) }
                }
            }
            if (s["logo"] != null) require(origin(safeUrl(text(s["logo"], 2048))) in allowedOrigins)
            NuvioPluginDescriptor(id, scraperName, if ("version" in s) version(s["version"]) else version,
                manifestUrl, version, script.toString())
        }
        require(scrapers.map { it.id }.distinct().size == scrapers.size)
        require(scrapers.map { it.scriptUrl }.distinct().size == scrapers.size)
        return NuvioPluginManifest(name, version, manifestUrl, scrapers.toList())
    }

    /** Integrity comparison alone never grants execution; digest claims from JSON are not trust. */
    fun matchesExpectedSha256(bytes: ByteArray, expected: String): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_SCRIPT_BYTES || !digestPattern.matches(expected)) return false
        val expectedBytes = ByteArray(32) { expected.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), expectedBytes)
    }
    fun isEmbeddedScriptApproved(descriptor: NuvioPluginDescriptor, bytes: ByteArray): Boolean =
        signedEmbeddedDigestAllowlist[descriptor]?.let { matchesExpectedSha256(bytes, it) } ?: false

    fun sandboxContract(): NuvioSandboxContract = NuvioSandboxContract()

    /** Only the trusted parent supplies exact public URLs and media identity. There is no
     * code/settings/auth field. This is a deliberately restricted getStreams wire protocol,
     * not a drop-in implementation of the upstream privileged fetch or settings API. */
    fun validateRequest(raw: String, parentAllowedUrls: Set<String>): NuvioSandboxRequest {
        allowedUrls(parentAllowedUrls)
        val obj = jsonObject(raw, MAX_INPUT_BYTES)
        fields(obj, setOf("schemaVersion", "requestId", "pluginId", "method", "params"))
        require(obj["schemaVersion"] == 1L && obj["method"] == "getStreams")
        val requestId = id(obj["requestId"]); val pluginId = id(obj["pluginId"])
        val params = objectValue(obj["params"])
        fields(params, setOf("tmdbId", "mediaType", "season", "episode"))
        val tmdbId = text(params["tmdbId"], 16)
        require(Regex("[1-9][0-9]{0,15}").matches(tmdbId))
        val type = text(params["mediaType"], 5)
        require(type == "movie" || type == "tv")
        val season: Int?; val episode: Int?
        if (type == "tv") {
            season = positiveInt(params["season"]); episode = positiveInt(params["episode"])
        } else {
            require("season" !in params && "episode" !in params); season = null; episode = null
        }
        return NuvioSandboxRequest(requestId, pluginId, tmdbId, type, season, episode, parentAllowedUrls)
    }

    /** Parent owns monotonic counters/deadlines; these functions do NOT perform I/O. A future
     * host must reserve quotas atomically, pin public DNS/IP on connection, disable proxies,
     * cookies/auth and redirects, cap decompressed bytes before buffering, bind each reply to
     * its outstanding GET and kill the isolated service at deadline/memory exhaustion.
     * Request approval here never permits execution (status is always BLOCKED_SECURITY). */
    fun validateFetchRequest(raw: String, request: NuvioSandboxRequest, parentAllowedUrls: Set<String>,
        fetchesAlreadyUsed: Int, elapsedMs: Int): NuvioParentFetchRequest {
        require(fetchesAlreadyUsed in 0 until 8); deadline(elapsedMs)
        scope(request, parentAllowedUrls)
        val obj = jsonObject(raw, MAX_INPUT_BYTES)
        fields(obj, setOf("schemaVersion", "requestId", "method", "url"))
        envelope(obj, request); require(obj["method"] == "GET")
        return NuvioParentFetchRequest(request.requestId, allowedUrl(obj["url"], parentAllowedUrls))
    }

    fun validateFetchResponse(raw: String, request: NuvioSandboxRequest, parentAllowedUrls: Set<String>,
        bytesAlreadyReceived: Int, elapsedMs: Int): NuvioParentFetchResponse {
        deadline(elapsedMs); scope(request, parentAllowedUrls)
        require(bytesAlreadyReceived in 0..524288)
        val obj = jsonObject(raw, 131072)
        require(raw.toByteArray(Charsets.UTF_8).size <= 524288 - bytesAlreadyReceived)
        fields(obj, setOf("schemaVersion", "requestId", "url", "status", "body"))
        envelope(obj, request); require(obj["status"] == 200L) // redirects/errors never cross IPC
        val body = obj["body"] as? String ?: invalid()
        require(body.toByteArray(Charsets.UTF_8).size <= 131072)
        return NuvioParentFetchResponse(request.requestId, allowedUrl(obj["url"], parentAllowedUrls), body)
    }

    /** Output is untrusted candidate metadata, never a playable/authorized StreamSource. */
    fun validateResponse(raw: String, request: NuvioSandboxRequest, parentAllowedUrls: Set<String>,
        elapsedMs: Int): NuvioSandboxResponse {
        deadline(elapsedMs); scope(request, parentAllowedUrls)
        val obj = jsonObject(raw, 262144)
        fields(obj, setOf("schemaVersion", "requestId", "tmdbId", "season", "episode", "streams"))
        envelope(obj, request); require(obj["tmdbId"] == request.tmdbId)
        if (request.mediaType == "tv") {
            require(positiveInt(obj["season"]) == request.season && positiveInt(obj["episode"]) == request.episode)
        } else require("season" !in obj && "episode" !in obj)
        val streams = obj["streams"] as? List<*> ?: invalid(); require(streams.size <= 16)
        val results = streams.map {
            val stream = objectValue(it); fields(stream, setOf("name", "title", "url"))
            NuvioSandboxStream(text(stream["name"], 128), text(stream["title"], 256), allowedUrl(stream["url"], parentAllowedUrls))
        }
        require(results.map { it.url }.distinct().size == results.size)
        return NuvioSandboxResponse(request.requestId, request.tmdbId, results)
    }
    private fun positiveInt(value: Any?): Int {
        require(value is Long && value in 1..100000); return value.toInt()
    }
    private fun envelope(obj: Map<String, Any?>, request: NuvioSandboxRequest) {
        require(obj["schemaVersion"] == 1L && obj["requestId"] == request.requestId)
    }
    private fun deadline(elapsedMs: Int) { require(elapsedMs in 0 until 5000) }
    private fun allowedUrls(urls: Set<String>) { require(urls.size <= 16); urls.forEach { safeUrl(it) } }
    private fun scope(request: NuvioSandboxRequest, urls: Set<String>) {
        allowedUrls(urls); require(request.sameUrls(urls)) { "Parent URL grant cannot change mid-request" }
    }
    private fun allowedUrl(value: Any?, urls: Set<String>): String = text(value, 2048).also {
        safeUrl(it); require(it in urls)
    }

    private fun version(value: Any?): String = text(value, 128).also { require(versionPattern.matches(it)) }
    private fun id(value: Any?): String = text(value, 128).also { require(idPattern.matches(it) && ".." !in it) }
    private fun text(value: Any?, limit: Int): String = (value as? String ?: invalid()).also {
        require(it.isNotBlank() && it.length <= limit && it.none { c -> c.isISOControl() || c.isSurrogate() })
    }
    private fun optionalText(obj: Map<String, Any?>, key: String, limit: Int) {
        if (key in obj && obj[key] != null) text(obj[key], limit)
    }
    private fun fields(obj: Map<String, Any?>, allowed: Set<String>) { require(obj.keys.all { it in allowed }) }
    private fun objectValue(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String })
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private fun jsonObject(raw: String, limit: Int): Map<String, Any?> {
        require(raw.length <= limit && raw.toByteArray(Charsets.UTF_8).size <= limit)
        return objectValue(StrictJson(raw).parse())
    }
    private fun origin(uri: URI): String = "https://${uri.host}"
    private fun safeUrl(url: String, allowEmptyPath: Boolean = false): URI {
        require(url.length <= 2048 && url.all { it.code in 33..126 } && '%' !in url && '\\' !in url)
        val uri = try { URI(url) } catch (_: Exception) { invalid() }
        val host = uri.host ?: invalid()
        require(uri.scheme == "https" && !uri.isOpaque && uri.rawUserInfo == null && uri.port == -1 &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawAuthority == host)
        require(host == host.lowercase() && host.length <= 253 && host.contains('.') &&
            !host.endsWith('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.endsWith(".internal") && !host.endsWith(".test.local") &&
            !host.all { it.isDigit() || it == '.' } && host.split('.').all {
                Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it)
            })
        if (allowEmptyPath && uri.rawPath.isEmpty()) return uri
        safePath(uri.rawPath, absolute = true)
        return uri
    }
    private fun safePath(path: String, absolute: Boolean) {
        require(path.length in 1..2048 && path.startsWith('/') == absolute)
        val relative = if (absolute) path.drop(1) else path
        require(relative.isNotEmpty() && relative.split('/').all {
            it != "." && it != ".." && Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*").matches(it)
        })
    }
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid Nuvio policy data")

    /** Small strict data parser: no lenient JS syntax, duplicate keys or trailing payloads.
     * Bound recursion before allocating nested trees; never evaluates strings or plugin code. */
    private class StrictJson(private val raw: String) {
        private var pos = 0
        fun parse(): Any? { val result = value(0); whitespace(); require(pos == raw.length); return result }
        private fun whitespace() { while (pos < raw.length && raw[pos] in " \t\r\n") pos++ }
        private fun value(depth: Int): Any? {
            require(depth <= 16); whitespace(); require(pos < raw.length)
            return when (raw[pos]) {
                '{' -> {
                    pos++; whitespace(); val out = linkedMapOf<String, Any?>()
                    if (take('}')) out else {
                        do { whitespace(); require(pos < raw.length && raw[pos] == '"'); val key = string()
                            require(key !in out); whitespace(); require(take(':')); out[key] = value(depth + 1)
                            whitespace(); if (take('}')) return out
                        } while (take(','))
                        invalid()
                    }
                }
                '[' -> {
                    pos++; whitespace(); val out = mutableListOf<Any?>()
                    if (take(']')) out else {
                        do { out += value(depth + 1); whitespace(); if (take(']')) return out } while (take(','))
                        invalid()
                    }
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = pos
                    if (take('-')) require(pos < raw.length)
                    require(pos < raw.length && raw[pos].isDigit())
                    if (take('0')) require(pos == raw.length || !raw[pos].isDigit())
                    else while (pos < raw.length && raw[pos] in '0'..'9') pos++
                    // Contract only uses integral quota/episode values. Other numeric forms denied.
                    raw.substring(start, pos).toLongOrNull() ?: invalid()
                }
            }
        }
        private fun take(c: Char): Boolean = if (pos < raw.length && raw[pos] == c) { pos++; true } else false
        private fun literal(word: String, value: Any?): Any? {
            require(raw.startsWith(word, pos)); pos += word.length; return value
        }
        private fun string(): String {
            require(take('"')); val result = StringBuilder()
            while (pos < raw.length) {
                val c = raw[pos++]
                if (c == '"') return result.toString()
                require(c.code >= 32 && !c.isSurrogate())
                if (c != '\\') result.append(c) else {
                    require(pos < raw.length)
                    result.append(when (val escaped = raw[pos++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> { require(pos + 4 <= raw.length)
                            val hex = raw.substring(pos, pos + 4); require(hex.all { it in "0123456789abcdefABCDEF" })
                            pos += 4; hex.toInt(16).toChar().also { require(!it.isSurrogate()) }
                        }
                        else -> invalid()
                    })
                }
            }
            invalid()
        }
    }
}
