# Cinator — native runtime and playback integration

**Phase 1 remains incomplete.** The app now contains an actual source-built QuickJS native slice in an unexported Android isolated process for one authored, digest-pinned Blender metadata resolver. This proves only a narrow execution/IPC path—not Nuvio's full plugin host. Remote/community plugins, fetch/DOM/WASM/settings bindings and CloudStream DEX execution remain disabled. No new Internet Archive, France24, Anime/K-Drama or external-provider playback is activated. See [outstanding gates](docs/phase1-blockers.md).

Host release and UBSan native suites pass 12/12. The v0.6 exact commit passed Android API35/x86_64 isolated-process execution, distinct UID, admission rejection and Binder retirement instrumentation ([run 40236640366](https://github.com/Xorashrin/Cinetor-Final/actions/runs/40236640366)). The current v0.7 candidate wires the production Android factory to the isolated resolver and revalidates each native candidate against the same fresh official API document. Lossless numeric parsing rejects Android rounding ambiguities. Exact-commit device verification is pending; prior v0.6 media evidence used the Kotlin path. The fixed script accepts already-reviewed official Blender metadata and returns untrusted candidate JSON that the parent must validate; it never receives credentials or network/filesystem bindings.

More → Engine compatibility displays the generated matrix. The separate `plugin_review_ledger.json` accounts for 162 unreviewed descriptor occurrences with unknown licensing/digest/signer fields. Counts are inventory, not working-provider counts. Existing Blender capabilities below remain unchanged.

Native Android Kotlin/Compose app with no login or multiple profiles and no required manual addon URL setup.

## Real playback-provider scope

**Blender Open Films** ships four audited films: Big Buck Bunny, Sintel, Tears of Steel and Cosmos Laundromat: First Cycle. Home contains a dedicated rail. Open a film → load details → **Resolve sources** → choose Play or Download. The provider re-fetches the exact official video API UUID, checks Blender ownership/full-film identity, then requires isolated-native candidate output for each selected same-film MP4 rendition. Native failure, invalid output or unavailable metadata does not fall back to the old Kotlin resolver. It never substitutes a sample for an unavailable film.

CC BY 3.0 project rights and credits must be preserved. Full films include their original credits; attribution/license links are displayed with details. `app/src/main/assets/open_films.json` pins audited identity and preferred quality, not a fake streaming backend.

## Other sources and boundaries

- Cinemeta/TVMaze Movies/Series/Anime/K-Drama entries remain **metadata-only** unless a matching playback provider is explicitly integrated.
- A Stremio HTTP protocol adapter is implemented/tested, but **no Stremio playback addon is activated**: reviewed public-domain addons returned torrents or retired/missing endpoints, not supported direct streams.
- Full Nuvio-native JS plugin and CloudStream `.cs3` runtime compatibility remain **unimplemented**. The searchable 79-repository/162-descriptor bundle is inert reference metadata, not those engines.
- IPTV-org category playlists remain available under More → Live TV; individual channel playback/rights/region availability are not guaranteed.
- No Adult provider/content is bundled; section disabled by default.
- No authorized universal native Anime/K-Drama no-key stream API was verified. Those categories do not silently use Blender films or unrelated clips.

## Existing app capabilities

Scoped debounced search and cache fallback; public-target catalog/artwork network clients; lazy poster rails; scrollable details; Media3 fullscreen/real tracks and same-content fallback. Foreground MP4 downloads validate membership, MIME/size/file signature, preserve canonical content/provider/stream identity, and atomically publish complete files. Backgrounding cancels downloads. No resume/background completion guarantee.

Persistent Watch History/Favorites/restart-safe download queue, production signing, PiP/casting and broad device QA remain separate phases.

## Verification

```sh
./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/verify_apk_bundle.py app/build/outputs/apk/debug/app-debug.apk
```

CI verifies the exact commit's APK and bundled asset bytes, then API35 emulator launch/navigation and provider instrumentation. Instrumentation uses the actual provider/resolver/downloader plus a test-owned visible ExoPlayer: decoded frames, position advance, pause, seek, full download, file length/hash and FileDataSource-only offline playback. It does **not** certify the app's player chrome, fullscreen, every film or physical-device performance. Only actual completed CI logs/results can mark these stages passed; test source alone is not proof. Tests fail on unavailable media/network rather than using fixtures as successful output.

See [provider coverage](docs/provider-coverage-v04.md), [architecture](docs/architecture-and-audit.md), [third-party notices](THIRD_PARTY_NOTICES.md). Debug preview signatures can differ between runners; uninstalling an older debug build may be required and removes local data.
