# Third-party notices and source attribution

- **TVMaze public API and returned metadata:** https://www.tvmaze.com/api — CC BY-SA 4.0. Credit TVMaze, link to each returned show's TVMaze URL, and comply with ShareAlike when redistributing derived datasets. API metadata is not an episode streaming license.
- **Cinemeta:** https://v3-cinemeta.strem.io/manifest.json — catalog/meta service. Cinator uses protocol responses, not copied upstream application source; no rights to distribute film/episode streams are inferred. Metadata/artwork rights belong to their respective licensors.
- **IPTV-org playlist data:** https://github.com/iptv-org/iptv — CC0 playlist data; individual broadcast content is separately controlled by its rights holders. Public availability does not imply download/recording rights. Geo-restricted/offline streams are not bypassed.
- **MDN flower sample:** https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4 — supplied in MDN's CC0 video examples. It is a video sample, not Anime/K-Drama or a feature film. Do not substitute it for missing title playback.
- **Jetpack/AndroidX Compose and Media3:** Google/Android Open Source Project — Apache License 2.0 libraries.
- **Kotlin and Kotlin coroutines:** JetBrains — Apache License 2.0 libraries.
- **Coil:** https://github.com/coil-kt/coil — Apache License 2.0 library.
- **NuvioMobile and CloudStream:** https://github.com/NuvioMedia/NuvioMobile and https://github.com/recloudstream/cloudstream — GPLv3 upstream source. Their full app engines and opaque third-party plugin code are not bundled in this preview. A repository descriptor is not an engine port.

Directory references from nuvioplugin.com and cloudstream-apk.com are untrusted, read-only snapshot metadata. No endorsement, security audit of plugin code, comprehensive extension inventory or runtime playback verification is implied.

This notice is a technical attribution record, not a legal determination for all third-party content. Do not remove it when distributing preview artifacts. A production release needs a complete dependency/license inventory and corresponding obligations.

## v0.4 audited Blender open-film subset

Only the following four full-film entries form the audited allowlist. Each is CC BY 3.0. Preserve the project attribution, link to its rights page and license, indicate changes, and retain complete ending credits. This is not a blanket license for all Blender media, third-party assets, logos or trademarks.

- **Big Buck Bunny (2008):** `(c) copyright 2008, Blender Foundation / www.bigbuckbunny.org` — rights: https://peach.blender.org/about/; license: https://creativecommons.org/licenses/by/3.0/; official identity: https://video.blender.org/api/v1/videos/bf1f3fb5-b119-4f9f-9930-8e20e892b898.
- **Sintel (2010):** `© copyright Blender Foundation | www.sintel.org` — rights: https://durian.blender.org/sharing/; license: https://creativecommons.org/licenses/by/3.0/; official identity: https://video.blender.org/api/v1/videos/0eb052d0-fd51-43e6-aa33-ecdbf77a5d40.
- **Tears of Steel (2012):** `(CC) Blender Foundation | mango.blender.org` — rights: https://mango.blender.org/sharing/; license: https://creativecommons.org/licenses/by/3.0/; official identity: https://video.blender.org/api/v1/videos/8533ea43-4271-4a57-9694-e9d0b35e1aa1.
- **Cosmos Laundromat: First Cycle (2015):** `(CC) Blender Foundation | gooseberry.blender.org` — rights: https://gooseberry.blender.org/sharing/; license: https://creativecommons.org/licenses/by/3.0/; official identity: https://video.blender.org/api/v1/videos/f507dfdc-e73e-45a4-9778-d758cbe1ce96.

**Separate ending-credit and logo rights:** Keep all ending credits in full-film streams and downloaded copies. Follow each project's sharing/about terms for credit logos. Permission to retain a logo within the movie's credits is not permission to extract, rebrand or separately redistribute that logo. The film's CC BY 3.0 grant does not silently extend to Blender/project logos or trademarks; no standalone logo license is asserted here.

**Runtime and content scope:** Stremio protocol support is not an integrated Nuvio-native JS/torrent runtime or CloudStream `.cs3`/DEX extension engine. Those engines remain unintegrated. Review upstream source-license obligations before later copying/bundling engine code; source-code licensing does not grant content-stream rights. No reviewed live Stremio direct-playback addon is enabled in the batch-2 contract: torrent-oriented results and HTTP 404s are blockers, not passes. Authorized native Anime/K-Drama playback has not been established. Existing metadata, playlist and sample notices above retain their narrower scope.

Research evidence supports official MP4 transport and short host decoding, not full Android playback/download acceptance. See [v0.4 provider coverage](docs/provider-coverage-v04.md) for named films, execution-stage boundaries and remaining blockers. This subset is not full provider-engine phase completion.
