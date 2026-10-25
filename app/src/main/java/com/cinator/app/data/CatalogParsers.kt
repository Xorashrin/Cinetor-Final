package com.cinator.app.data

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

data class CinemetaManifest(
    val id: String,
    val version: String,
    val name: String,
    val resources: List<String>,
    val types: List<String>,
    val catalogs: List<CinemetaCatalogDeclaration> = emptyList()
)

data class CinemetaCatalogDeclaration(
    val type: String,
    val id: String,
    val extraSupported: Set<String>,
    val extraRequired: Set<String>
)

object CinemetaManifestParser {
    fun parse(rawJson: String): CinemetaManifest {
        val root = JSONObject(rawJson)
        val id = root.getString("id")
        val version = root.getString("version")
        val name = root.getString("name")
        require(id.isNotBlank() && version.isNotBlank() && name.isNotBlank()) { "Incomplete manifest" }
        val resourcesArray = root.getJSONArray("resources")
        val resources = (0 until resourcesArray.length()).map { i ->
            when (val resource = resourcesArray.get(i)) {
                is String -> resource
                is JSONObject -> resource.getString("name")
                else -> throw IllegalArgumentException("Invalid manifest resource")
            }
        }
        require("catalog" in resources) { "Manifest does not declare catalog capability" }
        val types = strings(root.getJSONArray("types"))
        val declarations = root.getJSONArray("catalogs")
        require(declarations.length() in 1..32) { "Invalid catalog declaration count" }
        val seen = mutableSetOf<Pair<String, String>>()
        val catalogs = (0 until declarations.length()).map { i ->
            val declaration = declarations.getJSONObject(i)
            val type = declaration.getString("type")
            val catalogId = declaration.getString("id")
            require(type in types && catalogId.isNotBlank() && seen.add(type to catalogId)) { "Invalid catalog declaration" }
            val supported = strings(declaration.optJSONArray("extraSupported") ?: JSONArray()).toMutableSet()
            val required = strings(declaration.optJSONArray("extraRequired") ?: JSONArray()).toMutableSet()
            val extras = declaration.optJSONArray("extra") ?: JSONArray()
            for (j in 0 until extras.length()) {
                val extra = extras.getJSONObject(j)
                val extraName = extra.getString("name")
                require(extraName.isNotBlank()) { "Invalid extra name" }
                supported.add(extraName)
                if (extra.optBoolean("isRequired", false)) required.add(extraName)
            }
            CinemetaCatalogDeclaration(type, catalogId, supported, required)
        }
        require(catalogs.any { it.type in setOf("movie", "series") }) { "No supported catalog type" }
        return CinemetaManifest(id, version, name, resources, types, catalogs)
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
}

object CinemetaCatalogParser {
    fun parse(
        rawJson: String,
        section: FeedSection = FeedSection.HOME,
        providerId: String = "cinemeta-top-movies",
        providerName: String = "Cinemeta"
    ): List<MediaCard> {
        if (section != FeedSection.HOME) return emptyList()
        val root = JSONObject(rawJson)
        val metas = root.getJSONArray("metas")
        val results = mutableListOf<MediaCard>()

        for (i in 0 until metas.length()) {
            val meta = metas.getJSONObject(i)
            val id = meta.optString("imdb_id").ifEmpty { meta.optString("id") }
            require(id.isNotBlank()) { "Catalog item requires id" }
            val name = meta.getString("name")
            require(name.isNotBlank()) { "Catalog item requires name" }
            val year = meta.optString("releaseInfo").ifEmpty { meta.optString("year", "") }
            val rating = meta.optString("imdbRating", "")
            val genresArray = meta.optJSONArray("genres")
            val genres = mutableListOf<String>()
            if (genresArray != null) {
                for (g in 0 until genresArray.length()) {
                    genres.add(genresArray.getString(g))
                }
            }
            val poster = meta.optString("poster", "").takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) }
            val background = meta.optString("background", "").takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) }
            val description = meta.optString("description", "")
            val type = meta.getString("type")
            require(type.isNotBlank()) { "Catalog item requires type" }
            if (type !in setOf("movie", "series")) continue
            val countries = when (val country = meta.opt("country")) {
                is String -> country.split(',', ';').map { it.trim().lowercase() }
                is JSONArray -> (0 until country.length()).map { country.getString(it).trim().lowercase() }
                else -> emptyList()
            }
            val japanese = countries.any { it in setOf("jp", "japan") }
            val korean = countries.any { it in setOf("kr", "south korea", "korea", "korea, republic of", "republic of korea") }
            val animation = genres.any { it.equals("Animation", true) }
            val drama = genres.any { it.equals("Drama", true) }
            val anime = genres.any { it.equals("Anime", true) } || (japanese && animation)
            if (anime || (type == "series" && korean && drama && !animation)) continue
            // Series without origin or genre evidence cannot be safely separated from Anime/K-Drama.
            if (type == "series" && (countries.isEmpty() || countries.all { it.isBlank() } || genres.isEmpty())) continue
            val kind = if (type.equals("series", ignoreCase = true)) MediaKind.SERIES else MediaKind.MOVIE

            val subtitleText = listOfNotNull(year.takeIf { it.isNotEmpty() }, rating.takeIf { it.isNotEmpty() }?.let { "★ $it" }).joinToString(" • ")

            results.add(
                MediaCard(
                    id = id,
                    title = name,
                    subtitle = subtitleText,
                    synopsis = description,
                    section = section,
                    providerId = providerId,
                    providerName = providerName,
                    year = year,
                    rating = rating,
                    genres = genres,
                    externalUrl = null,
                    streams = emptyList(),
                    posterUrl = poster,
                    backdropUrl = background,
                    kind = kind,
                    contentId = id
                )
            )
        }
        return results
    }
}

