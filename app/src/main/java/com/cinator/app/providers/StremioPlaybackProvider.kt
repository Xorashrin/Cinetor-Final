package com.cinator.app.providers

import com.cinator.app.data.TextHttpClient
import com.cinator.app.data.UrlPolicyValidator
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.URI
import java.util.Locale

/**
 * Stremio JSON protocol, not a JavaScript/plugin runtime. The reviewed bundled registry
 * supplies the endpoint and section; manifests cannot install providers or grant rights.
 * Production transport must be BoundedHttpClient (including DNS/redirect enforcement).
 * A direct URL is an addon declaration, not evidence of media reachability or licensing.
 */
class StremioPlaybackProvider(
    info: ProviderInfo,
    private val manifestUrl: String,
    private val http: TextHttpClient,
    private val auditedDownloadPermission: Boolean = false
) : PlaybackProvider {
    private val configured = info.copy(
        enabled = info.enabled && info.section != FeedSection.ADULT,
        capabilities = info.capabilities.copy(download = info.capabilities.download && auditedDownloadPermission)
    )
    private val baseUrl: String
    private var discovered: Manifest? = null
    private val detailCache = mutableMapOf<Pair<String, String>, ProviderDetails>()

    override val info: ProviderInfo
        @Synchronized get() {
            val caps = configured.capabilities
            val manifest = discovered
            val active = configured.enabled
            val catalog = active && (manifest == null || manifest.catalogs.any { manifest.supports("catalog", it.type, it.id) })
            val meta = active && (manifest == null || manifest.resources.any { it.name == "meta" })
            val stream = active && (manifest == null || manifest.resources.any { it.name == "stream" })
            return configured.copy(capabilities = caps.copy(
                search = caps.search && catalog, details = caps.details && meta,
                episodes = caps.episodes && meta, playback = caps.playback && stream,
                download = caps.download && caps.playback && stream))
        }

    init {
        validId(info.id)
        require(info.name.isNotBlank()) { "Missing provider name" }
        safeUrl(manifestUrl)
        val uri = URI(manifestUrl)
        require(uri.rawQuery == null && uri.rawPath.endsWith("/manifest.json")) { "Expected public manifest endpoint" }
        require(uri.normalize().rawPath == uri.rawPath) { "Invalid manifest path" }
        baseUrl = manifestUrl.removeSuffix("/manifest.json")
    }

    @Synchronized override fun catalog(query: String): List<MediaCard> {
        interrupted()
        if (!configured.enabled || !configured.capabilities.search) return emptyList()
        require(query.length <= 1024 && query.none { it.isISOControl() }) { "Invalid search query" }
        val manifest = manifest()
        val result = linkedMapOf<Pair<String, String>, MediaCard>()
        for (catalog in manifest.catalogs) {
            if (!manifest.supports("catalog", catalog.type, catalog.id)) continue
            val searching = query.isNotEmpty()
            val provided = if (searching) setOf("search") else emptySet()
            if (!catalog.required.all { it in provided } || (searching && "search" !in catalog.supported)) continue
            val suffix = if (searching) "/search=${encode(query)}" else ""
            val metas = json(route("catalog", catalog.type, catalog.id, suffix)).getJSONArray("metas")
            bounded(metas, 500)
            for (i in 0 until metas.length()) {
                interrupted()
                val meta = metas.getJSONObject(i)
                require(text(meta, "type") == catalog.type) { "Catalog type mismatch" }
                card(meta, catalog.type)?.let { result[catalog.type to it.id] = it }
            }
        }
        return result.values.toList()
    }

    @Synchronized override fun details(card: MediaCard): ProviderDetails {
        interrupted()
        val type = validateCard(card)
        if (!configured.enabled || !configured.capabilities.details) return ProviderDetails(card.copy(streams = emptyList()))
        val manifest = manifest()
        if (!manifest.supports("meta", type, card.id)) return ProviderDetails(card.copy(streams = emptyList()))
        val key = type to card.id
        detailCache[key]?.let { return it }
        val meta = json(route("meta", type, card.id)).getJSONObject("meta")
        require(text(meta, "id") == card.id && text(meta, "type") == type) { "Metadata identity mismatch" }
        val parsed = requireNotNull(card(meta, type)) { "Metadata outside provider section" }
        val episodes = mutableListOf<EpisodeRef>()
        if (type == "series" && configured.capabilities.episodes) {
            val videos = array(meta, "videos")
            bounded(videos, 10000)
            val seen = mutableSetOf<String>()
            for (i in 0 until videos.length()) {
                interrupted()
                val video = videos.getJSONObject(i)
                val id = validId(text(video, "id"))
                require(seen.add(id)) { "Duplicate episode identity" }
                val title = optionalText(video, "title") ?: optionalText(video, "name") ?: id
                require(title.isNotBlank()) { "Missing episode title" }
                episodes += EpisodeRef(id, title, optionalInt(video, "season"), optionalInt(video, "episode"), id)
            }
        }
        return ProviderDetails(parsed, episodes.toList()).also {
            if (detailCache.size >= 128) detailCache.clear()
            detailCache[key] = it
        }
    }

    @Synchronized override fun resolve(request: ResolveRequest): List<StreamSource> {
        interrupted()
        val type = validateCard(request.card)
        if (!configured.enabled || !configured.capabilities.playback) return emptyList()
        val manifest = manifest()
        val episode = request.episode
        if (type == "series" && episode == null) return emptyList()
        require(episode == null || type == "series") { "Movie cannot select episode" }
        val contentId = episode?.contentId ?: request.card.id
        validId(contentId)
        require(episode == null || episode.id == contentId) { "Episode identity mismatch" }
        // Check declarations before metadata, especially for metadata-only addons.
        if (!manifest.supports("stream", type, contentId)) return emptyList()
        if (episode != null) {
            require(details(request.card).episodes.any { it == episode }) { "Episode does not belong to selected title" }
        }
        val streams = json(route("stream", type, contentId)).getJSONArray("streams")
        bounded(streams, 256)
        val result = linkedMapOf<String, StreamSource>()
        for (i in 0 until streams.length()) {
            interrupted()
            val entry = streams.getJSONObject(i)
            if (unsupported(entry)) continue
            if (entry.has("contentId") && optionalText(entry, "contentId") != contentId) continue
            val url = optionalText(entry, "url") ?: continue
            if (!isSafeUrl(url)) continue
            val path = URI(url).path.lowercase(Locale.ROOT)
            val protocol = when {
                path.endsWith(".mp4") -> StreamProtocol.MP4
                path.endsWith(".m3u8") -> StreamProtocol.HLS_M3U8
                else -> continue
            }
            result.putIfAbsent(url, StreamSource(
                id = "${configured.id}:$contentId:$i", label = optionalText(entry, "name") ?: configured.name,
                url = url, protocol = protocol, quality = "Unspecified",
                isDownloadable = auditedDownloadPermission && configured.capabilities.download,
                providerId = configured.id, contentId = contentId, section = configured.section,
                isLive = false, headers = emptyMap()
            ))
        }
        return result.values.toList()
    }

    private fun validateCard(card: MediaCard): String {
        require(card.providerId == configured.id && card.section == configured.section) { "Provider or section mismatch" }
        validId(card.id)
        require(card.contentId == card.id) { "Content identity mismatch" }
        return when (card.kind) {
            MediaKind.MOVIE -> "movie"
            MediaKind.SERIES, MediaKind.ANIME, MediaKind.K_DRAMA -> "series"
            else -> throw IllegalArgumentException("Unsupported media kind")
        }
    }

    private fun card(meta: JSONObject, type: String): MediaCard? {
        val id = validId(text(meta, "id"))
        val title = text(meta, "name")
        val genres = strings(array(meta, "genres"))
        val country = when (val raw = meta.opt("country")) {
            null, JSONObject.NULL -> emptyList()
            is String -> raw.split(',', ';')
            is JSONArray -> strings(raw)
            else -> throw IllegalArgumentException("Invalid country metadata")
        }.map { it.trim().lowercase(Locale.ROOT) }
        val tags = genres.map { it.lowercase(Locale.ROOT) }
        if (meta.optBoolean("adult", false) || meta.optBoolean("isAdult", false)) return null
        if (tags.any { it in setOf("adult", "xxx", "porn", "pornography", "erotic", "hentai") }) return null
        val animation = "animation" in tags
        val anime = "anime" in tags || (animation && country.any { it in setOf("jp", "japan") })
        val koreanDrama = type == "series" && !animation && "drama" in tags && country.any {
            it in setOf("kr", "south korea", "korea", "korea, republic of", "republic of korea")
        }
        val allowed = when (configured.section) {
            FeedSection.HOME -> !anime && !koreanDrama && (type != "series" || (country.any { it.isNotBlank() } && tags.isNotEmpty()))
            FeedSection.ANIME -> anime && type == "series"
            FeedSection.KDRAMA -> koreanDrama && !anime
            else -> false
        }
        if (!allowed) return null
        // Preserve protocol type in kind; section is authoritative, never addon-supplied.
        return MediaCard(id = id, title = title, subtitle = configured.name,
            synopsis = optionalText(meta, "description") ?: "", section = configured.section,
            providerId = configured.id, providerName = configured.name,
            year = optionalText(meta, "releaseInfo"), rating = optionalText(meta, "imdbRating"), genres = genres,
            posterUrl = optionalText(meta, "poster")?.takeIf(::isSafeUrl),
            backdropUrl = optionalText(meta, "background")?.takeIf(::isSafeUrl),
            kind = when {
                configured.section == FeedSection.ANIME && type == "series" -> MediaKind.ANIME
                configured.section == FeedSection.KDRAMA && type == "series" -> MediaKind.K_DRAMA
                type == "movie" -> MediaKind.MOVIE
                else -> MediaKind.SERIES
            }, contentId = id,
            externalUrl = null, streams = emptyList())
    }

    private fun manifest(): Manifest {
        discovered?.let { return it }
        val root = json(manifestUrl)
        validId(text(root, "id")); text(root, "version"); text(root, "name")
        val hints = if (root.has("behaviorHints")) root.getJSONObject("behaviorHints") else null
        if (hints?.has("configurationRequired") == true) {
            require(hints.get("configurationRequired") is Boolean) { "Invalid configuration required flag" }
        }
        require(hints?.optBoolean("configurationRequired", false) != true) { "Addon requires private configuration" }
        val types = strings(root.getJSONArray("types")).toSet()
        val prefixes = if (root.has("idPrefixes")) strings(root.getJSONArray("idPrefixes")) else null
        val resources = root.getJSONArray("resources")
        bounded(resources, 64)
        val declarations = (0 until resources.length()).map { index ->
            when (val resource = resources.get(index)) {
                is String -> Resource(validId(resource), types, if (resource == "catalog") null else prefixes)
                is JSONObject -> {
                    val name = validId(text(resource, "name"))
                    val declaredTypes = if (resource.has("types")) strings(resource.getJSONArray("types")).toSet() else types
                    require(declaredTypes.all { it in types }) { "Resource advertises undeclared type" }
                    Resource(name, declaredTypes, if (resource.has("idPrefixes")) strings(resource.getJSONArray("idPrefixes")) else if (name == "catalog") null else prefixes)
                }
                else -> throw IllegalArgumentException("Invalid manifest resource")
            }
        }
        val catalogs = array(root, "catalogs")
        bounded(catalogs, 32)
        val seen = mutableSetOf<Pair<String, String>>()
        val catalogDeclarations = (0 until catalogs.length()).map { index ->
            val catalog = catalogs.getJSONObject(index)
            val type = validId(text(catalog, "type"))
            val id = validId(text(catalog, "id"))
            require(type in types && seen.add(type to id)) { "Invalid catalog declaration" }
            val supported = strings(array(catalog, "extraSupported")).toMutableSet()
            val required = strings(array(catalog, "extraRequired")).toMutableSet()
            val extra = array(catalog, "extra")
            bounded(extra, 32)
            for (i in 0 until extra.length()) {
                val item = extra.getJSONObject(i)
                val name = validId(text(item, "name"))
                supported += name
                if (item.has("isRequired")) require(item.get("isRequired") is Boolean) { "Invalid required flag" }
                if (item.optBoolean("isRequired", false)) required += name
            }
            require(required.all { it in supported }) { "Required extra not supported" }
            Catalog(type, id, supported, required)
        }
        return Manifest(declarations, catalogDeclarations).also { discovered = it }
    }

    private data class Resource(val name: String, val types: Set<String>, val prefixes: List<String>?)
    private data class Catalog(val type: String, val id: String, val supported: Set<String>, val required: Set<String>)
    private data class Manifest(val resources: List<Resource>, val catalogs: List<Catalog>) {
        fun supports(name: String, type: String, id: String): Boolean = type in setOf("movie", "series") && resources.any {
            it.name == name && type in it.types && (it.prefixes == null || it.prefixes.any(id::startsWith))
        }
    }

    private fun route(resource: String, type: String, id: String, suffix: String = ""): String {
        require(resource in setOf("catalog", "meta", "stream") && type in setOf("movie", "series")) { "Unsupported route" }
        return "$baseUrl/$resource/${encode(type)}/${encode(validId(id))}$suffix.json".also(::safeUrl)
    }
    private fun json(url: String): JSONObject {
        interrupted()
        safeUrl(url)
        val body = http.get(url)
        interrupted()
        require(body.length <= 2 * 1024 * 1024) { "JSON response too large" }
        return try { JSONObject(body) } catch (_: org.json.JSONException) { throw IllegalArgumentException("Malformed addon JSON") }
    }
    private fun isSafeUrl(url: String): Boolean = UrlPolicyValidator.isSafeHttpsUrl(url) && URI(url).rawFragment == null
    private fun safeUrl(url: String) { require(isSafeUrl(url)) { "Unsafe HTTPS target" } }
    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Provider request interrupted") }
    private fun validId(value: String): String {
        require(value.isNotBlank() && value == value.trim() && value.length <= 1024 && value.none { it.isISOControl() } &&
            Charsets.UTF_8.newEncoder().canEncode(value)) { "Invalid protocol identifier" }
        return value
    }
    private fun encode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 255
            if (b in 65..90 || b in 97..122 || b in 48..57 || b == 45 || b == 95 || b == 126) append(b.toChar())
            else { append('%'); append("0123456789ABCDEF"[b ushr 4]); append("0123456789ABCDEF"[b and 15]) }
        }
    }
    private fun text(obj: JSONObject, key: String): String = requireNotNull(optionalText(obj, key)?.takeIf { it.isNotBlank() }) { "Missing text field" }
    private fun optionalText(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.get(key) as? String ?: throw IllegalArgumentException("Invalid text field")
    }
    private fun optionalInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.get(key)
        require(value is Int && value >= 0) { "Invalid episode number" }
        return value
    }
    private fun array(obj: JSONObject, key: String): JSONArray = if (obj.has(key)) obj.getJSONArray(key) else JSONArray()
    private fun bounded(array: JSONArray, max: Int) { require(array.length() <= max) { "Addon item limit exceeded" } }
    private fun strings(array: JSONArray): List<String> {
        bounded(array, 256)
        return (0 until array.length()).map { validId(array.get(it) as? String ?: throw IllegalArgumentException("Invalid string array")) }
    }
    private fun unsupportedArray(array: JSONArray): Boolean = (0 until array.length()).any {
        when (val item = array.get(it)) {
            is JSONObject -> unsupported(item)
            is JSONArray -> unsupportedArray(item)
            else -> false
        }
    }
    private fun unsupported(obj: JSONObject): Boolean {
        val forbidden = setOf("infohash", "externalurl", "ytid", "torrent", "webtorrent", "magnet", "sources", "fileidx", "headers", "proxyheaders", "drm", "drmtype", "drmlicenseurl", "licenseurl", "licensekey", "widevine", "clearkey")
        for (key in obj.keys()) {
            if (key.lowercase(Locale.ROOT) in forbidden) return true
            val value = obj.opt(key)
            if (value is JSONObject && unsupported(value)) return true
            if (value is JSONArray && unsupportedArray(value)) return true
        }
        return obj.optBoolean("notWebReady", false)
    }
}
