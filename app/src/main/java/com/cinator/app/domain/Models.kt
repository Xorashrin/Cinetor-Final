package com.cinator.app.domain

enum class FeedSection(val cacheKey: String, val displayName: String) {
    HOME("cinator_store_home", "Home"),
    ANIME("cinator_store_anime", "Anime"),
    KDRAMA("cinator_store_kdrama", "K-Drama"),
    LIVETV("cinator_store_livetv", "Live TV"),
    ADULT("cinator_store_adult", "Adult Content")
}

enum class SourceClass {
    OPEN_MEDIA_STREAM,
    PUBLIC_METADATA_API,
    PUBLIC_M3U_PLAYLIST,
    LOCAL_NETWORK_DISCOVERY,
    AUTHORIZED_ADDON_MANIFEST,
    EXTERNAL_LINK_ONLY
}

enum class ProviderCapability {
    SEARCH,
    METADATA,
    IN_APP_STREAM,
    SEEK,
    DOWNLOAD,
    LIVE_HLS,
    MANIFEST_CONTRACT,
    WIFI_HOST_PROBE,
    EXTERNAL_REFERENCE
}

enum class StreamProtocol {
    MP4,
    HLS_M3U8
}

data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val section: FeedSection,
    val sourceClass: SourceClass,
    val endpoint: String,
    val protocol: String,
    val capabilities: Set<ProviderCapability>,
    val canPlayInApp: Boolean,
    val canDownload: Boolean,
    val capabilityNote: String
)

data class StreamSource(
    val id: String,
    val label: String,
    val url: String,
    val protocol: StreamProtocol,
    val quality: String,
    val isDownloadable: Boolean,
    val providerId: String,
    val subtitleLang: String? = null
)

data class MediaCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val synopsis: String,
    val section: FeedSection,
    val providerId: String,
    val providerName: String,
    val year: String? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    val externalUrl: String? = null,
    val streams: List<StreamSource> = emptyList()
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadEntry(
    val id: String,
    val mediaId: String,
    val title: String,
    val section: FeedSection,
    val sourceUrl: String,
    val localFilePath: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: DownloadStatus,
    val errorMessage: String? = null
)

data class BdixHostStatus(
    val hostName: String,
    val endpoint: String,
    val reachable: Boolean,
    val httpStatus: Int,
    val latencyMs: Long,
    val note: String
)

data class SectionStoreState(
    val section: FeedSection,
    val cacheKey: String = section.cacheKey,
    val providers: List<ProviderDescriptor> = ProviderRegistry.forSection(section),
    val items: List<MediaCard> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<MediaCard> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val lastUpdatedMs: Long = 0L
)

