# v0.4 provider coverage — four-film subset, not phase completion

## Verdict and evidence boundary

**The authorized four-film subset advances provider support; it does not complete the provider-engine phase or all-source playback.** Nuvio-native JavaScript execution and the CloudStream extension runtime remain unintegrated. Native authorized Anime/K-Drama playback has not been established. Directory inventory, protocol fixtures and reachable MP4 files are different evidence stages.

This is a batch-2 working-tree documentation snapshot, not exact-release-commit certification. This documentation task ran no builds, unit tests, Android instrumentation, downloads or playback. Existing research results below are not fresh execution. The parent integration owner must append exact-candidate stage results before claiming device acceptance.

## Per-provider matrix

| Provider / layer | Implemented or contracted capability | Evidence boundary and blocker |
| --- | --- | --- |
| Blender Open Films (`blender-open-films`) | Batch-2 contract: four pinned Home/Movie entries, scoped search, official API details/current MP4 resolution, and audited download permission. | Four official-file transport/host short-decode successes are recorded. Parent integration now contains `BlenderPlaybackProvider.kt`, `BundledPlaybackProviders.kt`, the audited asset and resolved UI. Android acceptance remains a separate execution gate. No episodes or Anime/K-Drama provider coverage. |
| Generic `OpenFilmProvider` | Source-present constructor-fed allowlist, catalog/search, details and same-film stream resolution. | Adapter only, not a second audited catalog or device pass. Caller must audit exact film/stream rights. No sample fallback. |
| `StremioPlaybackProvider` | Source-present reviewed-endpoint manifest/catalog/meta/stream adapter; respects declared types/resources and movie/episode identities. Eligible direct HTTPS `.mp4`/`.m3u8` URLs only. | No live playback addon enabled by the batch-2 contract. Supplied research reports torrent-oriented results and HTTP 404s, not an approved working direct-playback addon. `infoHash`, WebTorrent and external-player links are unsupported, not converted to direct URLs. Unit fixtures do not prove live addon playback. |
| Cinemeta | Existing Movies/Series metadata adapter. | Catalog/meta support is not a stream resolver or content license. Metadata/artwork rights remain separate; no film/episode playback claim. |
| TVMaze | Existing metadata/search and Anime/K-Drama feed classification. | CC BY-SA 4.0 metadata, not episode video permission. No authorized native Anime/K-Drama playback established. |
| IPTV-org | Existing category-playlist adapter for eligible public HTTPS live streams. | CC0 playlist data does not license broadcasts. Historical transport probes are not v0.4 channel-by-channel decoding. Offline/geo-restricted streams remain unavailable; no access-control bypass or general live-download/recording claim. |
| MDN Flower | Explicit CC0 sample from the earlier preview; batch-2 contract omits it from the default v0.4 bundle. | Final bundle omission needs parent verification. Never attach the sample to an unavailable movie/episode or count it as real-title coverage. |
| Nuvio-native plugins | Inert/read-only repository descriptors. | No native JS runtime/bridge or upstream torrent-engine integration. A Stremio protocol adapter is not Nuvio-native plugin execution. Per-plugin authorization, compatibility, security/license review and E2E remain. |
| CloudStream extensions | Inert/read-only repository descriptors. | No `.cs3`/DEX extension runtime or full extractor-engine integration. Per-extension authorized search, extraction, playback and eligible downloads remain unverified. |
| Adult / BDIX | No new authorized playback provider in this batch; adult disabled by default. | References or reachability tools are not catalog/extractor integration or provider E2E. |

Legacy-provider attribution and evidence limits are retained from [release-readiness.md](release-readiness.md) and [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). This is not a fresh recount of every directory reference; unsupported references must not be counted as working providers.

## Exact licensed film subset

Exactly four records were parsed and checked against the declared evidence totals in `film-streams.json`. All are CC BY 3.0. They belong in Home/Movie, including Western animation; none establishes an Anime provider. Byte counts/heights describe the audited files, not immutable future API selections.

| Film | Exact content identity | Audited MP4 | Project attribution / rights source |
| --- | --- | --- | --- |
| Big Buck Bunny (2008) | `blender:bf1f3fb5-b119-4f9f-9930-8e20e892b898` | 720p; 110916740 bytes | (c) copyright 2008, Blender Foundation / www.bigbuckbunny.org[1] |
| Sintel (2010) | `blender:0eb052d0-fd51-43e6-aa33-ecdbf77a5d40` | 480p; 119976502 bytes | © copyright Blender Foundation &#124; www.sintel.org[2] |
| Tears of Steel (2012) | `blender:8533ea43-4271-4a57-9694-e9d0b35e1aa1` | 480p; 122344375 bytes | (CC) Blender Foundation &#124; mango.blender.org[3] |
| Cosmos Laundromat: First Cycle (2015) | `blender:f507dfdc-e73e-45a4-9778-d758cbe1ce96` | 480p; 110296781 bytes | (CC) Blender Foundation &#124; gooseberry.blender.org[4] |

Retain the complete ending credits of the selected full-film files and follow each project's sharing/about terms. Film licensing and separate logo permissions are not interchangeable: retaining a logo within the original credits does not establish a standalone license to extract or rebrand it.[1][2][3]

