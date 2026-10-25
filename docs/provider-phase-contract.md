# v0.4 provider-engine phase — shared agent contract

Goal: real provider search -> details/episodes -> resolve -> Media3 playback/download. Not a metadata-label exercise. Never attach sample media to a title, invent availability, or claim all Nuvio/CloudStream plugins integrated. Only reviewed open/public-domain/authorized sources are activated. No manual source URLs. Unknown/incompatible entries stay explicitly inactive; no opaque remote JS/DEX execution, DRM or access-control bypass.

Base /home/ASHRIN/Cinetor-Final, HEAD b2345f2. Parent owns shared model/Gradle/Manifest/CI/asset edits, app integration, commit/push/release. Children edit OWNED new files only; never commit/push or start Gradle daemons. Use filesystem tools, not repeated entire-repo reads. Strict test-first slices with RED/GREEN output; pure Kotlin harness is /home/ASHRIN/.hermes/cache/scratch/cinator-jvm-tooling/run_tests.py. JDK17 Android toolchain available for parent final compile. Actual network probes bounded max4MiB/15s except explicitly limited authorized sample. All scratch under /home/ASHRIN/.hermes/cache/scratch.

## Shared provider API (core owner creates file providers/ProviderContracts.kt)
Package com.cinator.app.providers:
```
data class ProviderCapabilities(val search:Boolean=true,val details:Boolean=true,val episodes:Boolean=false,val playback:Boolean=false,val download:Boolean=false)
data class ProviderInfo(val id:String,val name:String,val section:FeedSection,val capabilities:ProviderCapabilities,val attributionUrl:String,val enabled:Boolean=true)
data class EpisodeRef(val id:String,val title:String,val season:Int?,val number:Int?,val contentId:String)
data class ProviderDetails(val card:MediaCard,val episodes:List<EpisodeRef> = emptyList())
data class ResolveRequest(val card:MediaCard,val episode:EpisodeRef? = null)
interface PlaybackProvider {
 val info:ProviderInfo
 fun catalog(query:String=""):List<MediaCard>
 fun details(card:MediaCard):ProviderDetails
 fun resolve(request:ResolveRequest):List<StreamSource>
}
```
Existing domain MediaCard(id,title,subtitle,synopsis,section,providerId,providerName,year?,rating?,genres,externalUrl?,streams,posterUrl?,backdropUrl?,kind,contentId?). StreamSource(id,label,url,protocol[MP4/HLS_M3U8],quality,isDownloadable,providerId,subtitleLang?,contentId?,section?,isLive,headers). Do not change domain models.
Existing `TextHttpClient.get(url):String` + BoundedHttpClient in data/EmbeddedCatalog.kt provide public HTTPS/DNS checks, timeouts/2MiB body max. Tests can inject TextHttpClient; use byte streams only parent media/player.

## Error/identity rules
Provider validates IDs, card.providerId and section. Stream contentId exactly selected episode.contentId (when episode) else card.contentId; not generic title names. No cross-title fallback. Credentials absent from source/logs; download only if audited permission and supported protocol, no arbitrary hints. Unsupported stream transport (infoHash/webtorrent/externalUrl) isn't converted to URL. Respect cancellation/interruption. Reject malformed data explicitly; unavailable is empty or typed exception, no dummy videos.

## Ownership
- Core agent: NEW providers/ProviderContracts.kt, ProviderResolver.kt + tests. Providers registry constructed by parent, no global hardcoded URLs. Resolve candidate filtering done once, max3 same identity, timeouts via supplied http.
- Stremio agent: NEW providers/StremioPlaybackProvider.kt + tests. Reviewed endpoint required constructor, `ProviderInfo`, `TextHttpClient`. Calls manifest/catalog/meta/stream only resources/types declared. Private auth addons not default enabled. Implement protocol, not Nuvio-native JS engine.
- Open-film agent: NEW providers/OpenFilmProvider.kt + tests. Data-driven allowlist of audited films via constructor `List<OpenFilm>`; no seeded fiction. `OpenFilm(card:MediaCard, streams:List<StreamSource>, attributionUrl:String)`; catalog/search, details, resolve fully usable. Parent supplies real audited film metadata and links. Reject differing identity. No sample fallback.
- UI detail agent: NEW ui/ResolvedMediaDialog.kt + optional NEW pure state file/test. Inputs `details:ProviderDetails`, `loading:Boolean`, `error:String?`, `onEpisode:(EpisodeRef)->Unit`, `onResolve:()->Unit`, `onPlay:(StreamSource)->Unit`, `onDownload:(StreamSource)->Unit`, `onClose:()->Unit`. Dialog exposes actual episodes/streams, unavailable honest. No MainActivity edits.
- Provider proof agent: NEW scripts/provider_e2e.py/test if appropriate, only validates report artifacts/counts/provenance, don't fabricate playback.
- Research agents: scratch reports only; do not edit app. Find exact authorized endpoints, provenance, license, media transport, content identity. Return JSON field values backed by actual outputs.

Completion requires parent Android compile/test/lint, real selected-provider fetch/resolve, emulator playback controls if available, then exact-commit GitHub build/release readback. Unit fixture tests alone are not real-provider verification. Second review/test batch starts after first returns. No hidden source installation request.
