# Cinator: embedded-source architecture and coverage

## Product contract

No login or multiple profiles, no required manual URL setup. Source configuration ships in APK assets; standard sections auto-load their attached adapters. A stored repository URL or manifest descriptor is **not** an integrated extractor engine. The project must not claim all Nuvio plugins or all CloudStream extensions work simply because their names appear in a bundle.

- Home: Movies, Series and explicitly labelled video samples.
- Anime: Anime metadata, not arbitrary Japanese live-action shows.
- K-Drama: Korean scripted drama, not all Asian shows or Korean cartoons.
- More: Live TV, Downloads, source transparency, optional bounded BDIX reachability check and isolated disabled-by-default Adult section.
- Adult: no provider or content is currently bundled. Enabling an empty section must not trigger a fake example endpoint.

## Bundled assets

### `assets/embedded_catalog.json`

Versioned, typed executable adapter configuration (not executable downloaded code). Current routes are Cinemeta metadata, section-filtered TVMaze metadata and IPTV-org News, Entertainment, Sports, Music and Animation playlists. `scripts/probe_bundled_sources.py` records metadata/playlist availability in `docs/evidence/bundled-source-probe.json`.

Cinemeta's observed manifest declares `catalog`, `meta` and `addon_catalog`, **not `stream`**. Fetch its declared catalog routes; never invent a `/stream` capability. TVMaze also supplies metadata, not episode video. Metadata-only cards must not expose a misleading Play button or redirect playback to a test clip.

### `assets/embedded_directory_snapshot.json`

Read-only snapshot of the supplied Nuvio and CloudStream directories. Parent verified and deduplicated the raw inventory:

- 94 captured directory rows become 79 unique repository references.
- 11 Nuvio repository references contain 162 nested plugin descriptors.
- 68 CloudStream repository references after deduplication.
- 11 unique references lack resolved endpoints (17 raw unresolved rows).
- Two adult directory links were excluded from the bounded audit.

All entries have `REFERENCE_ONLY` status and `runtimeEnabled=false`. These are not working plugin binaries, not every extension in either ecosystem, and not playback verification. The original audit marked some links `descriptor_verified` merely because a directory listed them; that label is deliberately not used for the runtime bundle.

Sources: https://nuvioplugin.com/ and https://cloudstream-apk.com/cloudstream-repositories-extensions/ plus linked public indices. Directory marketing claims are not endorsement or verified feature evidence.

## Runtime and fallback rules

1. UI reads local per-section cache before bounded remote calls. Cached data is labelled; failures must not erase the last good catalog.
2. Only valid responses update cache. Empty or failed network responses never trigger invented media/catalog output.
3. Each request has section-scoped cancellation/generation protection. Stale search results cannot overwrite a newer query or cleared search.
4. One provider failure preserves other providers' results. All configured playlist categories are visited within declared request budgets.
5. Playback fallback is limited to a matching non-empty content identity, same section, same live/VOD mode and declared source candidates. Never switch to a different title, episode, channel or test video.
6. Downloader is foreground-only, validates source membership, HTTPS, MP4 MIME/signature, length and byte budget; uses `.part` and atomic final publication. Without a persisted strong entity validator, downloads restart from zero rather than unsafe byte-range append. No resumable/background-download guarantee.
7. Source URL reachability, valid JSON, a successful APK build, or ZIP integrity do not prove Android playback works.

## Upstream license scope

NuvioMobile and CloudStream identify their source licenses as GPLv3. Cinator's new adapters use protocol data contracts; this work does not bundle their full app engine or opaque third-party JS/DEX code. Copying or incorporating upstream implementation in future requires retained notices and compatible distribution/source obligations. Plugin content rights are separate from software licenses.

TVMaze API data: CC BY-SA 4.0, credit/link back in UI and preserve ShareAlike obligations for redistributed derived data. IPTV-org playlist data is CC0; broadcaster content and service terms remain separate. Cinemeta metadata availability does not grant film playback rights. Refer to `THIRD_PARTY_NOTICES.md`.

## Remaining work / release gates

- Full Nuvio-native plugin execution and CloudStream extension runtime integration remain **unimplemented**. Directory descriptors do not satisfy this requirement.
- Every-provider search/extraction/playback/download testing remains **unperformed**; metadata probes are only a bounded preliminary check.
- Persistent Watch History, Favorites and restart-safe download queue remain separate from catalog cache and player resume preferences.
- Production signing identity, optimized release build, PiP/casting, low-end-device performance and comprehensive install/upgrade/runtime tests remain outstanding.
- v0.3 changes require independent review, Android unit tests, lint, APK build, signature validation, bundle-byte verification and release-download checksum verification before delivery.