object TVMazeCatalogParser {
    fun parse(
        rawJson: String,
        section: FeedSection,
        providerId: String = "tvmaze",
        providerName: String = "TVMaze"
    ): List<MediaCard> {
        if (section !in setOf(FeedSection.HOME, FeedSection.ANIME, FeedSection.KDRAMA)) return emptyList()
        val array = JSONArray(rawJson)

        val results = mutableListOf<MediaCard>()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val show = entry.getJSONObject("show")
            val showId = show.optInt("id", -1)
            require(showId > 0) { "TVMaze show requires id" }
            val name = show.getString("name")
            require(name.isNotBlank()) { "TVMaze show requires name" }
            val language = show.optString("language", "")
            val type = show.optString("type", "")
            val genresArray = show.optJSONArray("genres")
            val genres = mutableListOf<String>()
            if (genresArray != null) {
                for (g in 0 until genresArray.length()) {
                    genres.add(genresArray.getString(g))
                }
            }
            val premiered = show.optString("premiered", "")
            val year = if (premiered.length >= 4) premiered.substring(0, 4) else ""
            val ratingObj = show.optJSONObject("rating")
            val rating = ratingObj?.optDouble("average")?.takeIf { !it.isNaN() }?.toString() ?: ""
            val network = show.optJSONObject("network")
            val webChannel = show.optJSONObject("webChannel")
            val netCountry = network?.optJSONObject("country")
            val webCountry = webChannel?.optJSONObject("country")
            val countryCodes = listOfNotNull(netCountry?.optString("code"), webCountry?.optString("code"))

            val isAnime = genres.any { it.equals("Anime", true) } ||
                (type.equals("Animation", true) && (language.equals("Japanese", true) || countryCodes.any { it.equals("JP", true) }))
            val isKDrama = !isAnime && type.equals("Scripted", true) && genres.any { it.equals("Drama", true) } &&
                (language.equals("Korean", true) || countryCodes.any { it.equals("KR", true) })

            if (section == FeedSection.ANIME && !isAnime) continue
            if (section == FeedSection.KDRAMA && !isKDrama) continue
            if (section == FeedSection.HOME && (isAnime || isKDrama || type.lowercase() !in setOf(
                    "scripted", "animation", "reality", "documentary", "talk show", "game show", "news", "sports", "variety", "panel show", "award show"))) continue

            val summaryHtml = show.optString("summary", "")
            val cleanSummary = sanitizeHtml(summaryHtml)
            val imageObj = show.optJSONObject("image")
            val poster = imageObj?.optString("medium")?.takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) }
                ?: imageObj?.optString("original")?.takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) }
            val backdrop = imageObj?.optString("original")?.takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) }

            val kind = when (section) {
                FeedSection.ANIME -> MediaKind.ANIME
                FeedSection.KDRAMA -> MediaKind.K_DRAMA
                else -> MediaKind.SERIES
            }

            val subtitleText = listOfNotNull(year.takeIf { it.isNotEmpty() }, language.takeIf { it.isNotEmpty() }).joinToString(" • ")

            results.add(
                MediaCard(
                    id = "tvmaze-$showId",
                    title = name,
                    subtitle = subtitleText,
                    synopsis = cleanSummary,
                    section = section,
                    providerId = providerId,
                    providerName = providerName,
                    year = year,
                    rating = rating,
                    genres = genres,
                    externalUrl = show.optString("url").takeIf { UrlPolicyValidator.isSafeHttpsUrl(it) },
                    streams = emptyList(),
                    posterUrl = poster,
                    backdropUrl = backdrop,
                    kind = kind,
                    contentId = "tvmaze-$showId"
                )
            )
        }
        return results
    }


    private fun sanitizeHtml(html: String): String {
        if (html.isEmpty()) return ""
        return html.replace(Regex("<[^>]*>"), "").trim()
    }
}

