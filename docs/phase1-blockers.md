# Phase 1 unresolved gates — not a completion report

The original requested phase remains **incomplete**. Preparatory modules and status reporting do not run Nuvio's native JavaScript plugins or CloudStream `.cs3` extensions. No runtime compatibility, safe isolation, or all-plugin playback claim is made.

## Current deliverable boundary

- Existing Blender Open Films provider remains active; v0.6 exact-commit Android one-film playback/download/file-only regression passed. It is not a replacement for the requested engines.
- Full Nuvio plugin admission remains disabled. A separate source-pinned native QuickJS slice now executes one authored, digest-pinned Blender resolver in an unexported isolated service. API35/x86_64 live-metadata/UID/admission/rebind evidence passed in run 40236640366. Native host deadline/OOM/stack tests pass, but Android fault injection, file/socket denial and full host bindings remain unverified. Production native-result-to-playback wiring is a distinct gate.
- New CloudStream policy validates descriptors and bounded embedded ZIP/digest metadata only. It does not class-load DEX, fetch plugin binaries or grant execution.
- New Internet Archive adapter is independently implemented against public API shapes. No item is default-enabled: exact file transport, codec and Android playback proof are incomplete. Metadata/uploader license claims do not independently establish rights.
- New external-provider policy does not return native streams. No reviewed routes are activated. It cannot supply Anime/K-Drama raw-video playback or download.
- Compatibility status and per-plugin ledger explicitly retain unknown license/signature/source/rights/E2E fields. Repository references are not a full ecosystem inventory.

## Blocking acceptance criteria

| Gate | Evidence still required |
|---|---|
| Actual Nuvio runtime | Beyond the v0.6 source-built fixed-script/service proof: production native-result-to-playback verification; Nuvio host APIs; network/credential boundaries; Android hard-failure recovery; specifically authorized ecosystem plugins; broader device compatibility/E2E tests. |
| Actual CloudStream compatibility | Reviewed/reproducibly rebuilt provider code or accepted restricted execution model; no assumption that PathClassLoader or isolated UID contains arbitrary DEX; license/media-rights clearance and explicit compatibility limits. |
| Per-plugin coverage | Immutable plugin identities/versions/artifacts; source and publisher authorization; digest/signature/revocation; software-license/dependency notices; rights/territory/playback/download decisions; Android evidence and expiry/review dates. |
| Anime/K-Drama | Real authorized direct playback or separately approved official embedded/external playback. No available universal no-key raw-stream API has been verified. Metadata remains metadata. |
| Internet Archive | Reachable exact media response, complete-file/codec validation and Android E2E after primary rights review. Current selected candidate is not activated. |
| Official Live TV | Broadcaster-approved integration path plus exact Android playback evidence. France24 public HLS transport exists, but default bundling permission has not been established from retrieved evidence. |
| User-hosted services | A known user-authorized Jellyfin/nginx server/library. No endpoint or credentials have been provided, and no fake default backend may be substituted. |

## Next valid decisions

Continue engineering a narrowly reviewed runtime subset only with its missing supply-chain, execution and device-test gates. If those gates cannot be satisfied, stop and report the blocker. Do not redefine Phase 1 as finished simply because all entries have a status, or advance to a later phase as if this one passed.
