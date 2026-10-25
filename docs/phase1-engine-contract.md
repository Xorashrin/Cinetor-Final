# v0.5 Phase 1 preparatory work — original acceptance unchanged

**Full Phase 1 is NOT complete.** The original requirement remains actual Nuvio native plugin and CloudStream runtime compatibility plus authorized per-provider Android playback/download evidence. Status matrices, digest checks, sandbox schemas, protocol adapters, external links, and blocked rows do not substitute for that requirement. No reduced scope has been accepted. This document describes a preparatory policy/coverage increment only.

A separate per-plugin ledger must track immutable version, artifact/source provenance, digest, signer/authorization/revocation, software license and source obligations, security review/grants, rights/territories/download permission, review dates and exact Android/release evidence. Unknown fields stay unknown; 79 repository references and 162 descriptor occurrences are not proof of complete plugin enumeration or approval.

Base `/home/ASHRIN/Cinetor-Final`, HEAD `19683fb71cc1efc50e5e9835177b8420b640d76e`. Parent owns shared models, Gradle/manifest/CI, integration, commits/releases. Children must not commit/push/start Gradle daemons. First read this contract. Use exclusive NEW files per task. Pure tests via `/home/ASHRIN/.hermes/cache/scratch/cinator-jvm-tooling/run_tests.py`; final Android/CI parent-owned.

## Evidence labels for the preparatory increment (not completion criteria)

A source is `SUPPORTED` only when search/catalog, details, exact content/episode identity, direct authorized stream transport and Android playback are evidenced. Download is separate and requires explicit permission plus actual file verification. A protocol parser, repo URL, metadata, torrent/hash, external website or loaded code is not playback support.

Phase output includes a versioned compatibility matrix for all bundled directory references with one of:
- `SUPPORTED_E2E`: exact tested provider/media/version and Android evidence.
- `SUPPORTED_PROTOCOL`: adapter tested, no default live provider enabled.
- `EXTERNAL_AUTHORIZED`: provider-controlled app/embed only; no raw stream extraction.
- `REQUIRES_USER_SERVER`: compatible but needs user-owned endpoint/auth; not preconfigured playback.
- `BLOCKED_SECURITY`: runtime requires untrusted arbitrary JS/DEX and no acceptable isolation/signing.
- `BLOCKED_RIGHTS_OR_API`: no reviewed authorized direct-stream API.
- `REFERENCE_ONLY`: descriptor inventory, untested/incompatible.

Do not mark Nuvio/CloudStream runtime complete by running arbitrary directory plugins. No access-control/DRM bypass, anti-bot evasion, debrid/piracy, adult-source discovery, raw YouTube extraction, or dynamic unsigned DEX/JS. Adult remains disabled/empty. Anime/KDrama metadata remains separated and unplayable unless an authorized path is proven.

## Shared compatibility contracts (compatibility agent owns)
Package `com.cinator.app.compat` NEW:
```
enum class CompatibilityStatus { SUPPORTED_E2E, SUPPORTED_PROTOCOL, EXTERNAL_AUTHORIZED, REQUIRES_USER_SERVER, BLOCKED_SECURITY, BLOCKED_RIGHTS_OR_API, REFERENCE_ONLY }
data class EngineCapability(val catalog:Boolean,val search:Boolean,val details:Boolean,val episodes:Boolean,val directPlayback:Boolean,val download:Boolean,val externalPlayback:Boolean)
data class CompatibilityEntry(val id:String,val ecosystem:String,val name:String,val status:CompatibilityStatus,val capability:EngineCapability,val reason:String,val evidenceUrl:String?,val version:String?)
data class CompatibilityMatrix(val schemaVersion:Int=1,val entries:List<CompatibilityEntry>)
object CompatibilityMatrixParser { fun parse(raw:String):CompatibilityMatrix }
```
Reject duplicate IDs, unknown statuses, control chars, invalid URLs, unsupported true capabilities for blocked/reference statuses. Parent creates `assets/provider_compatibility.json` generated from existing 79 refs plus active providers.

## Runtime boundaries

### Nuvio native JS
Upstream GPLv3 QuickJS host downloads and executes plugin JS with privileged fetch/DOM/crypto/WASM/settings bindings. Default-directory plugins are untrusted community code. An in-process/WebView evaluator is forbidden. A safe runtime would require an Android `isolatedProcess` service, no direct network/filesystem/credentials, parent-mediated bounded HTTPS allowlist, signed embedded plugin digest allowlist, per-plugin rights review, binder quotas/deadlines, no WASM/DOM expansion until tested. This phase may implement **manifest/digest validation and sandbox contract only**; without a working QuickJS isolated service + authorized plugin E2E, status is `BLOCKED_SECURITY`, not supported.

### CloudStream .cs3
Upstream `.cs3` is DEX loaded by PathClassLoader and can execute Android code. Dynamic directory DEX execution is forbidden. This phase may implement repository/plugin descriptor validation, APK/DEX digest/signature allowlist and status reporting only. No .cs3 `SUPPORTED` unless code is audited, licensed, embedded, digest-pinned, no arbitrary Android API escape, and provider media rights/E2E proven. Android isolated process cannot by itself guarantee DEX safety; absence of a capability-safe host is `BLOCKED_SECURITY`.

### Supported adapters
- Existing Blender provider: `SUPPORTED_E2E` one-film device evidence; four-film provider transport-resolved.
- Existing Stremio HTTP adapter: `SUPPORTED_PROTOCOL`; no default direct addon verified.
- IPTV-org: protocol/playlist loading plus selected channel playback only if exact official channel provenance and Android test; otherwise protocol subset.
- Internet Archive: implement independently from public API, not copy unlicensed extension. Restrict allowlist/queries to items with machine-readable public-domain/CC licenses and exact downloadable MP4; rights field must be accepted before streams. Test actual API transport and Android playback for one selected item before supported E2E.
- Jellyfin/nginx: `REQUIRES_USER_SERVER` unless a user-owned server is configured. No default endpoint or credentials in APK.
- Anime/KDrama: official embed/external app only until direct authorized API exists. Never label external YouTube/Viki/Crunchyroll as native playback.

## Parent integration after agents
Matrix shown in More Source Transparency; active adapters separate from blocked/reference rows. New provider UI uses existing typed provider contracts/resolver. Every actual default provider in CatalogRepository. Cache metadata only; streams resolved fresh. Evidence JSON validated by `scripts/provider_e2e.py` but parent must independently inspect instrumentation outputs.

## Agent ownership wave 1
1 compat contracts + parser/tests; 2 matrix generator script/tests; 3 Nuvio sandbox/digest contracts+tests; 4 CloudStream descriptor/digest contracts+tests; 5 IA API/rights research scratch; 6 IA provider implementation+tests; 7 IPTV official-channel provenance research scratch; 8 Jellyfin/nginx protocol/status audit scratch; 9 Anime/KDrama external authorized UI adapter design; 10 engine license/security review scratch.

Wave 2 tests/reviews/integration: matrix coverage, Nuvio security regressions, CloudStream security regressions, IA live provider verification, provider UI integration, Android IA/selected IPTV E2E if approved, evidence report, docs/attribution, final security review, exact-commit CI/release parent.