object MultiCategoryIptvParser {
    fun parse(
        m3uContent: String,
        section: FeedSection = FeedSection.LIVETV,
        providerId: String = "iptv-org",
        providerName: String = "IPTV-org",
        maxItems: Int = 200
    ): List<MediaCard> {
        if (section != FeedSection.LIVETV) return emptyList()
        require(maxItems in 1..200) { "Invalid playlist item budget" }
        val lines = m3uContent.lineSequence()
        require(lines.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("\uFEFF") == "#EXTM3U") { "Invalid M3U playlist" }
        val results = mutableListOf<MediaCard>()
        val seen = mutableSetOf<String>()
        var currentInf: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#EXTINF:")) {
                currentInf = trimmed
            } else if (!trimmed.startsWith("#") && currentInf != null) {
                val streamUrl = trimmed
                val inf = currentInf
                currentInf = null

                if (!isValidSafeHttpsUrl(streamUrl)) continue
                if (!URI(streamUrl).path.endsWith(".m3u8", ignoreCase = true)) continue

                val commaIdx = inf.lastIndexOf(',')
                val title = if (commaIdx != -1 && commaIdx < inf.length - 1) {
                    inf.substring(commaIdx + 1).trim()
                } else {
                    "Live Stream"
                }

                val tvgId = extractAttribute(inf, "tvg-id")
                val tvgLogo = extractAttribute(inf, "tvg-logo").takeIf { isValidSafeHttpsUrl(it) }
                val groupTitle = extractAttribute(inf, "group-title")
                val tvgCountry = extractAttribute(inf, "tvg-country")
                val tvgLanguage = extractAttribute(inf, "tvg-language")

                val genres = mutableListOf<String>()
                if (groupTitle.isNotEmpty()) {
                    genres.addAll(groupTitle.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() })
                }
                if (tvgCountry.isNotEmpty()) genres.add(tvgCountry)
                if (tvgLanguage.isNotEmpty()) genres.add(tvgLanguage)

                val digest = MessageDigest.getInstance("SHA-256").digest(streamUrl.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val id = "$providerId:${tvgId.ifEmpty { "channel" }}:$digest"
                if (!seen.add(id)) continue

                results.add(
                    MediaCard(
                        id = id,
                        title = title,
                        subtitle = "Live playlist listing • playback unverified",
                        synopsis = "Playlist-listed channel: $title. Availability and rights are not verified.",
                        section = section,
                        providerId = providerId,
                        providerName = providerName,
                        year = null,
                        rating = null,
                        genres = genres,
                        externalUrl = null,
                        streams = listOf(
                            StreamSource(
                                id = "stream-$id",
                                label = "Live HLS",
                                url = streamUrl,
                                protocol = StreamProtocol.HLS_M3U8,
                                quality = "",
                                isDownloadable = false,
                                providerId = providerId,
                                contentId = id,
                                section = section,
                                isLive = true
                            )
                        ),
                        posterUrl = tvgLogo,
                        backdropUrl = tvgLogo,
                        kind = MediaKind.LIVE_TV,
                        contentId = id
                    )
                )
                if (results.size >= maxItems) break
            }
        }
        return results
    }

    private fun extractAttribute(line: String, attr: String): String {
        val pattern = Regex("$attr=\"([^\"]*)\"")
        val match = pattern.find(line)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun isValidSafeHttpsUrl(url: String): Boolean {
        return UrlPolicyValidator.isSafeHttpsUrl(url)
    }
}

object OpenVideoCatalogParser {
    const val MDN_FLOWER_URL = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"

    fun parse(
        rawPayload: String = "",
        section: FeedSection = FeedSection.HOME,
        providerId: String = "open-video-sample",
        providerName: String = "MDN Open Video"
    ): List<MediaCard> {
        // Strictly allowed for HOME section. Never for ANIME or KDRAMA!
        if (section != FeedSection.HOME) return emptyList()

        val id = "open-video-flower"
        return listOf(
            MediaCard(
                id = id,
                title = "MDN CC0 Flower Sample Video",
                subtitle = "CC0 Public Domain Video",
                synopsis = "Official MDN CC0 public domain test clip of blooming flowers.",
                section = FeedSection.HOME,
                providerId = providerId,
                providerName = providerName,
                year = null,
                rating = null,
                genres = listOf("Educational", "Sample"),
                externalUrl = "https://developer.mozilla.org/en-US/docs/Web/HTML/Element/video",
                streams = listOf(
                    StreamSource(
                        id = "stream-open-video-flower",
                        label = "MDN CC0 sample MP4",
                        url = MDN_FLOWER_URL,
                        protocol = StreamProtocol.MP4,
                        quality = "",
                        isDownloadable = true,
                        providerId = providerId,
                        contentId = id,
                        section = FeedSection.HOME,
                        isLive = false
                    )
                ),
                posterUrl = null,
                backdropUrl = null,
                kind = MediaKind.VIDEO,
                contentId = id
            )
        )
    }
}
