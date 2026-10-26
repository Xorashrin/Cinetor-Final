# Current Phase 1 evidence and blockers

## Proven prior release

v0.7 commit `44b8e1b0d2e076d4fa1683b13438df867cd6eb3b`, Actions `40241754652`: production native-linked Blender resolver, one-film Media3 controls/full download/file-only replay, 335 JVM and 55 Python tests. Original full-engine/provider scope is not complete.

## Current candidate (Android gate pending)

- Source-built QuickJS bridge now settles returned Promises with original deadline/heap/stack budgets and a 1024-job cap. Parent rebuilt and ran 22 real JNI host groups successfully; this is not full Nuvio host support.
- Debug-only, unexported isolated fixed-case service exercises async result, native timeout/OOM/stack/output, absent bindings, private marker/socket denial and separate crash/watchdog fixtures. It never accepts caller source, paths, destinations or PIDs. The watchdog blocks JVM worker/main, not production JNI watchdog injection. Promise/native error cases and private-file positive controls passed in run40247879886, but the Java socket wrapper lost errno and the aggregate containment test failed. A direct Os.socket probe with app-UID positive control replaces it; the complete corrected Android gate remains pending.
- Test-only Internet Archive provider path covers actual metadata/resolver/Media3/download/local replay, not default source activation. Host full-file download and decode succeeded for `BigBuckBunny_328/BigBuckBunny_512kb.mp4`: 43,315,070 bytes, SHA-256 `3aaecd80a8fa3cbe6df8e79364af0412b7da6fa423d14c8c6bd332b32d7626b7`. Test-only Android18-stage acceptance, including the exact full-file hash, passed in run40247879886. Ending-frame numerical comparison with official media did not establish complete-credit retention; do not call that an attribution compliance pass or default activation.

## External/integration constraints

- Nuvio: bounded Promise support is not HTTP continuation, upstream invocation, settings, timers, URL/DOM/crypto host support or approved community-plugin coverage. The pinned upstream WasmBridge is itself TODO; nuvio-engine is a separate torrent engine, not the JS plugin host.
- CloudStream: real pinned MainAPI factories pass in a network-free Kotlin2.4.0 JVM probe. Kotlin2.0.21 compilation rejects upstream metadata2.4.0; crypto/serialization dependency metadata also exceeds the host. No metadata bypass, binary stubs or fabricated HTTP results were used. This is neither Android playback nor arbitrary DEX containment.
- Live TV/Anime/K-Drama: user confirmed native/direct streams only and declined official YouTube/WebView substitution; leave unsupported integrations blocked. Respect publisher-permitted territories, not assumed worldwide availability.
- France Médias Monde [legal notice](https://www.francemm.com/legal-notice) requires prior authorization for embedded video players. DW [free-use terms](https://www.dw.com/en/free-use-of-dw-content/a-77069962) permit unaltered/attributed official embed code conditionally, not a demonstrated direct-HLS distribution grant.
- Blender [primary license](https://peach.blender.org/about/) allows CC BY3.0 distribution and requires the full ending credits for entire-film screening. IA uploader labels do not replace that primary grant or exact-file credits verification.

Current tests must complete on the exact candidate before a new APK is described as device-tested. Passing these bounded tests cannot close the remaining original host/provider scope.
