# Narrow native runtime — verification boundary

Implemented source-built QuickJS C/JNI entry point, an unexported `isolatedProcess` Messenger service, an authenticated cancellation-aware broker, and Android instrumentation. Only one compiled authored Blender resolver is admitted by exact ID/SHA. No remote JavaScript, DEX, privileged fetch/files/settings bindings, or generic source input exists in service IPC.

## Parent host verification

- GCC14/CMake3.31.6 actual JNI release tests: 12/12 passed.
- Same suite under `-fsanitize=undefined -fno-sanitize-recover=undefined`: 12/12 passed after opcode macro patch.
- The patch uses unsigned opcode packing and explicit int32_t conversion; vendor provenance preserves upstream and local hashes. It addresses the observed signed-shift UBSan issue only.
- Parent also compiled the production `NativeQuickJs.kt` with Kotlin 2.0.21/JDK17 and executed all twelve host groups against that exact JNI ABI under `-Xcheck:jni`; all passed.
- Node execution of reviewed resolver against all four captured API fixtures: 6/6 passed.
- Android native shared libraries built for arm64-v8a, armeabi-v7a and x86_64. ELF LOAD alignment inspected at 0x4000. Alignment alone is not 16KiB-device compatibility.
- Broker version validation regression initially rejected the real native version containing spaces; parent reproduced and corrected allowed printable ASCII. The subsequent exact-commit Android IPC run passed as recorded below.

## Exact-commit Android verification

Commit `92b085ac666edab119412f51fcddd18e61f592a9`, [run 40236640366](https://github.com/Xorashrin/Cinetor-Final/actions/runs/40236640366): build and smoke passed. Downloaded raw isolated-JS evidence reports one Android test with 15 stages passed on API35/x86_64. App UID 10148 differs from observed isolated UIDs; 720p/480p live API inputs produce matching candidates; oversized input, wrong ID/digest are rejected; normal Binder retirement and subsequent fresh evaluation pass. Published v0.6 APK was downloaded again and matched the CI SHA-256.

## Not yet established

This pass proves only the authored synchronous subset and the tested UID/process/IPC/rejection/death properties. It does not establish denial of all app-private filesystem/socket access, malicious native containment, total RSS limits, foreign-app Binder attacks, arbitrary code safety, full Nuvio host support, CloudStream DEX compatibility, or every-provider playback.

## Production-playback candidate

The v0.7 candidate now supplies an Android broker-backed executor in the MainActivity factory, preserving worker-thread execution and cancellation. The parent accepts only exact identity/quality/URL/size tuples from the same fresh, provenance-checked document; permissions remain parent-owned. Bounded lossless BigDecimal parsing replaced Android JSONTokener for API and output numbers after a rounding defect reproduced under an AOSP-derived host JSON runtime. The parent reran 30 regression tests under that substitute successfully; this is not device evidence.

The enhanced Android media test includes the numeric rejection regression, requires observed production native callbacks and ties the chosen candidate to online playback, full download and file-only playback. Its runner retains raw schema-2 device evidence and rejects missing native/identity/stage data. This candidate still requires exact-commit Android verification. A service test or unit fixture is not a new content-source grant.
