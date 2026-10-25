# Isolated native JavaScript implementation gate

This continues the unfinished Phase 1; it is NOT another declaration that policies complete it.

## Current bounded target

Execute one authored, source-controlled, digest-pinned Blender metadata resolver through a real native QuickJS VM in a separate Android isolated process. Input is a bounded copy of already reviewed official metadata. No downloaded repository code, arbitrary source text, network/credential/file bindings, DOM, WASM, settings or native reflection is exposed to JavaScript.

This resolver intentionally supports a small, explicit host contract; it does not implement all Nuvio plugin APIs or CloudStream DEX compatibility. Passing it demonstrates native execution and isolation for that subset only.

## Acceptance gates

- Pin dependency version, exact artifacts/hashes and inspect matching source/license/native ABI provenance; do not bypass Kotlin metadata checks or TLS.
- Unexported `android:isolatedProcess=true` service and explicit component binding. Verify Android `Message.sendingUid` matches the parent app UID before reading the request payload.
- Fixed IPC method identifiers; reject unknown methods, arbitrary scripts, malformed/trailing JSON, >64KiB input, >256KiB output and concurrent unbounded work.
- Service loads only the embedded reviewed script; immutable digest identity checked. JS has no fetch, filesystem, process, Java reflection or credential bindings.
- VM memory/stack limits and native interrupt deadline, plus service-owned watchdog that terminates only its own process on deadline. Parent timeout unbinds, never trusts an arbitrary PID from results for process killing.
- Actual instrumentation verifies distinct UID/PID, successful reviewed script resolution with correct movie identity, blocked app-private-file access and denied direct network permission, request rejection and recovery after a deadline.
- Parent revalidates every returned stream against original official video metadata and same-film identity; no script output is a rights grant.
- Native engine execution, service isolation, app UI playback, and full Nuvio/CloudStream compatibility remain separate test claims.

Only after these gates pass can a narrow native-runtime capability be enabled. If a required dependency/API or runtime test blocks execution, record the blocker rather than publish another policy-only build as progress toward complete runtime support.
