package com.cinator.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cinator.app.engines.nuvio.runtime.IsolatedJsBroker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Debug-only fixed adversarial fixtures. Not arbitrary-plugin or live-media acceptance. */
@RunWith(AndroidJUnit4::class)
class NativeContainmentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val probeComponent get() = ComponentName(context.packageName,
        "com.cinator.app.engines.nuvio.runtime.NativeContainmentProbeService")
    private val productionComponent get() = ComponentName(context.packageName,
        "com.cinator.app.engines.nuvio.runtime.IsolatedQuickJsService")

    @Test(timeout = 180_000)
    fun fixedNativeContainmentAndProductionRecovery() {
        val evidence = Evidence(File(context.filesDir, "native-containment"))
        val marker = File(context.filesDir, "native-containment-private.txt")
        val pids = mutableSetOf<Int>()
        try {
            evidence.stage("manifest") {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getServiceInfo(probeComponent, 0)
                evidence.observe("manifest", obj("exported" to info.exported, "flags" to info.flags,
                    "processName" to info.processName, "applicationUid" to info.applicationInfo.uid))
                assertFalse(info.exported)
                assertTrue(info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
                assertEquals("${context.packageName}:native_containment_probe", info.processName)
                assertEquals(Process.myUid(), info.applicationInfo.uid)
                assertEquals(PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(android.Manifest.permission.INTERNET))
                val socketControl = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
                Os.close(socketControl)
                evidence.observe("parent_socket_control", obj("created" to true, "closed" to true, "uid" to Process.myUid()))
                // Marker is created and verified by the app UID, never sent over IPC.
                FileOutputStream(marker).use { it.write(MARKER.toByteArray()); it.fd.sync() }
                assertEquals(MARKER, marker.readText())
            }
            evidence.stage("probe_admission") {
                Endpoint(probeComponent).use { endpoint ->
                    endpoint.connect()
                    endpoint.send("TIMEOUT", what = 99)
                    endpoint.send("UNKNOWN_CASE")
                    endpoint.send("TIMEOUT", extraSource = true)
                    assertNull("Unknown messages/source fields must be ignored", endpoint.poll(400))
                    endpoint.send("CAPABILITIES")
                    val reply = endpoint.reply("CAPABILITIES")
                    evidence.observe("probe_admission", reply)
                    assertEquals("NO_HOST_BINDINGS", reply.getString("outcome"))
                    assertEquals(ENGINE, reply.getString("engineVersion"))
                    assertTrue(pids.add(reply.getInt("pid")))
                    endpoint.retire("CAPABILITIES")
                }
            }
            evidence.stage("native_promise") {
                Endpoint(probeComponent).use { endpoint ->
                    endpoint.connect()
                    endpoint.send("ASYNC_SUCCESS")
                    val reply = endpoint.reply("ASYNC_SUCCESS")
                    evidence.observe("native_promise", reply)
                    assertEquals("RESULT", reply.getString("event"))
                    assertEquals("ASYNC_SETTLED", reply.getString("outcome"))
                    assertEquals("async:42", reply.getString("detail"))
                    assertEquals(8, reply.getInt("resultBytes"))
                    assertEquals(ENGINE, reply.getString("engineVersion"))
                    assertTrue(pids.add(reply.getInt("pid")))
                    endpoint.retire("ASYNC_SUCCESS")
                }
            }
            for ((stage, name, expected) in listOf(
                Triple("native_timeout", "TIMEOUT", "TIMEOUT"),
                Triple("native_oom", "OOM", "OUT_OF_MEMORY"),
                Triple("native_stack", "STACK", "ENGINE_ERROR"),
                Triple("native_output_limit", "OUTPUT_LIMIT", "OUTPUT_LIMIT"),
                Triple("private_file_inaccessible", "PRIVATE_FILE", "INACCESSIBLE"),
                Triple("socket_denied", "SOCKET", "DENIED"))) {
                evidence.stage(stage) {
                    Endpoint(probeComponent).use { endpoint ->
                        endpoint.connect()
                        if (name == "PRIVATE_FILE") verifyPrivateMarker("before", marker, evidence)
                        endpoint.send(name)
                        val reply = endpoint.reply(name)
                        evidence.observe(stage, reply)
                        if (name == "PRIVATE_FILE") {
                            verifyPrivateMarker("after", marker, evidence)
                            // Do not canonicalize: the isolated mount namespace may hide this path.
                            assertEquals(marker.absolutePath, reply.getString("markerPath"))
                            val outcome = reply.getString("outcome")
                            val detail = reply.getString("detail")
                            val mode = when {
                                outcome == "DENIED" && detail in setOf("EACCES", "EPERM", "SECURITY_EXCEPTION") ->
                                    "permission_denied"
                                outcome == "NOT_FOUND" && detail == "ENOENT" ->
                                    "isolated_namespace_not_visible"
                                else -> throw AssertionError("Private marker unexpectedly accessible or probe failed: $outcome/$detail")
                            }
                            evidence.observe(stage, reply.put("isolationMode", mode))
                        } else assertEquals(expected, reply.getString("outcome"))
                        assertEquals("RESULT", reply.getString("event"))
                        assertTrue(pids.add(reply.getInt("pid")))
                        assertTrue(reply.getLong("elapsedMs") in 0..4000)
                        if (name in setOf("TIMEOUT", "OOM", "STACK", "OUTPUT_LIMIT")) {
                            assertEquals(ENGINE, reply.getString("engineVersion"))
                            assertEquals(0, reply.getInt("resultBytes"))
                        }
                        if (name == "SOCKET") {
                            assertEquals(PackageManager.PERMISSION_DENIED, reply.getInt("internetPermission"))
                            assertTrue(reply.getString("detail") in setOf("EACCES_OR_EPERM", "SECURITY_EXCEPTION"))
                        }
                        endpoint.retire(name)
                        evidence.observe("${stage}_retirement", obj("binderDeathObserved" to true))
                    }
                }
            }
            for ((stage, name) in listOf("watchdog_death" to "WATCHDOG_HANG", "crash_death" to "SELF_CRASH")) {
                evidence.stage(stage) {
                    Endpoint(probeComponent).use { endpoint ->
                        endpoint.connect()
                        val start = SystemClock.elapsedRealtime()
                        endpoint.send(name)
                        val reply = endpoint.reply(name)
                        evidence.observe(stage, reply)
                        assertEquals("STARTED", reply.getString("event"))
                        assertEquals("ARMED", reply.getString("outcome"))
                        assertEquals(if (name == "WATCHDOG_HANG") "BLOCKED_WORKER_AND_MAIN" else "SELF_SIGKILL",
                            reply.getString("detail"))
                        assertTrue(pids.add(reply.getInt("pid")))
                        // No ACK, unbind, caller-selected PID, or parent kill can cause this death.
                        endpoint.awaitDeath(if (name == "SELF_CRASH") 3000 else 4500)
                        val elapsed = SystemClock.elapsedRealtime() - start
                        if (name == "WATCHDOG_HANG") assertTrue(elapsed in 1000..4500)
                        else assertTrue("Crash must precede the 4s fallback watchdog", elapsed < 3000)
                        assertNull("Hang/crash must not produce a normal result", endpoint.poll(50))
                        evidence.observe("${stage}_death", obj("binderDeathObserved" to true,
                            "elapsedMs" to elapsed, "binderAlive" to endpoint.binder.isBinderAlive))
                    }
                }
            }
            evidence.stage("healthy_production_rebind") {
                // Synthetic identity-matched document, not a network/media acceptance fixture.
                val url = "https://video.blender.org/object-storage/web_videos/$FILM_UUID-720.mp4"
                val document = obj("uuid" to FILM_UUID, "isLocal" to true, "isLive" to false,
                    "url" to "https://video.blender.org/videos/watch/$FILM_UUID",
                    "files" to JSONArray().put(obj("height" to 720, "resolution" to obj("id" to 720),
                        "fileUrl" to url, "size" to 100, "hasAudio" to true, "hasVideo" to true))).toString()
                Endpoint(productionComponent).use { endpoint ->
                    endpoint.connect()
                    val result = runBlocking { withTimeout(12_000) {
                        IsolatedJsBroker(context).resolve(document, FILM_UUID, 720)
                    } }
                    evidence.observe("healthy_production_rebind", obj("uid" to result.uid, "pid" to result.pid,
                        "engineVersion" to result.engineVersion, "rawResultJson" to result.json,
                        "inputKind" to "AUTHORED_SYNTHETIC_METADATA"))
                    identity(result.uid, result.pid)
                    assertTrue(pids.add(result.pid))
                    assertEquals(ENGINE, result.engineVersion)
                    val rows = JSONArray(result.json)
                    assertEquals(1, rows.length())
                    val row = rows.getJSONObject(0)
                    assertEquals(FILM_UUID, row.getString("uuid"))
                    assertEquals(720, row.getInt("height"))
                    assertEquals(100L, row.getLong("size"))
                    assertEquals(url, row.getString("url"))
                    endpoint.awaitDeath(4000)
                    evidence.observe("healthy_production_retirement", obj("binderDeathObserved" to true))
                }
            }
            assertTrue("Marker cleanup failed", marker.delete())
            evidence.finish("PASSED")
        } catch (failure: Throwable) {
            evidence.finish("FAILED", failure)
            throw failure
        } finally {
            if (marker.exists()) marker.delete()
        }
    }

    private fun verifyPrivateMarker(whenChecked: String, marker: File, evidence: Evidence) {
        val exists = marker.isFile
        val readable = marker.canRead()
        val contentMatches = runCatching { marker.readText() == MARKER }.getOrDefault(false)
        evidence.observe("private_file_parent_$whenChecked", obj(
            "markerPath" to marker.absolutePath, "uid" to Process.myUid(),
            "exists" to exists, "readable" to readable, "contentMatches" to contentMatches))
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertTrue("App marker must exist $whenChecked probe", exists)
        assertTrue("App marker must be readable $whenChecked probe", readable)
        assertTrue("App marker contents must match $whenChecked probe", contentMatches)
    }

    private fun identity(uid: Int, pid: Int) {
        assertTrue(uid > 0 && uid != Process.myUid())
        assertTrue(pid > 0 && pid != Process.myPid())
        if (Build.VERSION.SDK_INT >= 34) assertTrue(Process.isIsolatedUid(uid))
    }

    private inner class Endpoint(private val component: ComponentName) : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val dead = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val replies = LinkedBlockingQueue<Message>()
        private val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
            replies.offer(Message.obtain().apply { copyFrom(message) }); true
        })
        private val deathRecipient = IBinder.DeathRecipient { dead.countDown() }
        private var bound = false
        lateinit var binder: IBinder
            private set
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    check(name == component && !::binder.isInitialized)
                    binder = service
                    service.linkToDeath(deathRecipient, 0)
                } catch (error: Throwable) { failure.set(error) }
                finally { connected.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onNullBinding(name: ComponentName) {
                failure.set(IllegalStateException("NULL_BINDING")); connected.countDown()
            }
            override fun onBindingDied(name: ComponentName) {
                failure.set(IllegalStateException("BINDING_DIED")); connected.countDown()
            }
        }
        fun connect() {
            assertFalse(Looper.myLooper() == Looper.getMainLooper())
            bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            assertTrue("Binding timed out", connected.await(8, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Connection failed", it) }
            assertTrue(binder.isBinderAlive)
        }
        fun send(name: String, what: Int = 1, extraSource: Boolean = false) {
            Messenger(binder).send(Message.obtain().apply {
                this.what = what; arg1 = 1
                replyTo = if (what == 2) null else this@Endpoint.callback
                data = Bundle().apply {
                    putString("case", name)
                    if (extraSource) putString("source", "throw new Error('NOT_ADMITTED');")
                }
            })
        }
        fun poll(timeoutMs: Long): Message? = replies.poll(timeoutMs, TimeUnit.MILLISECONDS)
        @Suppress("DEPRECATION")
        fun reply(name: String): JSONObject {
            val message = poll(4500) ?: throw AssertionError("Probe response timed out: $name")
            try {
                assertEquals(101, message.what)
                assertEquals(1, message.arg1)
                assertEquals(0, message.arg2)
                assertNull(message.obj); assertNull(message.replyTo)
                val data = message.data
                val expectedKeys = setOf("case", "event", "outcome", "detail", "uid", "pid", "engineVersion",
                    "elapsedMs", "resultBytes", "internetPermission") +
                    if (name == "PRIVATE_FILE") setOf("markerPath") else emptySet()
                assertEquals(expectedKeys, data.keySet())
                if (name == "PRIVATE_FILE") {
                    assertTrue(data.get("markerPath") is String)
                    assertTrue(data.getString("markerPath")!!.length in 1..1024)
                }
                for (key in setOf("case", "event", "outcome", "detail", "engineVersion")) {
                    assertTrue(data.get(key) is String)
                    assertTrue(data.getString(key)!!.length <= 128)
                }
                for (key in setOf("uid", "pid", "resultBytes", "internetPermission")) assertTrue(data.get(key) is Int)
                assertTrue(data.get("elapsedMs") is Long)
                assertEquals(name, data.getString("case"))
                assertEquals(message.sendingUid, data.getInt("uid"))
                identity(data.getInt("uid"), data.getInt("pid"))
                return JSONObject().apply {
                    for (key in data.keySet()) put(key, data.get(key))
                    put("sendingUid", message.sendingUid)
                }
            } finally { message.recycle() }
        }
        fun retire(name: String) {
            // Success before an ACK is required; this is not a hard-watchdog probe.
            try { send(name, what = 2) } catch (_: DeadObjectException) { }
            awaitDeath(4000)
        }
        fun awaitDeath(timeoutMs: Long) {
            assertTrue("No Binder death observed", dead.await(timeoutMs, TimeUnit.MILLISECONDS))
            assertFalse(binder.isBinderAlive)
        }
        override fun close() {
            if (bound) { context.unbindService(connection); bound = false }
            if (::binder.isInitialized) runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            while (true) (replies.poll() ?: break).recycle()
        }
    }

    private class Evidence(directory: File) {
        private val file = File(directory, "result.json")
        private val start = SystemClock.elapsedRealtime()
        private val stages = JSONArray()
        private val observations = JSONObject()
        private val root = obj("schemaVersion" to 1, "test" to "NativeContainmentTest", "status" to "RUNNING",
            "appUid" to Process.myUid(), "appPid" to Process.myPid(), "sdkInt" to Build.VERSION.SDK_INT,
            "startedAtEpochMs" to System.currentTimeMillis(), "stages" to stages, "observations" to observations,
            "expectedEngineVersion" to ENGINE,
            "notVerified" to JSONArray(listOf("Arbitrary third-party JS or full Nuvio compatibility",
                "Production service hard-watchdog injection (debug fixture uses its own timer)",
                "Native-code hang injection (fixture blocks JVM worker and main looper)",
                "Foreign-UID Binder attack", "All filesystem/network destinations", "Total process RSS bound",
                "Live metadata, playback, download, or new source admission")))
        init { check(directory.isDirectory || directory.mkdirs()); flush() }
        fun <T> stage(name: String, action: () -> T): T {
            val stage = obj("name" to name, "status" to "RUNNING", "startedElapsedMs" to elapsed())
            stages.put(stage); flush()
            try { return action().also { stage.put("status", "PASSED") } }
            catch (error: Throwable) { stage.put("status", "FAILED").put("exception", error.javaClass.name); throw error }
            finally { stage.put("finishedElapsedMs", elapsed()); flush() }
        }
        fun observe(name: String, value: JSONObject) { observations.put(name, value); flush() }
        fun finish(status: String, error: Throwable? = null) {
            root.put("status", status).put("elapsedMs", elapsed())
            if (error != null) root.put("exception", error.javaClass.name)
            flush()
        }
        private fun elapsed() = SystemClock.elapsedRealtime() - start
        private fun flush() {
            val temp = File(file.parentFile, "result.json.tmp")
            FileOutputStream(temp).use { it.write(root.toString(2).toByteArray()); it.fd.sync() }
            check(temp.renameTo(file)) { "EVIDENCE_RENAME_FAILED" }
        }
    }
    companion object {
        private const val MARKER = "AUTHORED_APP_PRIVATE_CONTAINMENT_MARKER"
        private const val ENGINE = "QuickJS 2026-06-04 (04be246)"
        private const val FILM_UUID = "bf1f3fb5-b119-4f9f-9930-8e20e892b898"
        private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
    }
}
