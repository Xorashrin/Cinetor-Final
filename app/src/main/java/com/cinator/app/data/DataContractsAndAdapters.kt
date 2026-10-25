package com.cinator.app.data

import com.cinator.app.domain.BdixHostStatus
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.ProviderRegistry
import com.cinator.app.domain.SectionStoreState
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

object UrlPolicyValidator {
    private val encodedControl = Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE)

    /** Syntactic public-target policy only; never performs DNS resolution. */
    fun isSafeHttpsUrl(rawUrl: String): Boolean {
        if (rawUrl.isBlank() || rawUrl != rawUrl.trim() ||
            rawUrl.any { it.isISOControl() } || encodedControl.containsMatchIn(rawUrl)) return false
        return try {
            val uri = URI(rawUrl)
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
                (uri.port != -1 && uri.port != 443)) return false
            when {
                host.startsWith("[") -> isPublicIpv6(host.removeSurrounding("[", "]"))
                host.all { it.isDigit() || it == '.' } -> isPublicIpv4(host)
                else -> {
                    val labels = host.split('.')
                    labels.size >= 2 && labels.all {
                        it.length in 1..63 && it.first().isLetterOrDigit() &&
                            it.last().isLetterOrDigit() && it.all { c -> c in 'a'..'z' || c.isDigit() || c == '-' }
                    } && labels.last().any { it in 'a'..'z' } &&
                        labels.last() !in setOf("localhost", "local", "internal", "lan", "home", "onion") &&
                        !host.endsWith(".home.arpa")
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPublicIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b, c) = octets
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && (b == 168 || b == 0 || (b == 88 && c == 99))) ||
            (a == 198 && (b in 18..19 || (b == 51 && c == 100))) ||
            (a == 203 && b == 0 && c == 113))
    }

    private fun isPublicIpv6(host: String): Boolean {
        // URI has already checked literal syntax. Only global unicast is accepted;
        // mapped IPv4, scope IDs, transition/tunnel and special ranges fail closed.
        if (host.contains('%') || host.contains('.') || host.startsWith(':')) return false
        val groups = host.split(':')
        val first = groups.first().toIntOrNull(16) ?: return false
        val second = groups.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull(16) ?: 0
        if (first !in 0x2000..0x3fff || first == 0x2002 || first == 0x3fff) return false
        if (first == 0x2001 && (second < 0x0200 || second == 0x0db8)) return false
        return true
    }

    fun isDeclaredBdixEndpoint(rawUrl: String): Boolean {
        if (!isSafeHttpsUrl(rawUrl)) return false
        val allowed = ProviderRegistry.bdixDeclaredHosts.map { it.second.lowercase() }.toSet()
        return allowed.contains(rawUrl.trim().trimEnd('/').lowercase())
    }
}

data class M3uChannelEntry(
    val id: String,
    val title: String,
    val groupTitle: String,
    val logoUrl: String?,
    val streamUrl: String
)

object M3uPlaylistParser {
    private val attrRegex = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")

    fun parse(m3uContent: String, maxChannels: Int = 60): List<M3uChannelEntry> {
        val lines = m3uContent.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val channels = mutableListOf<M3uChannelEntry>()
        var pendingExtInf: String? = null

        for (line in lines) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pendingExtInf = line
            } else if (!line.startsWith("#") && pendingExtInf != null) {
                val ext = pendingExtInf
                pendingExtInf = null
                if (!UrlPolicyValidator.isSafeHttpsUrl(line)) continue

                val attrs = mutableMapOf<String, String>()
                for (match in attrRegex.findAll(ext)) {
                    attrs[match.groupValues[1].lowercase()] = match.groupValues[2]
                }
                val titlePart = ext.substringAfterLast(",", "Live Channel").trim()
                    .ifEmpty { "Live Channel" }
                val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "Public TV"
                val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: "ch_${channels.size + 1}"
                val logo = attrs["tvg-logo"]?.takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) }

                channels.add(
                    M3uChannelEntry(
                        id = "iptv_${tvgId}_${channels.size}",
                        title = titlePart,
                        groupTitle = group,
                        logoUrl = logo,
                        streamUrl = line
                    )
                )
                if (channels.size >= maxChannels) break
            }
        }
        return channels
    }
}

data class NuvioManifestSummary(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val resources: List<String>,
    val types: List<String>,
    val catalogCount: Int
)

