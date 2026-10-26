# Phase 1 unresolved gates — not a completion report

The original requested phase remains **incomplete**. Preparatory modules and status reporting do not run Nuvio's native JavaScript plugins or CloudStream `.cs3` extensions. No runtime compatibility, safe isolation, or all-plugin playback claim is made.

## Current deliverable boundary

- Existing Blender Open Films provider remains active; v0.7 commit44b8e1b passed native-linked Android one-film playback/download/file-only regression in run40241754652. It is not a replacement for the requested ecosystems.
- Full Nuvio plugin admission remains disabled. A separate source-pinned native QuickJS slice now executes one authored, digest-pinned Blender resolver in an unexported isolated service. API35/x86_64 live-metadata/UID/admission/rebind evidence passed in run 40236640366. Native host deadline/OOM/stack tests pass, but Android fault injection, file/socket denial and full host bindings remain unverified. Production native-result-to-playback passed in v0.7. Debug-only Android containment probes and bounded Promise/job processing are the current implementation batch; their Android gate is pending.
- CloudStream policy validates descriptors and bounded embedded ZIP/digest metadata only; no DEX execution is enabled. Parent replayed actual upstream MainAPI construction/catalog/movie-load/extractor-link factories with Kotlin2.4.0 and official dependency closure. Host Kotlin2.0.21 rejects current upstream metadata; unchanged current dependencies also require newer metadata support. A compatible-toolchain migration or audited backport is required before Android integration. Static JVM factories are not playback or .cs3 hosting.
- New Internet Archive adapter is independently implemented against public API shapes. No item is default-enabled: the exact BigBuckBunny_328 / BigBuckBunny_512kb.mp4 complete43,315,070-byte file and H.264/AAC decode now pass host checks, and test-only Android playback/full-download/file-only replay passed18 stages in run40247879886; complete-credit correspondence and default activation remain unresolved. Metadata/uploader license claims do not independently establish rights.
- New external-provider policy does not return native streams. No reviewed routes are activated. It cannot supply Anime/K-Drama raw-video playback or download.
- Compatibility status and per-plugin ledger explicitly retain unknown license/signature/source/rights/E2E fields. Repository references are not a full ecosystem inventory.

## Blocking acceptance criteria

| Gate | Evidence still required |
|---|---|
| Actual Nuvio runtime | Beyond the v0.7 native-linked playback proof: Nuvio host APIs (Promise/HTTP continuation, invocation, settings/timers/URL/DOM/crypto); network/credential boundaries; Android hard-failure recovery; specifically authorized ecosystem plugins; broader device compatibility/E2E tests. |
| Actual CloudStream compatibility | Reviewed/reproducibly rebuilt provider code or accepted restricted execution model; no assumption that PathClassLoader or isolated UID contains arbitrary DEX; license/media-rights clearance and explicit compatibility limits. |
| Per-plugin coverage | Immutable plugin identities/versions/artifacts; source and publisher authorization; digest/signature/revocation; software-license/dependency notices; rights/territory/playback/download decisions; Android evidence and expiry/review dates. |
| Anime/K-Drama | Real authorized direct playback or separately approved official embedded/external playback. No available universal no-key raw-stream API has been verified. The user explicitly declined YouTube/WebView substitution and accepted retaining these integrations as blocked; availability must follow publisher-permitted countries. Metadata remains metadata. |
| Internet Archive | Reachable exact media response, complete-file/codec validation and Android E2E after primary rights review. Current selected candidate is not activated. |
| Official Live TV | Broadcaster-approved integration path plus exact Android playback evidence. France Médias Monde legal notice requires prior written authorization for embedded players. DW permits official embedding conditionally, but the user declined YouTube/WebView substitution; direct-HLS redistribution permission and Android native E2E remain unestablished. |
| User-hosted services | A known user-authorized Jellyfin/nginx server/library. No endpoint or credentials have been provided, and no fake default backend may be substituted. |

## Next valid decisions

Continue engineering a narrowly reviewed runtime subset only with its missing supply-chain, execution and device-test gates. If those gates cannot be satisfied, stop and report the blocker. Do not redefine Phase 1 as finished simply because all entries have a status, or advance to a later phase as if this one passed.
