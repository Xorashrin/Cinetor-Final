package com.cinator.app.data

import com.cinator.app.domain.BdixHostStatus
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
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
    fun isSafeHttpsUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host?.lowercase() ?: return false
            if (scheme != "https") return false
            if (uri.userInfo != null) return false
            if (host == "localhost" || host.startsWith("127.") || host == "0.0.0.0") return false
            true
        } catch (_: Exception) {
            false
        }
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
        private set

    fun getState(section: FeedSection): SectionStoreState =
        stores[section] ?: SectionStoreState(section = section)

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

    fun updateSectionItems(
        section: FeedSection,
        newItems: List<MediaCard>,
        timestampMs: Long = System.currentTimeMillis()
    ): SectionStoreState {
        if (section == FeedSection.ADULT && !isAdultEnabled) {
            return getState(FeedSection.ADULT)
        }
        val scoped = newItems.filter { it.section == section }.distinctBy { "${it.providerId}:${it.id}" }
        val current = getState(section)
        val next = current.copy(
            items = scoped,
            isLoading = false,
            errorMessage = null,
            lastUpdatedMs = timestampMs
        )
        stores[section] = next
        return next
    }

    fun updateSearch(
        section: FeedSection,
        query: String,
        remoteResults: List<MediaCard> = emptyList()
    ): SectionStoreState {
        if (section == FeedSection.ADULT && !isAdultEnabled) {
            return getState(FeedSection.ADULT)
        }
        val current = getState(section)
        val trimmed = query.trim()
        val localMatches = if (trimmed.isEmpty()) {
            emptyList()
        } else {
            current.items.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.subtitle.contains(trimmed, ignoreCase = true) ||
                    it.genres.any { g -> g.contains(trimmed, ignoreCase = true) }
            }
        }
        val combined = (localMatches + remoteResults.filter { it.section == section })
            .distinctBy { "${it.providerId}:${it.id}" }
        val next = current.copy(
            searchQuery = query,
            searchResults = combined
        )
        stores[section] = next
        return next
    }

    fun recordSectionError(section: FeedSection, error: String): SectionStoreState {
        val current = getState(section)
        val next = current.copy(isLoading = false, errorMessage = error)
        stores[section] = next
        return next
    }

    companion object {
        private const val CC0_MP4_URL =
            "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"
        private const val APPLE_BIPBOP_HLS_URL =
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

        fun defaultCuratedItems(section: FeedSection): List<MediaCard> = when (section) {
            FeedSection.HOME -> listOf(
                MediaCard(
                    id = "home_open_flower_mp4",
                    title = "Bloom in Motion (Open CC0 Cinema Fixture)",
                    subtitle = "Open Cinema • Direct MP4 (Play/Pause/Seek/Download)",
                    synopsis = "Verified CC0 MP4 video stream supporting HTTP 206 byte-range seeking and local offline MP4 download.",
                    section = FeedSection.HOME,
                    providerId = "open-cinema-mp4",
                    providerName = "Open Cinema & HLS Test Archive",
                    year = "2024",
                    rating = "8.6",
                    genres = listOf("Documentary", "Short"),
                    streams = listOf(
                        StreamSource(
                            id = "stream_home_mp4",
                            label = "Direct MP4 (CC0 • Downloadable)",
                            url = CC0_MP4_URL,
                            protocol = StreamProtocol.MP4,
                            quality = "720p",
                            isDownloadable = true,
                            providerId = "open-cinema-mp4",
                            subtitleLang = "English"
                        ),
                        StreamSource(
                            id = "stream_home_hls",
                            label = "Adaptive fMP4 HLS Stream",
                            url = APPLE_BIPBOP_HLS_URL,
                            protocol = StreamProtocol.HLS_M3U8,
                            quality = "1080p Auto",
                            isDownloadable = false,
                            providerId = "open-cinema-mp4",
                            subtitleLang = "English CC"
                        )
                    )
                ),
                MediaCard(
                    id = "home_hls_multirate",
                    title = "Signal Horizon (Adaptive HLS Test Feature)",
                    subtitle = "Apple fMP4 Master Playlist • Multi-bitrate HLS",
                    synopsis = "Exercises ExoPlayer adaptive HLS streaming, keyframe seeking, and playback speed controls.",
                    section = FeedSection.HOME,
                    providerId = "open-cinema-mp4",
                    providerName = "Open Cinema & HLS Test Archive",
                    year = "2025",
                    rating = "8.9",
                    genres = listOf("Sci-Fi", "Feature"),
                    streams = listOf(
                        StreamSource(
                            id = "stream_signal_hls",
                            label = "Master HLS (.m3u8)",
                            url = APPLE_BIPBOP_HLS_URL,
                            protocol = StreamProtocol.HLS_M3U8,
                            quality = "Adaptive HLS",
                            isDownloadable = false,
                            providerId = "open-cinema-mp4"
                        )
                    )
                )
            )

            FeedSection.ANIME -> listOf(
                MediaCard(
                    id = "anime_open_fixture",
                    title = "Chronicle of Aether (Open Anime Stream Fixture)",
                    subtitle = "Sub / Dub Ready • Isolated Anime Feed",
                    synopsis = "Open-licensed animation stream verifying isolated Anime feed playback, 10-second seek, and offline MP4 download.",
                    section = FeedSection.ANIME,
                    providerId = "open-anime-stream",
                    providerName = "Open Animation Stream Fixture",
                    year = "2025",
                    rating = "9.1",
                    genres = listOf("Anime", "Action", "Fantasy"),
                    streams = listOf(
                        StreamSource(
                            id = "stream_anime_hls",
                            label = "Anime HLS Stream (Sub)",
                            url = APPLE_BIPBOP_HLS_URL,
                            protocol = StreamProtocol.HLS_M3U8,
                            quality = "1080p HLS",
                            isDownloadable = false,
                            providerId = "open-anime-stream",
                            subtitleLang = "Japanese / English Sub"
                        ),
                        StreamSource(
                            id = "stream_anime_mp4",
                            label = "Direct Episode MP4 (Downloadable)",
                            url = CC0_MP4_URL,
                            protocol = StreamProtocol.MP4,
                            quality = "720p MP4",
                            isDownloadable = true,
                            providerId = "open-anime-stream"
                        )
                    )
                )
            )

            FeedSection.KDRAMA -> listOf(
                MediaCard(
                    id = "kdrama_open_fixture",
                    title = "Seoul Midnight Letters (Open Drama Fixture)",
                    subtitle = "Korean Drama • Isolated K-Drama Feed",
                    synopsis = "Isolated K-Drama feed entry paired with an open-licensed preview stream for player and offline download verification.",
                    section = FeedSection.KDRAMA,
                    providerId = "open-kdrama-preview",
                    providerName = "Open Drama Preview Stream",
                    year = "2025",
                    rating = "8.8",
                    genres = listOf("K-Drama", "Romance", "Mystery"),
                    streams = listOf(
                        StreamSource(
                            id = "stream_kdrama_mp4",
                            label = "Preview Episode MP4 (Downloadable)",
                            url = CC0_MP4_URL,
                            protocol = StreamProtocol.MP4,
                            quality = "720p MP4",
                            isDownloadable = true,
                            providerId = "open-kdrama-preview",
                            subtitleLang = "English / Korean"
                        )
                    )
                )
            )

            FeedSection.LIVETV -> listOf(
                MediaCard(
                    id = "livetv_bipbop_live",
                    title = "Cinator Test Broadcast HD",
                    subtitle = "Live TV • HLS .m3u8 Channel",
                    synopsis = "Verified HTTPS HLS stream for Live TV channel switching and low-latency ExoPlayer playback. Download is disabled on live streams.",
                    section = FeedSection.LIVETV,
                    providerId = "iptv-org-public",
                    providerName = "IPTV-org Public M3U Channels",
                    genres = listOf("Live TV", "Broadcast"),
                    streams = listOf(
                        StreamSource(
                            id = "stream_live_1",
                            label = "Live HLS Channel (.m3u8)",
                            url = APPLE_BIPBOP_HLS_URL,
                            protocol = StreamProtocol.HLS_M3U8,
                            quality = "Live HLS",
                            isDownloadable = false,
                            providerId = "iptv-org-public"
                        )
                    )
                )
            )

            FeedSection.ADULT -> listOf(
                MediaCard(
                    id = "adult_authorized_slot",
                    title = "Self-Hosted 18+ Addon Slot (No Default Content)",
                    subtitle = "Isolated 18+ Store • Opt-In Only",
                    synopsis = "This section is isolated from Home, Anime, and K-Drama. Configure an authorized self-hosted manifest URL in Settings to load adult catalog metadata.",
                    section = FeedSection.ADULT,
                    providerId = "adult-self-hosted-manifest",
                    providerName = "Authorized Self-Hosted 18+ Addon Slot",
                    genres = listOf("18+ Isolated")
                )
            )
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
            val language = show.optString("language", "English")
            val type = show.optString("type", "Series")
            val premiered = show.optString("premiered", "").take(4).ifEmpty { null }
            val ratingObj = show.optJSONObject("rating")
            val avgRating = ratingObj?.optDouble("average")?.takeIf { !it.isNaN() && it > 0.0 }?.toString()
            val genresArr = show.optJSONArray("genres") ?: JSONArray()
            val genres = (0 until genresArr.length()).mapNotNull { genresArr.optString(it).takeIf { g -> g.isNotBlank() } }
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