object NuvioManifestParser {
    fun parseManifestJson(rawJson: String): Result<NuvioManifestSummary> = runCatching {
        val obj = JSONObject(rawJson)
        val id = obj.optString("id", "").trim()
        val name = obj.optString("name", "").trim()
        val version = obj.optString("version", "").trim()
        require(id.isNotEmpty() && name.isNotEmpty() && version.isNotEmpty()) {
            "Manifest requires non-empty id, name, and version"
        }
        val resourcesArr = obj.optJSONArray("resources") ?: JSONArray()
        val resources = mutableListOf<String>()
        for (i in 0 until resourcesArr.length()) {
            val item = resourcesArr.get(i)
            when (item) {
                is String -> resources.add(item)
                is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }?.let { resources.add(it) }
            }
        }
        val typesArr = obj.optJSONArray("types") ?: JSONArray()
        val types = (0 until typesArr.length()).mapNotNull { typesArr.optString(it).takeIf { s -> s.isNotBlank() } }
        val catalogsArr = obj.optJSONArray("catalogs") ?: JSONArray()
        NuvioManifestSummary(
            id = id,
            name = name,
            version = version,
            description = obj.optString("description", ""),
            resources = resources,
            types = types,
            catalogCount = catalogsArr.length()
        )
    }
}

data class CloudStreamRepoSummary(
    val name: String,
    val description: String,
    val manifestVersion: Int,
    val pluginListUrls: List<String>,
    val executionPolicy: String = "METADATA_ONLY_NO_DEX_EXECUTION"
)

object CloudStreamRepoParser {
    fun parseRepoJson(rawJson: String): Result<CloudStreamRepoSummary> = runCatching {
        val obj = JSONObject(rawJson)
        val name = obj.optString("name", "").trim()
        require(name.isNotEmpty()) { "CloudStream repo descriptor requires a non-empty name" }
        val listsArr = obj.optJSONArray("pluginLists") ?: JSONArray()
        val validUrls = mutableListOf<String>()
        for (i in 0 until listsArr.length()) {
            val url = listsArr.optString(i, "").trim()
            require(UrlPolicyValidator.isSafeHttpsUrl(url)) {
                "Unsafe or non-HTTPS pluginList URL rejected: $url"
            }
            validUrls.add(url)
        }
        CloudStreamRepoSummary(
            name = name,
            description = obj.optString("description", ""),
            manifestVersion = obj.optInt("manifestVersion", 1),
            pluginListUrls = validUrls
        )
    }
}

/** Shared cache/state boundary; an unknown or relabeled kind is never approved. */
internal fun MediaCard.belongsTo(section: FeedSection): Boolean = this.section == section && when (section) {
    FeedSection.HOME -> kind in setOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.VIDEO)
    FeedSection.ANIME -> kind == MediaKind.ANIME
    FeedSection.KDRAMA -> kind == MediaKind.K_DRAMA
    FeedSection.LIVETV -> kind == MediaKind.LIVE_TV
    FeedSection.ADULT -> kind == MediaKind.ADULT
}

