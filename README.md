# Cinator (`Cinetor-Final`)

Native Android media application built with **Kotlin**, **Jetpack Compose**, and **AndroidX Media3 (ExoPlayer)** featuring strict feed isolation (`Home`, `Anime`, `K-Drama`, `Live TV`, and opt-in `Adult Content`), direct launch (no login or profile wall), typed provider registry, M3U live channel parsing, and authorized MP4 offline downloads.

## Architecture & Implemented Modules (v0.2.0)

1. **Direct Launch & Edge-to-Edge UI (`MainActivity.kt`):**
   - Launches straight into the `Home` feed with zero login or profile-selection screens.
   - Dedicated bottom navigation for `Home` (Movies & Series), `Anime`, `K-Drama`, and `More` (`Live TV`, `Downloads`, `BDIX Wi-Fi Host Probe`, `Nuvio/CloudStream Contract Validator`, and `Adult Content` opt-in).
2. **Strict Per-Section State Isolation (`SectionStateCoordinator`):**
   - Every section (`HOME`, `ANIME`, `KDRAMA`, `LIVETV`, `ADULT`) owns an isolated `SectionStoreState` (`cacheKey`, `providers`, `items`, `searchQuery`, `searchResults`, `errorMessage`).
   - Refreshing, searching, or failing in one section never mutates another section.
3. **Typed Provider Registry & Safe Contract Parsers (`Models.kt`, `DataContractsAndAdapters.kt`):**
   - Maps sources from the media guide into explicit `SourceClass` tiers (`OPEN_MEDIA_STREAM`, `PUBLIC_METADATA_API`, `PUBLIC_M3U_PLAYLIST`, `LOCAL_NETWORK_DISCOVERY`, `AUTHORIZED_ADDON_MANIFEST`, `EXTERNAL_LINK_ONLY`).
   - Third-party web aggregators (`VidSrc`, `Cineby/FlixHQ`, `HiAnime`, `AnimePahe`, `DramaCool`, `KissKH`, `DaddyLive`) are registered strictly as `EXTERNAL_LINK_ONLY` (`canPlayInApp = false`, `canDownload = false`).
   - `NuvioManifestParser` and `CloudStreamRepoParser` validate JSON schemas as inert data contracts (`METADATA_ONLY_NO_DEX_EXECUTION`) without executing remote `.cs3` / DEX code.
4. **Media3 ExoPlayer & Offline MP4 Downloader (`CinatorPlayerPanel.kt`, `CinatorDownloadManager.kt`):**
   - Native `ExoPlayer` supporting Play/Pause, `-10s`/`+10s` seek, interactive seek bar, playback speed (`1.0x`–`2.0x`), and persistent resume positions.
   - Direct MP4 downloader supporting HTTP `Range` requests and offline playback.

## Build & Verify (GitHub Actions)

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```
