# v0.4 batch 2 integration contract

Repo /home/ASHRIN/Cinetor-Final, first batch new providers/ProviderContracts.kt, ProviderResolver.kt, OpenFilmProvider.kt, StremioPlaybackProvider.kt; ui/ResolvedMediaDialog.kt ResolvedDetailsState.kt already exist. Read before editing. Parent owns all shared Gradle, manifest, CI, source asset JSON, Models.kt and CatalogRepository.kt edits. No child commits, pushes, tags or Gradle daemons. Local JVM harness ready, Gradle final run parent only.

## Audited film registry
Parent creates assets/open_films.json:
```
{ "schemaVersion":1, "providerId":"blender-open-films", "name":"Blender Open Films", "films":[
 {"id":"big-buck-bunny", "uuid":"bf1f3fb5-b119-4f9f-9930-8e20e892b898", "title":"Big Buck Bunny", "year":2008, "apiUrl":"https://video.blender.org/api/v1/videos/<uuid>", "streamUrl":"...verified official MP4...", "height":720, "contentLength":110916740, "durationMs":596566, "attributionUrl":"https://peach.blender.org/about/", "attribution":"...", "licenseUrl":"https://creativecommons.org/licenses/by/3.0/", "sourcePermalink":"...", "posterUrl":null }
] }
```
Four film records from scratch/cinator-v04-openfilms/film-streams.json. contentId = `blender:<uuid>`, card.id=film.id, stream.id=`blender:<uuid>:<height>`. HOME MOVIE even if Western animation, not ANIME. No fake ratings/descriptions. Streams full film preserve credits. Runtime provider must re-resolve current files via actual API, validate UUID and declared official file provenance rather than use unrelated URL. If no matching official MP4 -> unavailable, never sample fallback.

## Factory API (runtime agent owns)
```
object BundledPlaybackProviders {
 fun fromJson(raw:String, http:TextHttpClient=BoundedHttpClient()):List<PlaybackProvider>
}
class BlenderPlaybackProvider ... : PlaybackProvider
```
Real catalog/search from pinned4 audited film descriptions, details loads exact video API (may obtain description/poster), resolve gets same API files+identity, max3 same-content MP4 sources with height preferences. No full media bytes in text client. Explicit audited CC permission gates download. Embed title/attribution/license in card synopsis and externalUrl to rights page. Important DownloadPolicy known IDs: provider blender-open-films absent registry means permitted by existing logic if matching card; parent adds descriptor later.

## Parent CatalogRepository integration
Constructor optional `playbackProviders: List<PlaybackProvider> = emptyList()`; load(section,query) combines built-in catalog sources plus provider.catalog(query), isolated failure semantics and namespaced identities. MainActivity constructs providers from assets.open_films.json, shares them between CatalogRepository and ProviderResolver; skips default MDN sample in v0.4 bundle, no new custom URL UI. No Stremio playback provider enabled because audit blocked direct transport; adapter stays tested dormant.

## UI integration owner
Own MainActivity.kt only + ResolvedMediaDialog.kt/ResolvedDetailsState.kt if necessary. Initialize providers safely, source errors shown. When card provider matches: fetch details via runInterruptible, stale-request generation, open ResolvedMediaDialog; Resolve uses ProviderResolver(providers).resolve(ResolveRequest(card,selectedEpisode)), write returned sources only current selection. onPlay picks actual stream, title from details, onDownload authorize corresponding episode card identity (for now films no episodes). Old metadata-only dialog remains for unmatched catalogs. Caches persist artwork safely; sources will be freshly resolved, don't call prior catalog streams verified. SelectedCard cancellation generation important. No raw calls in Composable main thread. Parent adds factory/assets/CatalogRepository args concurrently.

## Download identity fields parent adds
DownloadEntry trailing fields contentId:String?=null, providerId:String?=null, streamId:String?=null. Downloader propagation owner sets from actual card/stream; offline MoreScreen uses those rather than entry.mediaId; no identity guessing. Parent owns Models so child only download manager/MoreScreen+tests.

## Android E2E
Prefer instrumented JUnit on API35 over elaborate debug telemetry hooks. Owner NEW androidTest/java/.../ProviderPlaybackE2ETest.kt constructs actual factory from target assets and actual public API, Media3 actual ExoPlayer on MainLooper in test Activity via ActivityScenario(MainActivity), real decoded frame or videoDecoderCounters, sample positions, pause stable, actual seek discontinuity. Download one verified film to target Context via actual DownloadManager IO and verify bytes/full hash where pinned, then offline local file ExoPlayer with FileDataSource-only and network forbidden. Don't use fake URL. Separate test stages and saved evidence, fail not skip if unavailable. If true UI fullscreen test cannot implement safely, report untested—not passed. Parent sets testInstrumentationRunner AndroidJUnitRunner + junit/runner/rules dependencies and CI `connectedDebugAndroidTest`. No debug hooks required for initial integration; UI test optional next scope.

## Ownership batch2
1 review resolver only;2 Stremio tests/review only;3 BundledPlaybackProviders.kt + BlenderPlaybackProvider.kt + own tests;
4 MainActivity and Resolved dialog/state/tests only;
5 downloader/MoreScreen identity propagation + tests only;
6 cache/provider identity regression tests only;
7 Android instrumentation test files only;
8 docs/provider-coverage-v04.md/THIRD_PARTY_NOTICES append only;
9 artifact/evidence validator review/tests only;
10 independent review existing first-batch code, scratch report only.
Parent will freeze integration, run full tests/CI/emulator, publish accurate preview or blockers.
