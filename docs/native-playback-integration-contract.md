# Native resolver → playback integration contract

Scope: connect the existing fixed reviewed Blender QuickJS resolver to production provider resolution. Not full Nuvio or CloudStream. Fail closed; do not silently fall back to Kotlin when native execution fails.

## Shared interfaces

- New pure Kotlin `com.cinator.app.providers.BlenderCandidateExecutor`: `fun interface` with `fun execute(document: String, uuid: String, height: Int): String`. Returns untrusted JSON, never a StreamSource.
- `BlenderPlaybackProvider(raw: String, http: TextHttpClient = BoundedHttpClient(), candidateExecutor: BlenderCandidateExecutor? = null)`. Null retains legacy JVM-path coverage only; production factory always passes an executor.
- `BundledPlaybackProviders.fromJson(raw: String, http: TextHttpClient = BoundedHttpClient(), candidateExecutor: BlenderCandidateExecutor? = null)` forwards it.
- New Android-only `AndroidBundledPlaybackProviders.fromContext(context: Context, http: TextHttpClient = BoundedHttpClient(), onNativeResult: ((IsolatedJsResult) -> Unit)? = null): List<PlaybackProvider>` reads bundled open_films asset and passes a real broker-backed executor. Observer is diagnostics only, not an executor override.
- New Android-only `AndroidBlenderCandidateExecutor(context: Context, onNativeResult: ((IsolatedJsResult) -> Unit)? = null) : BlenderCandidateExecutor` wraps `IsolatedJsBroker.resolve` using `runBlocking` only on a non-main worker thread. Reject main-thread use before blocking. Propagate interruption/cancellation and native failure. No source string parameter, fake result, blocking main looper or fallback. UI already calls ProviderResolver via runInterruptible(IO).

## Parent acceptance of native data

Use the same fresh official API document that passed account/channel/UUID/privacy/full-duration checks. Bound document/output UTF-8, arrays (8 playlists, 256 total rows), and strict numeric fields. For up to 3 existing same-film ordered quality choices, native output must be [] or exactly one {uuid,height,url,size}. Match full identity, exact official URL, strict numeric quality and positive safe-integer size to the actual API entry, not just host allowlisting. Reject additional/duplicate/malformed/unknown-field output. Empty native result means no result for that quality; exception/invalid result must not return prior partial sources. Preserve download permission from reviewed film license/credits and fresh API downloadEnabled, never from JS. Recheck interruption before return. Do not introduce another film/provider fallback.

## Blocking review corrections

- Validate raw API number tokens losslessly before Android `org.json` can round fractional literals to integral `Double` values. Cover height, resolution.id, size and duration, including nested playlist files; a modern host `org.json` pass is not sufficient.
- Run `android_lossless_numeric_admission` in the same Android provider instrumentation test. These deliberately invalid local documents are negative tests only; actual native execution/playback still requires live official metadata and observed service results.
- Require test-owned schema-2 provider evidence in CI. Missing/stale tar, absent native callbacks, mismatched native/online/download/offline identity or missing stages must fail even if `adb` exits zero. Preserve raw device JSON instead of replacing it with a host success summary.

## Ownership

- Provider worker: BlenderCandidateExecutor.kt, BlenderPlaybackProvider.kt, BundledPlaybackProviders.kt, new BlenderNativeCandidateTest.kt only. TDD vertical cases + focused JVM tests.
- Android worker: AndroidBlenderCandidateExecutor.kt, AndroidBundledPlaybackProviders.kt, MainActivity.kt factory wiring, new unit wiring test only. No provider files/build files.
- Device worker: ProviderPlaybackE2ETest.kt only; use Android factory and observer to assert real isolated native results occurred during production resolver path. Save count/UID/engineVersion/request-dependent candidate evidence. Existing one-film playback/download/file-only assertions must remain intact. Fail if observer never fires. Avoid tests changing expected identity or manufacturing evidence.
- Parent: builds, merge/review, docs, versions, CI, release. No agent commits, full Gradle builds or shared file edits.

Preserve all previous security policies. Use targeted tests RED then GREEN; baseline zero failures. Android instrumentation remains a separate exact-commit gate after combined build.