object ProviderRegistry {
    val allProviders: List<ProviderDescriptor> = listOf(
        // HOME (Movies & Series only)
        ProviderDescriptor(
            id = "open-cinema-mp4",
            displayName = "Open Cinema & HLS Test Archive",
            section = FeedSection.HOME,
            sourceClass = SourceClass.OPEN_MEDIA_STREAM,
            endpoint = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
            protocol = "HTTPS MP4 (HTTP 206 Byte-Range) & Apple fMP4 HLS",
            capabilities = setOf(
                ProviderCapability.SEARCH,
                ProviderCapability.METADATA,
                ProviderCapability.IN_APP_STREAM,
                ProviderCapability.SEEK,
                ProviderCapability.DOWNLOAD
            ),
            canPlayInApp = true,
            canDownload = true,
            capabilityNote = "Verified CC0 MP4 & Apple fMP4 HLS streams supporting native ExoPlayer play, pause, 10s seek, and offline MP4 download."
        ),
        ProviderDescriptor(
            id = "tvmaze-home",
            displayName = "TVMaze Movies & Series Catalog",
            section = FeedSection.HOME,
            sourceClass = SourceClass.PUBLIC_METADATA_API,
            endpoint = "https://api.tvmaze.com/shows",
            protocol = "HTTPS REST JSON",
            capabilities = setOf(ProviderCapability.SEARCH, ProviderCapability.METADATA),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "Live public metadata and search API for scripted series and TV films."
        ),
        ProviderDescriptor(
            id = "vidsrc-ref",
            displayName = "VidSrc (Guide Reference)",
            section = FeedSection.HOME,
            sourceClass = SourceClass.EXTERNAL_LINK_ONLY,
            endpoint = "https://vidsrc.to",
            protocol = "External Web Embed",
            capabilities = setOf(ProviderCapability.EXTERNAL_REFERENCE),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "External link reference only; no unauthorized embed scraping or stream extraction."
        ),
        ProviderDescriptor(
            id = "cineby-flixhq-ref",
            displayName = "Cineby / FlixHQ (Guide Reference)",
            section = FeedSection.HOME,
            sourceClass = SourceClass.EXTERNAL_LINK_ONLY,
            endpoint = "https://cineby.app",
            protocol = "External Web Portal",
            capabilities = setOf(ProviderCapability.EXTERNAL_REFERENCE),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "External link reference only."
        ),
        ProviderDescriptor(
            id = "yts-ref",
            displayName = "YTS.mx (Guide Reference)",
            section = FeedSection.HOME,
            sourceClass = SourceClass.EXTERNAL_LINK_ONLY,
            endpoint = "https://yts.mx",
            protocol = "External BitTorrent Portal",
            capabilities = setOf(ProviderCapability.EXTERNAL_REFERENCE),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "External link reference only."
        ),

        // ANIME (Anime only)
        ProviderDescriptor(
            id = "tvmaze-anime",
            displayName = "TVMaze Anime Metadata API",
            section = FeedSection.ANIME,
            sourceClass = SourceClass.PUBLIC_METADATA_API,
            endpoint = "https://api.tvmaze.com/search/shows?q=anime",
            protocol = "HTTPS REST JSON",
            capabilities = setOf(ProviderCapability.SEARCH, ProviderCapability.METADATA),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "Live public anime metadata and search filtered strictly to Anime/Animation."
        ),
        ProviderDescriptor(
            id = "open-anime-stream",
            displayName = "Open Animation Stream Fixture",
            section = FeedSection.ANIME,
            sourceClass = SourceClass.OPEN_MEDIA_STREAM,
            endpoint = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            protocol = "HTTPS HLS + CC0 MP4",
            capabilities = setOf(
                ProviderCapability.SEARCH,
                ProviderCapability.METADATA,
                ProviderCapability.IN_APP_STREAM,
                ProviderCapability.SEEK,
                ProviderCapability.DOWNLOAD
            ),
            canPlayInApp = true,
            canDownload = true,
            capabilityNote = "Open-licensed animation stream for testing Anime feed playback, seek, and offline MP4 download."
        ),
        ProviderDescriptor(
            id = "hianime-ref",
            displayName = "HiAnime / AniWatch (Guide Reference)",
            section = FeedSection.ANIME,
            sourceClass = SourceClass.EXTERNAL_LINK_ONLY,
            endpoint = "https://hianime.to",
            protocol = "External Web Portal",
            capabilities = setOf(ProviderCapability.EXTERNAL_REFERENCE),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "External link reference only; not bundled as an in-app scraper."
        ),
        ProviderDescriptor(
            id = "animepahe-ref",
            displayName = "AnimePahe (Guide Reference)",
            section = FeedSection.ANIME,
            sourceClass = SourceClass.EXTERNAL_LINK_ONLY,
            endpoint = "https://animepahe.ru",
            protocol = "External Web Portal",
            capabilities = setOf(ProviderCapability.EXTERNAL_REFERENCE),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "External link reference only."
        ),
        ProviderDescriptor(
            id = "gogoanime-nyaa-ref",
            displayName = "GogoAnime / Nyaa.si (Guide Reference)",
            section = FeedSection.ANIME,
            sourceClass = SourceClass.EXTERNAL_LINK_ONLY,
            endpoint = "https://nyaa.si",
            protocol = "External Index",
            capabilities = setOf(ProviderCapability.EXTERNAL_REFERENCE),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "External link reference only."
        ),

        // K-DRAMA (K-Drama only)
        ProviderDescriptor(
            id = "tvmaze-kdrama",
            displayName = "TVMaze Korean Drama Catalog",
            section = FeedSection.KDRAMA,
            sourceClass = SourceClass.PUBLIC_METADATA_API,
            endpoint = "https://api.tvmaze.com/search/shows?q=korean",
            protocol = "HTTPS REST JSON",
            capabilities = setOf(ProviderCapability.SEARCH, ProviderCapability.METADATA),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "Live Korean & Asian drama metadata and search isolated to the K-Drama feed."
        ),
        ProviderDescriptor(
            id = "open-kdrama-preview",
            displayName = "Open Drama Preview Stream",
            section = FeedSection.KDRAMA,
            sourceClass = SourceClass.OPEN_MEDIA_STREAM,
            endpoint = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
            protocol = "HTTPS MP4 (HTTP 206)",
            capabilities = setOf(
                ProviderCapability.SEARCH,
                ProviderCapability.METADATA,
                ProviderCapability.IN_APP_STREAM,
                ProviderCapability.SEEK,
                ProviderCapability.DOWNLOAD
            ),
            canPlayInApp = true,
            canDownload = true,
            capabilityNote = "Open-licensed stream fixture for K-Drama feed player & downloader verification."
        ),
        ProviderDescriptor(
            id = "dramacool-kisskh-ref",
            displayName = "DramaCool / KissKH / MyAsianTV (Reference)",
            section = FeedSection.KDRAMA,
            sourceClass = SourceClass.EXTERNAL_LINK_ONLY,
            endpoint = "https://kisskh.co",
            protocol = "External Web Portal",
            capabilities = setOf(ProviderCapability.EXTERNAL_REFERENCE),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "External link reference only."
        ),

        // LIVE TV (Strictly isolated inside More -> Live TV)
        ProviderDescriptor(
            id = "iptv-org-public",
            displayName = "IPTV-org Public M3U Channels",
            section = FeedSection.LIVETV,
            sourceClass = SourceClass.PUBLIC_M3U_PLAYLIST,
            endpoint = "https://iptv-org.github.io/iptv/categories/animation.m3u",
            protocol = "HTTPS #EXTM3U / HLS (.m3u8)",
            capabilities = setOf(
                ProviderCapability.SEARCH,
                ProviderCapability.IN_APP_STREAM,
                ProviderCapability.LIVE_HLS
            ),
            canPlayInApp = true,
            canDownload = false,
            capabilityNote = "Parses public #EXTINF M3U playlists and plays HTTPS .m3u8 live streams in ExoPlayer. Download is disabled for live streams."
        ),
        ProviderDescriptor(
            id = "daddylive-ref",
            displayName = "DaddyLive / DLHD (Guide Reference)",
            section = FeedSection.LIVETV,
            sourceClass = SourceClass.EXTERNAL_LINK_ONLY,
            endpoint = "https://daddylive.sx",
            protocol = "External Web Portal",
            capabilities = setOf(ProviderCapability.EXTERNAL_REFERENCE),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "External link reference only."
        ),

        // ADULT (Strictly isolated inside More -> Adult Content, disabled by default)
        ProviderDescriptor(
            id = "adult-self-hosted-manifest",
            displayName = "Authorized Self-Hosted 18+ Addon Slot",
            section = FeedSection.ADULT,
            sourceClass = SourceClass.AUTHORIZED_ADDON_MANIFEST,
            endpoint = "https://example.org/manifest.json",
            protocol = "Nuvio/Stremio JSON Manifest",
            capabilities = setOf(ProviderCapability.MANIFEST_CONTRACT, ProviderCapability.SEARCH),
            canPlayInApp = false,
            canDownload = false,
            capabilityNote = "Disabled by default. Requires explicit 18+ opt-in and user-supplied authorized manifest URL; completely isolated from Home, Anime, and K-Drama."
        )
    )

    val bdixDeclaredHosts: List<Pair<String, String>> = listOf(
        "Discovery FTP (DFLIX)" to "https://movies.discoveryftp.net",
        "Discovery Portal" to "https://discoveryftp.net",
        "Circle FTP" to "https://circleftp.net",
        "ICC FTP" to "https://iccbdixtv.com"
    )

    fun forSection(section: FeedSection): List<ProviderDescriptor> =
        allProviders.filter { it.section == section }
}
