# Cinator Architecture, Source Audit & Provider Policy

## 1. Upstream Open-Source Audit

### 1.1 NuvioMobile (`https://github.com/NuvioMedia/NuvioMobile`)
- **License:** GNU General Public License v3.0 (`GPL-3.0`).
- **Architecture:** Kotlin Multiplatform + Compose Multiplatform (`composeApp`, `androidApp`, `iosApp`) with native player bindings.
- **Addon Protocol Contract:** Uses Stremio-style JSON manifests (`/manifest.json`) declaring `id`, `name`, `version`, `resources` (`catalog`, `meta`, `stream`, `subtitles`), `types`, and `catalogs`.
- **Integration Decision:** Cinator implements a typed, read-only `NuvioManifestParser` data-contract validator in `com.cinator.app.data.NuvioManifestParser`. Cinator does not copy Nuvio binaries or bundle unverified third-party community scrapers.

### 1.2 CloudStream (`https://github.com/recloudstream/cloudstream`)
- **License:** GNU General Public License v3.0 (`GPL-3.0`).
- **Architecture:** Native Android client that loads external repository JSON descriptors (`repo.json`) pointing toplugin lists (`plugins.json`) and compiled Dalvik bytecode archives (`.cs3` / DEX).
- **Security & Policy Decision:** Executing arbitrary remote `.cs3` / DEX bytecode inside the application process via `DexClassLoader` introduces severe code-execution, security, and copyright-compliance risks. Cinator strictly **prohibits** executing `.cs3` or arbitrary plugin bytecode. Instead, `CloudStreamRepoParser` parses repository JSON descriptors as inert metadata contracts.

---

## 2. Source Classification & Capabilities Matrix

Every source from `MEDIA_STREAMING_SOURCES_GUIDE.md` is mapped in `ProviderRegistry` to a single `FeedSection` and classified by its real, verified capability:

| Provider ID | Section | Source Class | In-App Play | Download | Verification & Policy Note |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `open-cinema-mp4` | `HOME` | `OPEN_MEDIA_STREAM` | Yes (ExoPlayer) | Yes (MP4) | Verified CC0/open-licensed MP4 & Apple fMP4 HLS streams with HTTP 206 byte-range support. |
| `tvmaze-home` | `HOME` | `PUBLIC_METADATA_API` | Stream fallback | No | Live HTTPS JSON metadata & search via `api.tvmaze.com` (English scripted series/films). |
| `vidsrc`, `cineby-flixhq`, `yts`, `fmovies`, `cinehd` | `HOME` | `EXTERNAL_LINK_ONLY` | No | No | Reference/official link entries only; no scraping, access-control bypass, or unauthorized stream extraction. |
| `tvmaze-anime` | `ANIME` | `PUBLIC_METADATA_API` | Stream fallback | No | Live HTTPS JSON metadata & search via `api.tvmaze.com/search/shows?q=anime` (`Anime` / Japanese animation). |
| `open-anime-stream` | `ANIME` | `OPEN_MEDIA_STREAM` | Yes (ExoPlayer) | Yes (MP4) | Open-licensed animation stream for native playback, seek, and download verification. |
| `hianime`, `animepahe`, `gogoanime`, `nyaa` | `ANIME` | `EXTERNAL_LINK_ONLY` | No | No | External reference entries only; not claimed as integrated playback/download providers. |
| `tvmaze-kdrama` | `KDRAMA` | `PUBLIC_METADATA_API` | Stream fallback | No | Live HTTPS JSON metadata & search via `api.tvmaze.com/search/shows?q=korean` (Korean dramas). |
| `dramacool`, `kisskh`, `myasiantv` | `KDRAMA` | `EXTERNAL_LINK_ONLY` | No | No | External reference entries only. |
| `iptv-org-public` | `LIVETV` | `PUBLIC_M3U_PLAYLIST` | Yes (HLS) | No | Parses `#EXTINF` and HTTPS `.m3u8` URLs from `iptv-org.github.io`. Live TV download is disabled. |
| `daddylive` | `LIVETV` | `EXTERNAL_LINK_ONLY` | No | No | External reference entry only. |
| `adult-self-hosted-manifest` | `ADULT` | `AUTHORIZED_ADDON_MANIFEST` | Authorized only | No | Disabled by default (`adultOptIn = false`). Requires explicit 18+ opt-in in Settings; isolated store wiped on opt-out. |

---

## 3. Strict Section Isolation

`SectionStateCoordinator` maintains an independent `SectionStoreState` per `FeedSection` (`HOME`, `ANIME`, `KDRAMA`, `LIVETV`, `ADULT`). Each section has its own:
- `cacheKey` (`cinator_store_<section>`)
- `providers` list filtered strictly by `provider.section == section`
- `items`, `searchQuery`, `searchResults`, `isLoading`, and `errorMessage`

A refresh, search query, or network failure in one section never mutates another section's state.