Cosmos Laundromat also has project-specific sharing/credit terms.[4] Link the CC BY 3.0 license, preserve the supplied attribution and indicate changes; do not infer a blanket license for every Blender asset, trademark or video-host entry.[5]

## New implementation versus execution

The [first-batch contract](provider-phase-contract.md) and [batch-2 contract](provider-phase-batch2.md) define requirements, not passing results.

- **Source-present:** provider capability/detail/episode/resolve contracts; registry resolver; generic open-film and Stremio adapters; resolved-details UI/state. Resolver inspection shows selected provider/card/episode identity checks, safe HTTPS/header filtering, duplicate rejection, up to three same-content candidates and MP4/non-live download capability gating. No cross-title fallback.
- **Batch-2 runtime target, not certified here:** `BundledPlaybackProviders.fromJson` plus `BlenderPlaybackProvider` load the parent-owned four-film registry, fetch the exact current official API video, validate identity/file provenance and resolve current same-film MP4 sources. Missing/mismatched files must become unavailable, never a sample or unrelated URL. License/title attribution and rights links remain visible; no invented ratings or descriptions.
- **Batch-2 integration implemented, pending device proof:** share providers between catalog and resolver; isolate provider errors; use asynchronous details/resolve with stale-selection cancellation; play/download only current resolved identity. Propagate `contentId`, `providerId`, `streamId` into download/offline records without guessing. Parent has integrated these paths; exact-candidate CI and Android media tests remain the verification gate.

| Stage | Recorded status | What is not proved |
| --- | --- | --- |
| Rights and official identity | Four audited film records and primary project pages supplied | Blanket rights for other films, assets, logos or ecosystems. |
| Network/media transport | Four records have HEAD 200, range 206, ffprobe exit 0; official MP4 provenance recorded | Android app networking, catalog wiring or long-term availability. |
| Host short A/V decoding | Each film has two one-second ffmpeg checks at 15s and 300s, both exit 0 | Full-film playback, Android Media3 decode, UI seek/pause or download. |
| Kotlin/unit tests | Files/contracts exist; no fresh execution by this task | Passing exact-candidate regression suite. |
| Bundled app catalog/search/details/resolve | Parent integration and execution pending in this record | Successful app flow merely because remote API probes passed. |
| Android Media3 decode and controls | Not executed by this documentation task | Decoded frames, advancing position, stable pause, real seek, track handling or fallback. |
| Full authorized MP4 download / local playback | Not executed by this documentation task | Actual completed bytes/hash where pinned, retained identity, or local-only playback with network forbidden. |
| Fullscreen/lifecycle/physical-device QA | Not verified here | Instrumentation player construction alone is not a UI/lifecycle pass. |
| Build/test/lint, exact-commit CI and artifact/install readback | Parent-owned; no result claimed here | Release-green or production readiness. |

### Research provenance

Durable summary: `docs/evidence/blender-open-films-audit.json`. Local detailed evidence: `/home/ASHRIN/.hermes/cache/scratch/cinator-v04-openfilms/film-streams.json`, project rights text/HTML and per-film verification JSON in the same directory. Scratch storage is not a durable distributed artifact: the parent should preserve evidence with the release and replace this pointer with its stable location. `verifiedPlayable=true` in that research record means host probe/short-decode success, not Android E2E acceptance.

- **Big Buck Bunny** — official API identity[6]; recorded probe timestamp `2026-10-25T15:07:25.859192+00:00`.
- **Sintel** — official API identity[7]; recorded probe timestamp `2026-10-25T15:07:25.862740+00:00`.
- **Tears of Steel** — official API identity[8]; recorded probe timestamp `2026-10-25T15:07:25.866753+00:00`.
- **Cosmos Laundromat: First Cycle** — official API identity[9]; recorded probe timestamp `2026-10-25T15:07:25.861006+00:00`.

## Remaining engine-coverage gate

A successful four-film E2E can pass only that declared subset. It cannot pass “every bundled source works,” native Nuvio/CloudStream coverage or authorized Anime/K-Drama episodes. Remaining work includes authorized engine/runtime or compatible adapter integration, explicit compatibility status for each requested provider, episode extraction/identity, headers/tracks/failure handling and actual per-provider Android playback/control/download evidence. Unsupported references must stay inactive and explicitly unsupported.

Durable history/favorites/download queues and production signing/device/performance QA remain separate work packages. Catalog caching, resume position or one offline film do not complete those packages. Parent updates must name the exact candidate, device/API/network, film/provider/stream, command/report and actual result for each stage; retain blocked and untested rows rather than declaring the entire phase complete.

## Sources

[1] https://peach.blender.org/about
[2] https://durian.blender.org/sharing
[3] https://mango.blender.org/sharing
[4] https://gooseberry.blender.org/sharing
[5] https://creativecommons.org/licenses/by/3.0
[6] https://video.blender.org/api/v1/videos/bf1f3fb5-b119-4f9f-9930-8e20e892b898
[7] https://video.blender.org/api/v1/videos/0eb052d0-fd51-43e6-aa33-ecdbf77a5d40
[8] https://video.blender.org/api/v1/videos/8533ea43-4271-4a57-9694-e9d0b35e1aa1
[9] https://video.blender.org/api/v1/videos/f507dfdc-e73e-45a4-9778-d758cbe1ce96