class SectionStateCoordinator(
    adultInitiallyEnabled: Boolean = false
) {
    private val stores = mutableMapOf<FeedSection, SectionStoreState>().apply {
        for (section in FeedSection.entries) {
            put(section, SectionStoreState(section = section, items = defaultCuratedItems(section)))
        }
        if (!adultInitiallyEnabled) {
            put(FeedSection.ADULT, SectionStoreState(section = FeedSection.ADULT, items = emptyList()))
        }
    }

    var isAdultEnabled: Boolean = adultInitiallyEnabled
        @Synchronized get
        private set

    @Synchronized
    fun getState(section: FeedSection): SectionStoreState =
        stores[section] ?: SectionStoreState(section = section)

    @Synchronized
    fun setAdultOptIn(enabled: Boolean): SectionStoreState {
        isAdultEnabled = enabled
        val updated = if (enabled) {
            SectionStoreState(
                section = FeedSection.ADULT,
                items = defaultCuratedItems(FeedSection.ADULT),
                lastUpdatedMs = System.currentTimeMillis()
            )
        } else {
            SectionStoreState(
                section = FeedSection.ADULT,
                items = emptyList(),
                searchQuery = "",
                searchResults = emptyList(),
                errorMessage = null
            )
        }
        stores[FeedSection.ADULT] = updated
        return updated
    }

    @Synchronized
    fun updateSectionItems(
        section: FeedSection,
        newItems: List<MediaCard>,
        timestampMs: Long = System.currentTimeMillis()
    ): SectionStoreState {
        if (section == FeedSection.ADULT && !isAdultEnabled) {
            return getState(FeedSection.ADULT)
        }
        val scoped = newItems.filter { it.belongsTo(section) }.distinctBy { it.providerId to it.id }
        val current = getState(section)
        if (timestampMs < current.lastUpdatedMs) return current
        val next = current.copy(
            items = scoped,
            searchResults = localMatches(scoped, current.searchQuery),
            isLoading = false,
            errorMessage = null,
            lastUpdatedMs = timestampMs
        )
        stores[section] = next
        return next
    }

    @Synchronized
    fun updateSearch(
        section: FeedSection,
        query: String,
        remoteResults: List<MediaCard> = LOCAL_QUERY_CHANGE
    ): SectionStoreState {
        if (section == FeedSection.ADULT && !isAdultEnabled) {
            return getState(FeedSection.ADULT)
        }
        val current = getState(section)
        val trimmed = query.trim()
        // Two arguments start/change a query; an explicitly supplied list completes
        // that query, including an empty provider response. Late completions are inert.
        if (remoteResults !== LOCAL_QUERY_CHANGE && trimmed != current.searchQuery.trim()) return current
        val combined = if (trimmed.isEmpty()) emptyList() else
            (localMatches(current.items, trimmed) + remoteResults.filter { it.belongsTo(section) })
                .distinctBy { it.providerId to it.id }
        val next = current.copy(
            searchQuery = query,
            searchResults = combined
        )
        stores[section] = next
        return next
    }

    private fun localMatches(items: List<MediaCard>, query: String): List<MediaCard> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return items.filter {
            it.title.contains(trimmed, ignoreCase = true) ||
                it.subtitle.contains(trimmed, ignoreCase = true) ||
                it.genres.any { genre -> genre.contains(trimmed, ignoreCase = true) }
        }
    }

    @Synchronized
    fun recordSectionError(section: FeedSection, error: String): SectionStoreState {
        val current = getState(section)
        if (section == FeedSection.ADULT && !isAdultEnabled) return current
        val next = current.copy(isLoading = false, errorMessage = error)
        stores[section] = next
        return next
    }

    companion object {
        private val LOCAL_QUERY_CHANGE = object : AbstractList<MediaCard>() {
            override val size: Int = 0
            override fun get(index: Int): MediaCard = throw IndexOutOfBoundsException(index.toString())
        }
        private const val CC0_MP4_URL =
            "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"

        fun defaultCuratedItems(section: FeedSection): List<MediaCard> = when (section) {
            FeedSection.HOME -> listOf(
                MediaCard(
                    id = "mdn_cc0_flower",
                    title = "Flower (MDN CC0 sample)",
                    subtitle = "Public CC0 demonstration video • not a movie or episode",
                    synopsis = "The Flower video from MDN's interactive examples. This is a playback sample, not a catalog title.",
                    section = FeedSection.HOME,
                    providerId = "mdn-cc0-sample",
                    providerName = "MDN CC0 video sample",
                    kind = MediaKind.VIDEO,
                    contentId = "mdn:cc0:flower",
                    streams = listOf(
                        StreamSource(
                            id = "mdn_cc0_flower_mp4",
                            label = "CC0 sample MP4",
                            url = CC0_MP4_URL,
                            protocol = StreamProtocol.MP4,
                            quality = "",
                            isDownloadable = true,
                            providerId = "mdn-cc0-sample",
                            contentId = "mdn:cc0:flower",
                            section = FeedSection.HOME
                        )
                    )
                )
            )
            else -> emptyList()
        }
    }
}

