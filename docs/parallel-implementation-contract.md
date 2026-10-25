# Parallel implementation contract — v0.3 preview

## Shared models (parent owned)
`MediaCard` retains existing fields and adds `posterUrl: String? = null`, `backdropUrl: String? = null`, `kind: MediaKind = MediaKind.UNKNOWN`, `contentId: String? = null`.
`MediaKind`: `MOVIE`, `SERIES`, `VIDEO`, `ANIME`, `K_DRAMA`, `LIVE_TV`, `ADULT`, `UNKNOWN`.
`StreamSource` retains existing fields and adds `contentId: String? = null`, `section: FeedSection? = null`, `isLive: Boolean = false`, `headers: Map<String,String> = emptyMap()`.
Do not attach a test video, invented rating, invented audio/subtitle, or unrelated title to real catalog entries. Unknown category is not automatically approved. K-Drama means Korean scripted drama only, not all Asian animation.

## Backend agent API
Package `com.cinator.app.data`:
- `data class EmbeddedCatalog(val sources: List<EmbeddedSource>)` (extra provenance properties allowed)
- `data class EmbeddedSource(val id: String, val name: String, val section: FeedSection, val engine: String, val endpoints: List<String>, val enabled: Boolean, val capabilityNote: String)` (extra properties with defaults allowed)
- `object EmbeddedCatalogParser { fun parse(rawJson: String): EmbeddedCatalog }`
- `interface TextHttpClient { fun get(url: String): String }`
- `class BoundedHttpClient : TextHttpClient` (actual bounded HTTPS networking; no bypass; no opaque code execution)
- `data class CatalogLoadResult(val items: List<MediaCard>, val failures: List<String>, val sourceCount: Int)`
- `class CatalogRepository(val bundle: EmbeddedCatalog, val http: TextHttpClient = BoundedHttpClient()) { fun load(section: FeedSection, query: String = ""): CatalogLoadResult }`
Sources `TVMAZE`, `CINEMETA`, `IPTV` have real protocol adapters. All other directory entries are explicitly references, not stream engines. Manifest/catalog parser is not permission to bypass. Cinemeta declares catalog/meta, not stream.
App assets JSON schema `schemaVersion:1, bundleVersion:"0.3.0", sources:[{id,name,section,engine,endpoints,enabled,capabilityNote}]`. Parent owns generated asset JSON and external inventory. Static `engine` selection only. Repository failures must not suppress successful providers; URLs validated, response size/time limited. Empty unavailable feed stays empty, never sample fallback.

## Cache/state agent API
Package `com.cinator.app.data`:
- `class CatalogCache(rootDirectory: java.io.File) { fun read(section: FeedSection): List<MediaCard>; fun write(section: FeedSection, items: List<MediaCard>); fun clear(section: FeedSection) }`.
Serialize real item metadata, namespaced content identity, correct sections/kinds, streams and artwork. Validate URLs on read and restrict stale credentials; do not cache expiring stream secrets. Adult cache is isolated; opt-out clears it. Atomic file replacement and bounded JSON.
Preserve `SectionStateCoordinator` public API, eliminate fictional fixtures (Home may contain the honestly-labelled MDN CC0 Flower sample only; Anime/KDrama/Live/Adult empty until real adapter data exists). State updates/search results enforce actual section and reject cross-section provider results. No unsupported content class fabrications.
`UrlPolicyValidator.isSafeHttpsUrl` must reject credentials, localhost, loopback, link-local, private IPv4/IPv6, metadata IP, unexpected ports and unsafe control characters. Preserve declared opt-in BDIX host policy.

## UI agent API
`MainActivity.kt` plus new UI helper files owned by UI agent. Use `context.assets.open("embedded_catalog.json")` and `EmbeddedCatalogParser.parse`. Auto-load HOME/ANIME/KDRAMA/LIVETV once on startup using IO coroutines and `CatalogRepository.load`; per-section caches loaded before remote. Query/debounced search scoped to one section, cancel outdated work, no stale query overwrites. Preserve no login/multi-profile. More contains Live TV, Downloads, sources status, disabled-by-default Adult toggle. No manual URL editor.
Use `CatalogCache(File(context.filesDir,"catalog_cache"))` in IO; cache fallback labelled cached, never verified new playback. Persist enabled flag only if user opts in, no adult thumbnails outside opt-in section. Use Coil2 AsyncImage (dependency parent adds). Retain Media3/download calls but disable unsupported buttons. Dialog details scrollable; no full-screen player + detail + feed stacked beyond screen. Dark color scheme, safe insets, lazy rails.
Parent owns Gradle/manifest/models/CI/docs; agents must not edit these.

## Player agent API
Preserve `CinatorPlayerPanel(title, stream, localOverridePath = null, onClose)` call signature; add optional `fallbackSources: List<StreamSource> = emptyList()` and callbacks only with defaults.
Standalone internal fullscreen Dialog uses SAME ExoPlayer instance, not a second playback instance. Restore orientation/system bars on dispose; pause on lifecycle stop. Fallbacks require same non-null contentId and same non-null section, no live-to-VOD or unrelated sample replacement; attempts bounded, user sees source failure/exhaustion. No subtitle label without actual track; track UI reflects real Player.currentTracks. Seek commit on drag-finish, preserve user pause while buffering. Resume key includes content/section, avoid frequent disk writes.

## Local JVM test harness
`python3 /home/ASHRIN/.hermes/cache/scratch/cinator-jvm-tooling/run_tests.py --sources <paths...> --tests <paths...>` (parent creates). Kotlin 2.0.21/JDK21, org.json, JUnit and coroutines available. This compiles only pure Kotlin files; no Android/UI classes. Agents record RED then GREEN per behavioral slice. Parent performs Android CI compile/lint/instrumentation before delivery.

## Ownership
Backend: NEW `EmbeddedCatalog.kt`, `CatalogRepository.kt`, `CatalogParsers.kt`, its tests.
State: existing `DataContractsAndAdapters.kt`, NEW `CatalogCache.kt`, its tests; do not modify other agent files.
UI: existing `MainActivity.kt`, NEW UI helpers, its tests; no Gradle/models edits.
Player: existing `playback/CinatorPlayerPanel.kt`, NEW `PlaybackFallback.kt`, its tests; no manifest edits.
Parent: model/registry adjustments, asset snapshots, shared tooling, Gradle/CI, integration fixes/docs, final external verification.
No child may commit/push/tag/release or edit Hermes global settings. Return paths and test output, not completion claims for untested external effects.
