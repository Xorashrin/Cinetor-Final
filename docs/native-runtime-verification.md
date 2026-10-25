# Narrow native runtime — verification boundary

Implemented source-built QuickJS C/JNI entry point, an unexported `isolatedProcess` Messenger service, an authenticated cancellation-aware broker, and Android instrumentation. Only one compiled authored Blender resolver is admitted by exact ID/SHA. No remote JavaScript, DEX, privileged fetch/files/settings bindings, or generic source input exists in service IPC.

## Parent host verification

- GCC14/CMake3.31.6 actual JNI release tests: 12/12 passed.
- Same suite under `-fsanitize=undefined -fno-sanitize-recover=undefined`: 12/12 passed after opcode macro patch.
- The patch uses unsigned opcode packing and explicit int32_t conversion; vendor provenance preserves upstream and local hashes. It addresses the observed signed-shift UBSan issue only.
- Parent also compiled the production `NativeQuickJs.kt` with Kotlin 2.0.21/JDK17 and executed all twelve host groups against that exact JNI ABI under `-Xcheck:jni`; all passed.
- Node execution of reviewed resolver against all four captured API fixtures: 6/6 passed.
- Android native shared libraries built for arm64-v8a, armeabi-v7a and x86_64. ELF LOAD alignment inspected at 0x4000. Alignment alone is not 16KiB-device compatibility.
- Broker version validation regression initially rejected the real native version containing spaces; parent reproduced and corrected allowed printable ASCII. Device IPC is still an independent gate.

## Not yet established

Until the exact commit's Android isolated-JS instrumentation passes, no device native-execution/isolation success is claimed. Even a pass proves only the authored synchronous subset and the tested UID/process/IPC/rejection/death properties. It does not establish denial of all app-private filesystem/socket access, malicious native containment, total RSS limits, foreign-app Binder attacks, arbitrary code safety, full Nuvio host support, CloudStream DEX compatibility, or every-provider playback.

Existing Blender playback is separately tested. Connecting a native resolver result to playback requires exact official API/candidate and rights revalidation. A service test is not a new content-source grant.
