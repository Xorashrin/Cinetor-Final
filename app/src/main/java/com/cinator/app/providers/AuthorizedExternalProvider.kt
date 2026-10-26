package com.cinator.app.providers

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaKind
import java.net.URI
import java.util.Collections

/**
 * Explicitly reviewed official content-page metadata, not discovered media or a user URL form.
 * The caller must verify title, origin and kind against the official provider's metadata.
 * URL syntax alone cannot establish anime or Korean scripted television drama classification.
 * No titles are bundled here. Do not populate routes with guessed IDs or inferred classifications.
 */
data class OfficialContentRoute(
    val title: String,
    val officialUrl: String,
    val kind: MediaKind,
    val originCountryCode: String
)

enum class ExternalPlaybackStatus { EXTERNAL_AUTHORIZED }

/** Constants cannot be elevated by metadata or caller-provided flags. */
object ExternalNavigationCapabilities {
    val nativeStreamPlayback: Boolean get() = false
    val download: Boolean get() = false
    val catalogDiscovery: Boolean get() = false
    val externalNavigation: Boolean get() = true
}

/** Navigation hand-off only: not a playable stream, embed contract or entitlement grant. */
class ExternalPlaybackMetadata internal constructor(
    val providerId: String,
    val contentId: String,
    val title: String,
    val officialUrl: String,
    val supportedSection: FeedSection,
    val kind: MediaKind,
    val rightsAndAvailabilityNotice: String,
    val rightsEvidenceUrl: String
) {
    val status: ExternalPlaybackStatus get() = ExternalPlaybackStatus.EXTERNAL_AUTHORIZED
    val providerControlsPlayback: Boolean get() = true
}

/**
 * Data-only policy for an explicit external-browser/app hand-off. Intentionally NOT a
 * PlaybackProvider: the native resolver cannot consume this provider or its metadata.
 * Never fetches pages, extracts media, evaluates JS, selects fallback titles or launches a UI.
 * A consuming UI must label this "Open on provider", not native Play/Download, and must not
 * load the page in a privileged WebView. Provider login, DRM, ads and geography stay intact.
 * Factories have no defaults: absent reviewed catalog data must mean an inactive provider.
 */
class AuthorizedExternalProvider private constructor(
    val providerId: String,
    val supportedSection: FeedSection,
    private val host: String,
    private val routePattern: Regex,
    private val requiredKind: MediaKind,
    private val requiredCountry: String,
    private val rightsNotice: String,
    private val rightsEvidence: String,
    routes: List<OfficialContentRoute>
) {
    val capabilities: ExternalNavigationCapabilities get() = ExternalNavigationCapabilities
    private val approved: Map<String, ExternalPlaybackMetadata>
    val active: Boolean get() = approved.isNotEmpty()

    init {
        val entries = LinkedHashMap<String, ExternalPlaybackMetadata>()
        routes.forEach { route ->
            require(route.title.isNotBlank() && route.title == route.title.trim() &&
                route.title.none { it.isISOControl() }) { "Missing or malformed official title" }
            require(route.kind == requiredKind && route.originCountryCode == requiredCountry) {
                "Official content classification does not match provider section"
            }
            val uri = try { URI(route.officialUrl) } catch (_: Exception) {
                throw IllegalArgumentException("Malformed official content URL")
            }
            // Exact canonical authority excludes IPs, private hosts, subdomains, credentials,
            // explicit ports, IDN lookalikes and alternative host spellings without DNS requests.
            require(uri.scheme == "https" && uri.rawAuthority == host && uri.host == host &&
                !uri.isOpaque && uri.rawUserInfo == null && uri.port == -1 &&
                uri.rawQuery == null && uri.rawFragment == null &&
                route.officialUrl == "https://$host${uri.rawPath}") { "Noncanonical official content URL" }
            // Strict ASCII path excludes encoded delimiters, traversal, manifests and API routes.
            val match = routePattern.matchEntire(uri.rawPath.orEmpty())
                ?: throw IllegalArgumentException("Not an approved official content-page route")
            val id = match.groupValues[1]
            require(id !in entries) { "Duplicate official content identity" }
            entries[id] = ExternalPlaybackMetadata(providerId, id, route.title, route.officialUrl,
                supportedSection, route.kind, rightsNotice, rightsEvidence)
        }
        approved = Collections.unmodifiableMap(entries)
    }

    /** Only IDs from the reviewed allowlist resolve; there is no arbitrary URL entrypoint. */
    fun navigation(contentId: String): ExternalPlaybackMetadata {
        check(active) { "No reviewed external content routes configured" }
        return approved[contentId] ?: throw IllegalArgumentException("Content is not approved")
    }

    companion object {
        fun crunchyroll(routes: List<OfficialContentRoute>): AuthorizedExternalProvider =
            AuthorizedExternalProvider(
                "crunchyroll-external", FeedSection.ANIME, "www.crunchyroll.com",
                Regex("/series/([A-Z0-9]{9})/[a-z0-9]+(?:-[a-z0-9]+)*"),
                MediaKind.ANIME, "JP",
                "External navigation only. Crunchyroll controls login, subscription, ads, DRM and " +
                    "playback. Licensed availability varies by territory; access is not guaranteed.",
                "https://www.crunchyroll.com/tos", routes
            )

        fun viki(routes: List<OfficialContentRoute>): AuthorizedExternalProvider =
            AuthorizedExternalProvider(
                "viki-external", FeedSection.KDRAMA, "www.viki.com",
                Regex("/tv/([1-9][0-9]*c)-[a-z0-9]+(?:-[a-z0-9]+)*"),
                MediaKind.K_DRAMA, "KR",
                "Korean scripted television drama only; excludes films, variety, reality, music " +
                    "and non-Korean dramas. External navigation only. Viki controls login, ads, " +
                    "Viki Pass, DRM, subtitles and playback. Licensed availability varies by territory; " +
                    "access is not guaranteed.",
                "https://www.viki.com/legal/terms_of_use?locale=en", routes
            )

        fun asiancrush(routes: List<OfficialContentRoute>): AuthorizedExternalProvider =
            AuthorizedExternalProvider(
                "asiancrush-external", FeedSection.KDRAMA, "www.asiancrush.com",
                Regex("/video/([a-z0-9]+(?:-[a-z0-9]+)*)"),
                MediaKind.K_DRAMA, "KR",
                "Korean scripted television drama only; excludes non-Korean content. " +
                    "External navigation only. AsianCrush controls login, ads, " +
                    "subtitles and playback. Licensed availability varies by territory; " +
                    "access is not guaranteed.",
                "https://www.asiancrush.com/terms-of-service", routes
            )
    }
}
