# Native QuickJS slice — exact implementation contract

Repo `/home/ASHRIN/Cinetor-Final`; parent owns vendor source/Gradle/manifest/assets/version/CI/provider integration/git. New native implementation is ONLY fixed authored `ReviewedEmbeddedScript` resolving copied Blender metadata; no opaque plugin code, no remote script loading, no full Nuvio/CloudStream compatibility claim. Existing policy allowlists stay empty for third-party code.

## Ownership
1 native agent: `app/src/main/cpp/cinator_quickjs.c`, `app/src/main/cpp/CMakeLists.txt`, `engines/nuvio/runtime/NativeQuickJs.kt`, host tests under `app/src/test/native/` ONLY.
2 service agent: `engines/nuvio/runtime/IsolatedQuickJsService.kt` ONLY + pure validator if needed `IsolatedJsRequest.kt` and tests. No IPC globals file other than owned validator.
3 broker agent: `engines/nuvio/runtime/IsolatedJsBroker.kt` ONLY + its tests (if pure state).
4 test agent: `app/src/androidTest/java/com/cinator/app/IsolatedQuickJsTest.kt` ONLY. Parent config adds runner selection.

## Native API
Package `com.cinator.app.engines.nuvio.runtime`:
```
internal object NativeQuickJs {
 init { System.loadLibrary("cinator_quickjs") }
 external fun evaluate(source:ByteArray, document:ByteArray, uuid:ByteArray, height:Int, timeoutMs:Int):ByteArray
 external fun version():String
}
```
`evaluate` bounds source<=131072, document<=65536, uuid exactly36 canonical, height144..2160, timeoutMs50..2000, output<=262144, JS runtime16MiB heap/256KiBstack, CLOCK_MONOTONIC interrupt deadline. Fresh runtime/context eachcall; no JS-libc,std/os,moduleloader/network/Java bindings. Pass three data via JS_NewStringLen/properties `__parentDocument`, `__expectedUuid`, `__preferredHeight`, not source interpolation. Execute supplied source (onlyservice supplies compiledconstant), fixed `module.exports.getStreams(__parentDocument, __expectedUuid, __preferredHeight)` returnmustJSString UTF8 bytes; errors sanitized Java IllegalStateException enumwords; propercleanup. Hosttest samecore using buildmacro harness or JNIactual Java bytearrays; memorylooptimeout tested authoredtests only. CMake source at `vendor/quickjs` under root accessible relative cpp ../../../..; files quickjs.c dtoa.c libregexp.c libunicode.c cutils.c; includes allheaders. Define _GNU_SOURCE CONFIG_VERSION="2026-06-04", C11, libm, pthread asneeded; no quickjs-libc. Link16KiB pages targetoptions.

## IPC v1 (identical service/client/test)
Use Messenger, `what=1` EVALUATE, `arg1=1`, `arg2=0`, `obj=null`, `replyTo` parentMessenger. Bundle exactly:
`requestId:String` randomUUID; `pluginId:String`; `sourceSha256:String`; `document:ByteArray`; `uuid:String`; `height:Int`.
NO source code in IPC. bytebudget<=64KiBdocument; source fixed ReviewedEmbeddedScript.get(id), compare advertisedSHA with constant and isApproved. Requestprincipal `message.sendingUid == applicationInfo.uid`, not Binder.getCallingUid onHandler, drop foreignUID. Unexportedisolatedservice declared parent, process :isolated_quickjs.
Reply `what=101` success or `102` error, arg1=1,arg2=0,obj=null,replyTo=null; data:
`requestId:String`, `uid:Int` actualProcess.myUid, `pid:Int`, `engineVersion:String`, plus `result:ByteArray` forsuccess OR `error:String` fixedenum.
No raw logs/documents/URLs in errors. Client binds endpoint identity via frameworkconnection; compares `sendingUid` ==payloaduid, different appuid; API34 isIsolatedUid optional guarded. Validate requestid and payload before returning. PID diagnostic only NEVERparentkillPID.
Service must handle exactly ONE valid request per process; BUSY error for latercalls no newworker; worker singlethread; separate watchdog kills ownProcess at5seconds if no return; after success/error retain existing 5s hard deadline while awaiting ACK/unbind; do not introduce an earlier post-reply kill. `what=2` ACK/ABORT withrequestId only, sameUID; kills only self. Parentcleanup sendwhat2; explicitunbind; no concurrent reuse, one global Semaphore1; bindtimeoutoverall8s, transportcancel ABORT, no fallback inprocess. Native timeout1second default (budget smaller thanwatchdog).

## Broker API
```
data class IsolatedJsResult(val json:String,val uid:Int,val pid:Int,val engineVersion:String)
class IsolatedJsBroker(context:Context) {
 suspend fun resolve(document:String, uuid:String, height:Int):IsolatedJsResult
}
```
FixedpluginID/digest from ReviewedEmbeddedScript parentchecks both. MainthreadMessengerhandler callback; scope cancellation closes binding/sendsabort, ignoredlatecallback; wait/death nohang; binddead/timeout error sanitized. Can expose internal `resolveRequest` test seam with pluginID/digest defaults for rejectiontests, but NO callersuppliedsource. Broker uses applicationContext, no UI/threadIO; ByteArray <=65536. Concurrency semaphore and retirement must wait binder death or bounded grace before newbind toavoidserviceBUSY loop; workerreply only acceptedwhile binder alivegeneration. Don't claim sandbox fullysecure justuidtested.

## Device tests
Actual service via broker, current fixedsource and original `app/src/test/resources/blender` API fixtures not inassets; instrumentation can fetch bounded official API or use tiny synthetic APIshape with *actual exactBlenderUUID/fileUrl* and clearly fixture-only result: proves input-dependentJS not realtransport. Prefer actualBoundedHttpClient fetching auditedBBB API (alreadyexistingproviderE2E). Assert source resultJSON exactURL/height, actualnative engineVersion, UID differs app, PID differs, currentSDK>=34 isolateduid check; resolve2heights returnsdifferentURL, sequentialrepeat newprocess works. Unknownplugin/digest/oversize rejected pre-execution; require freshhealth aftererror. Isolationcheck permissionmanifest via PackageManager serviceInfo isolatedProcess exportedfalse flags, noclaim actualsocketdenial until tested. Evidence files `files/isolated-js-e2e/result.json` stages and limits, no rawprivateinput. Native host timeout/OOM separate tests. Need CI instrumentation runner class list parenthandles.

No child git/Gradle daemon/manifest/dependency edits. Use TDD where executable. Return real tool results, files, assumptions promptly.
