# Cinator — v0.3 preview

Native Android Kotlin/Compose app with no login or multiple profiles. Source settings ship inside the APK; no manual addon/playlist URL entry is required.

## Scope

- Built-in `embedded_catalog.json`: Cinemeta Movies/Series **metadata**, TVMaze section-filtered **metadata**, and five IPTV-org playlist categories under More → Live TV.
- `embedded_directory_snapshot.json`: 79 unique Nuvio/CloudStream repository **references** and 162 nested Nuvio plugin descriptors. Read-only, searchable and paged; **not executable extensions and not full engine integration**.
- Automatic per-section loading, scoped debounced search/cancellation and last-good catalog fallback. Bounded atomic JSON cache keeps metadata across restarts.
- Coil poster loading, lazy rails, scrollable detail dialog and dark UI.
- Media3 player with same-instance fullscreen, lifecycle pause/cleanup, actual track selection, seek-on-release and same-content bounded source fallback.
- Foreground MP4 downloader with source identity checks, size/MIME/signature validation and atomic publication. Unknown-length/chunked files and live downloads are rejected. No resume/background-service guarantee.

## Important limitations

Movies/Series/Anime/K-Drama cards from Cinemeta/TVMaze are metadata-only; they cannot play full episodes without a real compatible authorized resolver. This preview does not integrate all Nuvio plugins or CloudStream `.cs3` engines. IPTV links may be offline or geo-restricted; a playlist response is not a playback guarantee. Adult has no bundled provider/content and is disabled by default.

Older v0.2 test-video entries used invented film/anime/drama titles and ratings. They are removed: a generic sample must never be substituted for an unavailable title. Missing data is shown as unavailable, not fabricated.

Persistent Favorites, Watch History, restart-safe download queue, production signing, PiP/casting and broad physical-device performance testing remain future work. Debug signing keys may differ between CI runners; do not assume in-place updates from older previews.

## Build and checks

Requires JDK 17 and Android SDK 35:

```sh
./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug
python3 scripts/verify_apk_bundle.py app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds this same task set, verifies APK signing and compares bundled JSON bytes with source. It uploads APK/checksum and test/lint reports. New version success is only established by the exact commit's completed CI result; old green runs do not verify current edits.

`python3 scripts/probe_bundled_sources.py --output docs/evidence/bundled-source-probe.json` performs bounded public metadata/playlist probes, not stream decoding.

See [architecture and coverage](docs/architecture-and-audit.md) and [third-party notices](THIRD_PARTY_NOTICES.md). TVMaze data is CC BY-SA 4.0; IPTV-org playlist data is CC0. Broadcaster/content rights are separate.
