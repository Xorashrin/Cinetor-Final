# Release readiness — current v0.3.0 preview

## Verdict and evidence boundary

**A limited metadata/live-TV/sample-video preview, not an all-source streaming release.** Full Nuvio plugin execution and the CloudStream extension runtime are not implemented. Bundling repository names, URLs or plugin descriptors does not implement their engines or prove search, extraction, playback or downloads.

This audit compares the working-tree implementation/documentation against `parallel-implementation-contract.md` and `architecture-and-audit.md`. The inspected tree contains concurrent, uncommitted changes; this is not a frozen release-commit certification. This audit did **not** run Gradle, lint, APK/signature checks, CI, stream decoding or physical-device tests. Existing tests and workflow definitions are not fresh passing results. No current-build-green claim is made. Some bulk source output and asset reads were truncated or failed; this report does not claim an independent complete recount of the directory snapshot.

## Coverage: inventory is not execution

| Layer | Current scope | What it does not establish |
| --- | --- | --- |
| Bundled directory inventory | Documented inventory: 79 unique inert repository references, comprising 11 Nuvio and 68 CloudStream references, with 162 nested Nuvio plugin descriptors. Entries are documented as `REFERENCE_ONLY`, `runtimeEnabled=false`. | 79 functioning providers, 162 working plugins, complete ecosystem coverage, or executable engine integration. |
| Cinemeta adapter | Movies/Series metadata through manifest/catalog routes. | Film/episode streams. The recorded manifest declares `catalog`, `meta`, `addon_catalog`, not `stream`. |
| TVMaze adapter | Metadata/search filtered into Home, Anime and K-Drama. | Episode video or downloadable films. Metadata classification is not playback support. |
| IPTV-org adapter | News, Entertainment, Sports, Music and Animation playlist categories; eligible public HTTPS streams are offered to Media3. | Every listed channel playing, geographical availability, content rights, or live downloading. |
| MDN Flower sample | Explicitly labelled CC0 MP4 sample, separate from real film/series metadata. | A resolver for any unavailable movie, anime or drama. |
| Adult / BDIX | Adult disabled by default with no bundled provider; optional bounded BDIX reachability tooling. | Adult engine integration or a functioning BDIX catalog/extractor. |

The active implementation scope is Cinemeta metadata, TVMaze metadata, IPTV playlists and the separate MDN sample. Other registry/reference entries must not be counted as executable coverage. **All-source playback has not been achieved.**

## Current acceptance matrix

**Source-present** is implementation evidence, not device acceptance. **Partial** means only the named subset exists. **Unverified** requires execution evidence for the exact candidate. **Not implemented** does not pass the requested capability.

| Requested capability | Current assessment | Evidence / acceptance still needed |
| --- | --- | --- |
| Native Android, no login/multiple profiles, no mandatory manual source URL | Source-present; device unverified | Compose app and bundled-source configuration are present. Verify install, first launch and automatic loading. |
| Automatic separated feeds and scoped search | Source-present; runtime unverified | Repository/UI wiring, section filters and debounce/cancellation implementation are described in current implementation docs. Verify rapid queries, empty responses, cancellation and section isolation on-device. |
| Movies/Series/Anime/K-Drama availability | Metadata subset only | Cinemeta/TVMaze cards are metadata-only, not full playable episodes. Never substitute a test clip for an unavailable title. |
| Every embedded Nuvio/CloudStream source works | Not implemented | Directory snapshot/inspector is not native plugin or `.cs3` runtime integration. Per-provider search/extraction/playback/download E2E remains unperformed. |
| Posters, dark UI, details and lazy feeds | Source-present; device unverified | Compose/Coil implementation exists. Layout, accessibility, scrolling and low-memory behavior require device checks. |
| Playback, tracks, fullscreen and safe fallback | Partial; device unverified | Media3 panel includes actual track handling and bounded same-content fallback. Actual decoding, seek, pause, orientation/lifecycle and source exhaustion require playback QA. Metadata-only cards remain unplayable. |
| Playback-position persistence | Partial | Player resume preferences are narrower than a watch-history library; they do not establish durable history, favorites or download recovery. |
| Catalog survives restart | Catalog-cache subset; device unverified | Bounded atomic JSON catalog cache is implemented/documented. This persists catalog metadata, not a complete user library. |
| Favorites / Watch History | Not implemented | Need durable user records, actions/UI and restart/migration tests. Catalog cache and player resume preferences do not satisfy this requirement. |
| Downloads | Partial, foreground MP4 subset | Downloader/policy validate eligible identity, HTTPS, MIME/signature, size and atomic publication. Live and unknown-length/chunked downloads are rejected; no resumable/background guarantee or durable restart-safe queue. Media files alone are not a persisted queue/library. |
| Adult isolation | Empty opt-in subset only | Disabled by default; no bundled provider/content. Verify cache clearing and no leakage if scope expands. |
| Current build/test/lint/APK acceptance | Unverified by this audit | Tests and CI definitions exist; fresh exact-candidate execution, signing/bundle checks and downloaded-artifact checksum evidence are required. |
| Production release readiness | Not accepted | Stable production signing, release optimization, install/upgrade QA and broad physical-device/performance evidence remain outstanding. PiP/casting are optional extensions, not delivered capabilities. |

