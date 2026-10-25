# Native QuickJS host verification

This suite loads the production JNI shared library, evaluates real QuickJS, and checks
ordinary UTF-8 byte arrays. No mocked interpreter or fabricated results are used.
The reviewed-resolver case reads the actual authored source from its Kotlin file and
uses clearly synthetic Blender-shaped metadata; it is not a network/playback test.

## Build and run (no Gradle or Android SDK needed)

Set `JAVA_HOME` to a JDK 17+ installation and use CMake 3.22+:

```sh
cmake -S /home/ASHRIN/Cinetor-Final/app/src/main/cpp \
  -B /home/ASHRIN/.hermes/cache/scratch/cinator-quickjs-host/cmake \
  -DBUILD_TESTING=ON -DCMAKE_BUILD_TYPE=Release
cmake --build /home/ASHRIN/.hermes/cache/scratch/cinator-quickjs-host/cmake --parallel 2
ctest --test-dir /home/ASHRIN/.hermes/cache/scratch/cinator-quickjs-host/cmake --output-on-failure
```

CTest's Java declaration is an ABI twin of the Kotlin object (same package,
`INSTANCE`, instance native methods, byte-array signatures). The same Java test
runner was separately compiled and executed against the **compiled production
NativeQuickJs.kt object** using Kotlin 2.2.20, JDK 17 and `-Xcheck:jni`; all twelve
cases passed. This distinguishes ABI verification from Android service isolation,
which requires the parent's device tests.

## Contract and error categories

* Fresh context/runtime per call, 16 MiB tracked allocator ceiling (including
  allocation headers), 256 KiB QuickJS stack ceiling, CLOCK_MONOTONIC interrupt.
* Source <=131072 bytes, document <=65536 bytes, lowercase canonical 36-byte UUID,
  height 144..2160, timeout 50..2000 milliseconds, output <=262144 UTF-8 bytes.
* Inputs are copied without holding JVM pins during JS. Strict UTF-8; embedded NUL
  is allowed in document strings, but not executable source.
* Three readonly global primitive properties pass data without interpolation.
  A fixed getStreams invocation returns a JS string or a native Promise fulfilling
  to a string. Pending jobs run only while that Promise remains pending, on the
  same JNI thread, with the original monotonic deadline, heap/stack limits and a
  fixed 1024-job cap. The deadline and OOM latch are checked between jobs too.
  A pending Promise with no jobs fails immediately; there is no callback wait.
  Synchronous strings and settled Promises do not drain unrelated jobs. Arbitrary
  thenables are invalid, without inspecting their properties.
* No quickjs-libc, module loader, std/os/network/Java bindings, or raw diagnostics.
* IllegalStateException message is only INVALID_INPUT, OUT_OF_MEMORY, TIMEOUT,
  ENGINE_ERROR, INVALID_RESULT, OUTPUT_LIMIT, PENDING_PROMISE or JOB_LIMIT.
  Rejections/recursion/syntax/JS exceptions share ENGINE_ERROR; native code never
  reads untrusted exception text. JS_PromiseResult returns an owned duplicate;
  the Promise and its fulfillment are each released exactly once.
* CMake checks the exact source commit/version and every vendored file hash.
  Upstream core uses GNU C11 for its `asm` Atomics instruction; the JNI bridge is
  strict C11 with -Wall/-Wextra/-Werror. Android library uses 16 KiB page link flags.

## Bounded Promise regression verification

The host suite now has 22 CTest cases, including Promise.resolve UTF-8 strings,
async/await and chained fulfillment, sanitized rejection (hostile getters), pending
with no jobs, self-requeue, exact 1024/1025-job boundaries, invalid fulfillment,
output limits, OOM (including caught OOM), recursion, job timeout and fresh-runtime
recovery. All load real JNI under `-Xcheck:jni`; each adversarial error is followed
by a healthy arithmetic evaluation. Release and fatal UBSan builds passed 22/22.
Evidence is under `/home/ASHRIN/.hermes/cache/scratch/phase1-promise-native/`:
`red-resolve.log`, `red-jobs.log`, `green-release.log`, `green-ubsan.log`.
Final ownership cleanup and leftover-job tests were re-run three times per case
in each build (66/66 executions each): `green-release-repeat.log` and
`green-ubsan-repeat.log`.

This is bounded core Promise support, **not full Nuvio host compatibility**.
No fetch, timers, network callbacks, public IPC or production source pins changed.
This host run does not establish Android isolation/device behavior or leak freedom.

## Additional sanitizer finding (subagent run, superseded below)

An ASan/UBSan build passed the first nine groups with JVM leak detection disabled,
then UBSan stopped in pinned upstream `quickjs.c:35549` on a signed left shift
(`168 << 24`) during the fresh-runtime test. LeakSanitizer with leak detection enabled
had earlier stopped on JDK libjvm/libjli retained allocations. Neither run establishes
sanitizer-green or leak-free behavior. The parent owns vendoring; upstream was not
patched or unpinned. Release CTest (36 repeated executions) and actual Kotlin/JNI
verification remained green.

Parent reproduced the opcode-packing signed-shift defect and locally changed M2/M3/M4 to unsigned shifts with explicit int32_t conversion, retaining int-vararg ABI. PROVENANCE.json records original and modified hashes. GCC14/CMake3.31.6 release CTest then passed12/12; a fresh `-fsanitize=undefined -fno-sanitize-recover=undefined` build also passed12/12. This does not claim ASan/LeakSanitizer or arbitrary-engine safety.

Only the service should call this internal bridge with ReviewedEmbeddedScript's
compiled constant. Host-authored pathological scripts are test-only. Android NDK
build and actual isolated UID/process/IPC behavior are parent integration gates.