object LiveNetworkAdapters {
    private fun httpGet(url: String, timeoutMs: Int = 8000): String {
        require(UrlPolicyValidator.isSafeHttpsUrl(url)) { "Blocked non-HTTPS URL: $url" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Cinator/0.2 (Android; Native)")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $url")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun fetchTvMazeSectionCatalog(section: FeedSection, customQuery: String? = null): List<MediaCard> {
        val defaultQuery = when (section) {
            FeedSection.HOME -> customQuery?.takeIf { it.isNotBlank() } ?: "star"
            FeedSection.ANIME -> customQuery?.takeIf { it.isNotBlank() } ?: "anime"
            FeedSection.KDRAMA -> customQuery?.takeIf { it.isNotBlank() } ?: "korean"
            else -> return emptyList()
        }
        val encoded = URLEncoder.encode(defaultQuery, "UTF-8")
        val endpoint = "https://api.tvmaze.com/search/shows?q=$encoded"
        val raw = httpGet(endpoint)
        return parseTvMazeSearchJson(raw, section)
    }

    fun parseTvMazeSearchJson(rawJson: String, section: FeedSection): List<MediaCard> {
        if (section !in setOf(FeedSection.HOME, FeedSection.ANIME, FeedSection.KDRAMA)) return emptyList()
        val arr = JSONArray(rawJson)
        val results = mutableListOf<MediaCard>()
        val providerId = when (section) {
            FeedSection.HOME -> "tvmaze-home"
            FeedSection.ANIME -> "tvmaze-anime"
            FeedSection.KDRAMA -> "tvmaze-kdrama"
            else -> "tvmaze-other"
        }
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val show = row.optJSONObject("show") ?: continue
            val id = show.optInt("id", -1)
            if (id <= 0) continue
            val name = show.optString("name", "").trim()
            if (name.isEmpty()) continue
            val language = show.optString("language", "")
            val type = show.optString("type", "")
            val premiered = show.optString("premiered", "").take(4).ifEmpty { null }
            val ratingObj = show.optJSONObject("rating")
            val avgRating = ratingObj?.optDouble("average")?.takeIf { !it.isNaN() && it > 0.0 }?.toString()
            val genresArr = show.optJSONArray("genres") ?: JSONArray()
            val genres = (0 until genresArr.length()).mapNotNull { genresArr.optString(it).takeIf { g -> g.isNotBlank() } }
            val isAnime = genres.any { it.equals("Anime", ignoreCase = true) } ||
                (type.equals("Animation", ignoreCase = true) && language.equals("Japanese", ignoreCase = true))
            val isKDrama = !isAnime && language.equals("Korean", ignoreCase = true) &&
                type.equals("Scripted", ignoreCase = true) && genres.any { it.equals("Drama", ignoreCase = true) }
            val kind = when {
                isAnime -> MediaKind.ANIME
                isKDrama -> MediaKind.K_DRAMA
                type.lowercase() in setOf("scripted", "animation", "reality", "documentary", "talk show", "game show", "news", "sports", "variety", "panel show", "award show") -> MediaKind.SERIES
                else -> MediaKind.UNKNOWN
            }
            val matchesSection = when (section) {
                FeedSection.ANIME -> kind == MediaKind.ANIME
                FeedSection.KDRAMA -> kind == MediaKind.K_DRAMA
                FeedSection.HOME -> kind == MediaKind.SERIES
                else -> false
            }
            if (!matchesSection) continue
            val rawSummary = show.optString("summary", "")
                .replace(Regex("<[^>]*>"), "")
                .trim()
                .ifEmpty { "Live metadata fetched from TVMaze ($type • $language)." }
            val officialUrl = show.optString("officialSite", "").takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) }
                ?: show.optString("url", "").takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) }

            results.add(
                MediaCard(
                    id = "tvmaze_${section.name.lowercase()}_$id",
                    title = name,
                    subtitle = "$type • $language" + (premiered?.let { " • $it" } ?: ""),
                    synopsis = rawSummary,
                    section = section,
                    providerId = providerId,
                    providerName = "TVMaze Public API",
                    year = premiered,
                    rating = avgRating,
                    genres = genres,
                    kind = kind,
                    contentId = "tvmaze:$id",
                    externalUrl = officialUrl,
                    streams = emptyList()
                )
            )
        }
        return results
    }

    fun fetchIptvChannels(playlistUrl: String = "https://iptv-org.github.io/iptv/categories/animation.m3u"): List<MediaCard> {
        val raw = httpGet(playlistUrl)
        val parsed = M3uPlaylistParser.parse(raw, maxChannels = 35)
        return parsed.map { ch ->
            MediaCard(
                id = ch.id,
                title = ch.title,
                subtitle = "Live TV • ${ch.groupTitle}",
                synopsis = "Public M3U stream parsed from $playlistUrl",
                section = FeedSection.LIVETV,
                providerId = "iptv-org-public",
                providerName = "IPTV-org Public M3U",
                genres = listOf(ch.groupTitle, "Live HLS"),
                streams = listOf(
                    StreamSource(
                        id = "${ch.id}_hls",
                        label = "Live HLS (.m3u8)",
                        url = ch.streamUrl,
                        protocol = StreamProtocol.HLS_M3U8,
                        quality = "Live",
                        isDownloadable = false,
                        providerId = "iptv-org-public"
                    )
                )
            )
        }
    }

    fun probeDeclaredBdixHosts(): List<BdixHostStatus> {
        return ProviderRegistry.bdixDeclaredHosts.map { (name, endpoint) ->
            val start = System.currentTimeMillis()
            if (!UrlPolicyValidator.isDeclaredBdixEndpoint(endpoint)) {
                BdixHostStatus(name, endpoint, false, -1, 0L, "Rejected by host policy")
            } else {
                try {
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 2500
                        readTimeout = 2500
                        instanceFollowRedirects = false
                    }
                    val code = conn.responseCode
                    val elapsed = System.currentTimeMillis() - start
                    conn.disconnect()
                    BdixHostStatus(
                        hostName = name,
                        endpoint = endpoint,
                        reachable = code in 200..399,
                        httpStatus = code,
                        latencyMs = elapsed,
                        note = if (code in 200..399) "Reachable on current network" else "HTTP $code"
                    )
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - start
                    BdixHostStatus(
                        hostName = name,
                        endpoint = endpoint,
                        reachable = false,
                        httpStatus = 0,
                        latencyMs = elapsed,
                        note = "Unreachable (${e.javaClass.simpleName})"
                    )
                }
            }
        }
    }
}