## Remaining work: three broad phases after this preview

These are work packages with acceptance gates, **not** automatic version increments or a promise that three iterations yield v1.0. Each can require several releases. Authorization, supported-provider scope and real evidence govern what can ship.

| Remaining phase | Required work | Exit gate |
| --- | --- | --- |
| 1. Authorized provider engines + per-provider E2E | Define supported providers and implement real compatible execution/resolver adapters, or properly integrate licensed Nuvio/CloudStream runtimes where authorized. Handle identity, episodes, headers, tracks, failures and updates. Retain notices/source obligations; respect content rights and access controls. Unsupported references remain inert and explicit. | A versioned matrix for **each supported provider** records search, metadata, extraction, actual Android decoding, seek/tracks where applicable, same-content fallback, eligible download/local playback or explicit unsupported status. Record exact app/provider versions, device/network and failures. If all bundled sources remain required, assess every reference and disclose unsupported entries; a supported subset cannot pass an all-source criterion. |
| 2. Durable history, favorites and download queue | Persist user records and download jobs independently of catalog cache. Define namespaced identity, progress/completion, file reconciliation, deletion, migrations, process-death recovery and adult isolation. Implement background work only if promised. Resume only with validated server entity/range support; otherwise safely restart. | Restart, force-stop/relaunch, network interruption, storage exhaustion, cancellation, deletion, upgrade and corrupt-state tests preserve consistent records and verified files. Supported background behavior is exercised, not implied. |
| 3. Production signing, device QA and performance | Establish stable controlled signing, candidate artifact verification, release configuration/optimization, security/license review, install/upgrade testing and physical-device/network QA including low-end performance and playback lifecycle. PiP/casting may be added as optional scope with separate acceptance. | Fresh exact-candidate tests/lint/build; APK signature and bundled-byte checks; checksum of the actual downloaded artifact; supported-device install/update, playback/download and measured performance results. Publish known issues and exclusions. Decide release numbering only after gates pass. |

Provider E2E depends on phase 1. Durable-state work can proceed for already supported media without claiming broader provider coverage. Production verification applies to every candidate, not just a hypothetical v1.0.

## Evidence references and handoff

- `README.md`, `docs/architecture-and-audit.md`, `docs/parallel-implementation-contract.md`: present scope, limitations and requested acceptance contract.
- `app/src/main/assets/embedded_catalog.json`, `embedded_directory_snapshot.json`: executable adapter configuration versus inert inventory; inventory totals above follow the current supplied/documented audit, not a new independent recount.
- `app/src/main/java/com/cinator/app/data/`, `MainActivity.kt`, `ui/`, `playback/`, `downloads/`: implementation locations for catalog/cache, UI, player and downloader.
- `app/src/main/java/com/cinator/app/domain/Models.kt`: MDN sample and metadata/reference capability distinctions.
- `app/build.gradle.kts`: preview `versionName = "0.3.0"`; no production signing configuration in the inspected file.
- `app/src/main/AndroidManifest.xml`: no download service or PiP declaration in the inspected manifest.
- `app/src/test/`: existing tests, **not executed by this audit**.
- `docs/evidence/bundled-source-probe.json`: pre-existing bounded metadata/playlist probe records 12 checked/reachable endpoints at its own `capturedAt`. It explicitly says **no stream playback tested**. It is not a fresh probe, every-provider test or current-build result.
- `.github/workflows/build-apk.yml`, `scripts/verify_apk_bundle.py`: automation definitions, not proof of a successful current run.

Before shipping this preview, freeze the candidate revision, execute documented tests/lint/build and APK checks, verify the exact delivered artifact, and attach actual device/playback evidence. A past green build must not certify newer edits. Describe the preview as limited metadata, eligible live streams and a labelled sample—not full embedded Nuvio/CloudStream playback or a complete persistent streaming library.
