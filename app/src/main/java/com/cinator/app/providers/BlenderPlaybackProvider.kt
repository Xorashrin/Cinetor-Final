package com.cinator.app.providers

import com.cinator.app.data.BoundedHttpClient
import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import com.cinator.app.domain.MediaKind
import com.cinator.app.domain.StreamProtocol
import com.cinator.app.domain.StreamSource
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException

/** Re-resolves only the four audited complete films on Blender's own PeerTube instance.
 * The bundled URL is evidence/quality preference, never a fallback for an unavailable API.
 * CC permission comes from the film-specific audit, not PeerTube's often Unknown licence.
 */
class BlenderPlaybackProvider(
    raw: String,
    private val http: TextHttpClient = BoundedHttpClient(),
    private val candidateExecutor: BlenderCandidateExecutor? = null
) : PlaybackProvider {
    override val info = ProviderInfo(PROVIDER_ID, "Blender Open Films", FeedSection.HOME,
        ProviderCapabilities(playback = true, download = true), "https://studio.blender.org/films/")

    private data class Pin(val id: String, val uuid: String, val title: String, val year: Int,
        val height: Int, val rights: String, val description: String)
    private data class Film(val pin: Pin, val card: MediaCard, val apiUrl: String,
        val attribution: String, val license: String, val durationMs: Long, val downloadAllowed: Boolean)

    private val films: Map<String, Film>

    init {
        interrupted()
        require(raw.length <= 262144) { "Blender registry exceeds size budget" }
        val root = JSONObject(raw)
        require(root.opt("schemaVersion") == 1 && root.opt("providerId") == PROVIDER_ID &&
            root.opt("name") == info.name) { "Unsupported Blender registry" }
        val rows = root.getJSONArray("films")
        require(rows.length() == PINS.size) { "Audited registry must contain all four films" }
        val entries = linkedMapOf<String, Film>()
        for (i in 0 until rows.length()) {
            interrupted()
            val row = rows.getJSONObject(i)
            val pin = PINS.firstOrNull { it.id == row.opt("id") }
                ?: throw IllegalArgumentException("Film is not audited")
            require(row.opt("uuid") == pin.uuid && row.opt("title") == pin.title &&
                row.opt("year") == pin.year && row.opt("height") == pin.height) { "Altered audited film identity" }
            val apiUrl = "$ORIGIN/api/v1/videos/${pin.uuid}"
            require(row.opt("apiUrl") == apiUrl && row.opt("streamUrl") == fileUrl(pin.uuid, pin.height) &&
                row.opt("attributionUrl") == pin.rights &&
                row.opt("sourcePermalink") == "https://studio.blender.org/projects/${pin.id}/") {
                "Altered audited film provenance"
            }
            val attribution = row.getString("attribution")
            require(attribution.isNotBlank() && attribution.length <= 8192) { "Missing film attribution" }
            val license = row.opt("licenseUrl") as? String ?: ""
            val durationMs = row.getLong("durationMs")
            require(durationMs > 0 && row.getLong("contentLength") > 0) { "Missing full-film audit" }
            val credits = !row.has("preserveCompleteEndCredits") || row.opt("preserveCompleteEndCredits") == true
            val description = row.opt("description") as? String ?: row.opt("synopsis") as? String ?: pin.description
            val card = MediaCard(id = pin.id, title = pin.title, subtitle = "Blender Open Films • ${pin.year}",
                synopsis = synopsis(description, attribution, license, pin.title), section = info.section,
                providerId = info.id, providerName = info.name, year = pin.year.toString(),
                externalUrl = pin.rights, posterUrl = artwork(row.opt("posterUrl") as? String, pin.uuid),
                kind = MediaKind.MOVIE, contentId = "blender:${pin.uuid}")
            require(entries.put(pin.id, Film(pin, card, apiUrl, attribution, license, durationMs,
                license == CC_BY_3 && credits)) == null) { "Duplicate audited film" }
        }
        require(entries.size == PINS.size) { "Audited registry is incomplete" }
        films = entries.toMap()
    }

    override fun catalog(query: String): List<MediaCard> {
        interrupted()
        val needle = query.trim()
        return films.values.map { it.card }.filter {
            interrupted()
            needle.isEmpty() || listOf(it.title, it.synopsis, it.year.orEmpty()).any { value ->
                value.contains(needle, ignoreCase = true)
            }
        }
    }

    override fun details(card: MediaCard): ProviderDetails {
        val film = selected(card)
        val data = load(film).second
        val description = data.opt("description") as? String
        val poster = artwork(data.opt("previewPath") as? String, film.pin.uuid)
            ?: artwork(data.opt("thumbnailPath") as? String, film.pin.uuid)
        return ProviderDetails(film.card.copy(
            synopsis = if (description.isNullOrBlank()) film.card.synopsis
                else synopsis(description, film.attribution, film.license, film.pin.title),
            posterUrl = poster ?: film.card.posterUrl))
    }

    override fun resolve(request: ResolveRequest): List<StreamSource> {
        val film = selected(request.card)
        require(request.episode == null) { "Blender open films do not have episodes" }
        val (document, data) = load(film)
        val listedFiles = mutableListOf<JSONObject>()
        var totalRows = 0
        fun collect(files: org.json.JSONArray?) {
            totalRows += files?.length() ?: 0
            require(totalRows <= 256) { "Blender file row budget exceeded" }
            for (index in 0 until (files?.length() ?: 0)) {
                interrupted()
                files?.optJSONObject(index)?.let(listedFiles::add)
            }
        }
        require(!data.has("files") || data.opt("files") is org.json.JSONArray) { "Invalid Blender files" }
        collect(data.optJSONArray("files"))
        // PeerTube may move direct file entries beneath streamingPlaylists. Never use
        // playlistUrl, torrentUrl, fileDownloadUrl or a fragment-based/trailer URL.
        require(!data.has("streamingPlaylists") || data.opt("streamingPlaylists") is org.json.JSONArray) { "Invalid Blender playlists" }
        val playlists = data.optJSONArray("streamingPlaylists")
        require((playlists?.length() ?: 0) <= 8) { "Blender playlist budget exceeded" }
        for (index in 0 until (playlists?.length() ?: 0)) {
            interrupted()
            val playlist = playlists?.optJSONObject(index)
            require(playlist?.opt("files") is org.json.JSONArray) { "Invalid Blender playlist files" }
            collect(playlist?.optJSONArray("files"))
        }
        val validFiles = listedFiles.filter { file ->
            interrupted()
            val height = safeInteger(file.opt("height"))
            height != null && height in 144..2160 &&
                safeInteger(file.optJSONObject("resolution")?.opt("id")) == height &&
                file.opt("fileUrl") == fileUrl(film.pin.uuid, height.toInt()) &&
                safeInteger(file.opt("size")) != null && file.opt("hasAudio") == true && file.opt("hasVideo") == true
        }
        val candidates = validFiles.map { safeInteger(it.opt("height"))!!.toInt() }.distinct().sortedWith(compareBy<Int> {
            when { it == film.pin.height -> 0; it < film.pin.height -> 1; else -> 2 }
        }.thenBy { if (it < film.pin.height) -it else it }).take(3)
        interrupted()
        val sources = candidates.mapNotNull { height ->
            interrupted()
            if (candidateExecutor != null) {
                val output = try { candidateExecutor.execute(document, film.pin.uuid, height) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Blender native request interrupted")
                }
                interrupted()
                val candidate = parseCandidate(output) ?: return@mapNotNull null
                val file = validFiles.first { safeInteger(it.opt("height")) == height.toLong() }
                require(candidate.length() == 4 && candidate.opt("uuid") == film.pin.uuid &&
                    safeInteger(candidate.opt("height")) == height.toLong() && candidate.opt("url") == file.opt("fileUrl") &&
                    safeInteger(candidate.opt("size")) == safeInteger(file.opt("size"))) { "Native candidate differs from reviewed API entry" }
            }
            StreamSource(id = "${film.card.contentId}:$height", label = "${film.pin.title} • ${height}p • Full film",
                url = fileUrl(film.pin.uuid, height), protocol = StreamProtocol.MP4, quality = "${height}p",
                isDownloadable = film.downloadAllowed && data.opt("downloadEnabled") == true,
                providerId = info.id, contentId = film.card.contentId, section = info.section)
        }
        interrupted()
        return sources
    }

    /** Both API and native numbers are parsed exactly, without JSONTokener/Double. */
    private fun parseCandidate(raw: String): JSONObject? {
        val rows = BoundedLosslessJson.parse(raw, 16 * 1024) as? org.json.JSONArray
            ?: throw IllegalArgumentException("Invalid native candidate array")
        if (rows.length() == 0) return null
        require(rows.length() == 1) { "Invalid native candidate count" }
        val result = rows.optJSONObject(0) ?: throw IllegalArgumentException("Invalid native candidate object")
        require(result.length() == 4 && listOf("uuid", "height", "url", "size").all(result::has)) {
            "Unexpected native candidate fields"
        }
        return result
    }

    private fun safeInteger(value: Any?): Long? {
        if (value !is Number) return null
        val number = try { java.math.BigDecimal(value.toString()).longValueExact() }
            catch (_: NumberFormatException) { return null }
            catch (_: ArithmeticException) { return null }
        return number.takeIf { it in 1..9007199254740991L }
    }

    private fun selected(card: MediaCard): Film {
        interrupted()
        val film = films[card.id] ?: throw IllegalArgumentException("Film is not audited")
        require(card.providerId == info.id && card.section == info.section && card.kind == MediaKind.MOVIE &&
            card.contentId == film.card.contentId) { "Foreign film identity" }
        return film
    }

    private fun load(film: Film): Pair<String, JSONObject> {
        interrupted()
        val raw = try { http.get(film.apiUrl) }
        catch (e: CancellationException) { throw e }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Blender request interrupted")
        } catch (_: Exception) {
            interrupted()
            throw IOException("Blender film API unavailable")
        }
        interrupted()
        val data = BoundedLosslessJson.parse(raw, 64 * 1024) as? JSONObject
            ?: throw IllegalArgumentException("Invalid Blender API object")
        val account = data.optJSONObject("account")
        val channel = data.optJSONObject("channel")
        require(data.opt("uuid") == film.pin.uuid && data.opt("isLocal") == true &&
            data.opt("isLive") == false && data.opt("url") == "$ORIGIN/videos/watch/${film.pin.uuid}" &&
            account?.opt("host") == HOST && account.opt("name") == "blender" &&
            account.opt("url") == "$ORIGIN/accounts/blender" && channel?.opt("host") == HOST &&
            channel.opt("name") == "blender_open_movies" &&
            channel.opt("url") == "$ORIGIN/video-channels/blender_open_movies") { "Unverified Blender film provenance" }
        require(safeInteger(data.optJSONObject("privacy")?.opt("id")) == 1L && data.opt("blacklisted") != true) { "Blender film is not public" }
        val seconds = safeInteger(data.opt("duration")) ?: throw IllegalArgumentException("Invalid full-film duration")
        require(seconds > 0 && kotlin.math.abs(seconds - film.durationMs / 1000) <= 2) { "Full-film duration changed" }
        return raw to data
    }

    companion object {
        private const val PROVIDER_ID = "blender-open-films"
        private const val HOST = "video.blender.org"
        private const val ORIGIN = "https://video.blender.org"
        private const val CC_BY_3 = "https://creativecommons.org/licenses/by/3.0/"
        // UUID/title/year/rights associations verified against official project pages and API.
        private val PINS = listOf(
            Pin("big-buck-bunny", "bf1f3fb5-b119-4f9f-9930-8e20e892b898", "Big Buck Bunny", 2008, 720, "https://peach.blender.org/about/", "Big Buck Bunny tells the story of a giant rabbit with a heart bigger than himself. When one sunny day three rodents rudely harass him, something snaps... and the rabbit ain't no bunny anymore! In the typical cartoon tradition he prepares the nasty rodents a comical revenge."),
            Pin("sintel", "0eb052d0-fd51-43e6-aa33-ecdbf77a5d40", "Sintel", 2010, 480, "https://durian.blender.org/sharing/", "\"Sintel\" is an independently produced short film, initiated by the Blender Foundation as a means to further improve and validate the free/open source 3D creation suite Blender. With initial funding provided by 1000s of donations via the internet community, it has again proven to be a viable development model for both open 3D technology as for independent animation film.\r\nThis 15 minute film has been realized in the studio of the Amsterdam Blender Institute, by an international team of artists and developers. In addition to that, several crucial technical and creative targets have been realized online, by developers and artists and teams all over the world."),
            Pin("tears-of-steel", "8533ea43-4271-4a57-9694-e9d0b35e1aa1", "Tears of Steel", 2012, 480, "https://mango.blender.org/sharing/", "\"Tears of Steel\" was realized with crowd-funding by users of the open source 3D creation tool Blender. Target was to improve and test a complete open and free pipeline for visual effects in film - and to make a compelling sci-fi film in Amsterdam, the Netherlands."),
            Pin("cosmos-laundromat", "f507dfdc-e73e-45a4-9778-d758cbe1ce96", "Cosmos Laundromat: First Cycle", 2015, 480, "https://gooseberry.blender.org/sharing/", "On a desolate island, suicidal sheep Franck meets his fate in a quirky salesman, who offers him the gift of a lifetime. Little does he know that he can only handle so much lifetime. (Target audience age: 13+)")
        )
        private fun fileUrl(uuid: String, height: Int) = "$ORIGIN/object-storage/web_videos/$uuid-$height.mp4"
        private fun artwork(raw: String?, uuid: String): String? {
            val path = "/lazy-static/thumbnails/$uuid.jpg"
            return if (raw == path || raw == "$ORIGIN$path") "$ORIGIN$path" else null
        }
        private fun synopsis(description: String, attribution: String, license: String, title: String) =
            "$description\n\nAttribution: $attribution\nTitle: $title\nLicense: ${license.ifBlank { "Not documented; downloads unavailable" }}\nFull film with original end credits; preserve attribution and credits."
        private fun interrupted() {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Blender request interrupted")
        }
    }
}
