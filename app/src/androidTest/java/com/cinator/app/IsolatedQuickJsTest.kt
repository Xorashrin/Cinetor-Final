package com.cinator.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cinator.app.data.BoundedHttpClient
import com.cinator.app.engines.nuvio.ReviewedEmbeddedScript
import com.cinator.app.engines.nuvio.runtime.IsolatedJsBroker
import com.cinator.app.engines.nuvio.runtime.IsolatedJsResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real HTTP -> parent broker -> isolated Messenger service -> native QuickJS.
 * No fixture fallback, direct in-process native call, arbitrary JS or process killing.
 * Device/network failures fail the test and preserve the last completed evidence stage.
 */
@RunWith(AndroidJUnit4::class)
class IsolatedQuickJsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val component get() = ComponentName(context.packageName,
        "com.cinator.app.engines.nuvio.runtime.IsolatedQuickJsService")

    @Test(timeout = 180_000)
    fun officialBunnyResolvesInFreshIsolatedNativeProcesses() {
        val evidence = Evidence(File(context.filesDir, "isolated-js-e2e"))
        try {
            evidence.stage("installed_service_manifest") {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getServiceInfo(component, 0)
                evidence.observe("manifest", obj("flags" to info.flags, "exported" to info.exported,
                    "processName" to info.processName, "applicationUid" to info.applicationInfo.uid))
                assertFalse("The isolated endpoint must not be exported", info.exported)
                assertTrue("Missing public ServiceInfo.FLAG_ISOLATED_PROCESS",
                    info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
                assertEquals("${context.packageName}:isolated_quickjs", info.processName)
                assertEquals(context.applicationInfo.uid, Process.myUid())
            }
            val document = evidence.stage("bounded_official_api_fetch") {
                val raw = BoundedHttpClient(maxSizeBytes = DOCUMENT_LIMIT).get(API_URL)
                val bytes = raw.toByteArray(Charsets.UTF_8)
                evidence.observe("http", obj("url" to API_URL, "outcome" to "RESPONSE",
                    "utf8Bytes" to bytes.size, "sha256" to sha256(bytes)))
                assertTrue(bytes.size <= DOCUMENT_LIMIT)
                val data = JSONObject(raw)
                assertEquals(FILM_UUID, data.getString("uuid"))
                assertEquals("https://video.blender.org/videos/watch/$FILM_UUID", data.getString("url"))
                assertTrue(data.getBoolean("isLocal"))
                assertFalse(data.getBoolean("isLive"))
                assertEquals("blender", data.getJSONObject("account").getString("name"))
                assertEquals("video.blender.org", data.getJSONObject("account").getString("host"))
                assertEquals("blender_open_movies", data.getJSONObject("channel").getString("name"))
                assertEquals("video.blender.org", data.getJSONObject("channel").getString("host"))
                assertEquals(1, data.getJSONObject("privacy").getInt("id"))
                assertFalse(data.optBoolean("blacklisted", false))
                // Independent exact-file checks against the live response, before JS runs.
                expectedFile(data, 720)
                expectedFile(data, 480)
                raw
            }
            val broker = IsolatedJsBroker(context)
            val seenPids = mutableSetOf<Int>()
            fun healthy(name: String, height: Int): IsolatedJsResult = evidence.stage(name) {
                // A second, observation-only binding witnesses actual Binder death, rather
                // than mistaking a changed numeric PID for proof of process retirement.
                ServiceProbe().use { probe ->
                    probe.connect()
                    val result = runBlocking { withTimeout(12_000) { broker.resolve(document, FILM_UUID, height) } }
                    evidence.observe(name, obj("uid" to result.uid, "pid" to result.pid,
                        "engineVersion" to result.engineVersion, "rawResultJson" to result.json,
                        "requestedHeight" to height))
                    assertIdentity(result.uid, result.pid)
                    assertEquals(EXPECTED_ENGINE_VERSION, result.engineVersion)
                    assertTrue("A retired process must not be reused", seenPids.add(result.pid))
                    assertTrue(result.json.toByteArray(Charsets.UTF_8).size <= 262_144)
                    val rows = JSONArray(result.json)
                    assertEquals(1, rows.length())
                    val row = rows.getJSONObject(0)
                    val expected = expectedFile(JSONObject(document), height)
                    assertEquals(FILM_UUID, row.getString("uuid"))
                    assertEquals(height, row.getInt("height"))
                    assertEquals(expected.getString("fileUrl"), row.getString("url"))
                    assertEquals(expected.getLong("size"), row.getLong("size"))
                    probe.awaitDeath()
                    evidence.observe("${name}_retirement", obj("binderDeathObserved" to true,
                        "binderAliveAfterDeath" to probe.binder.isBinderAlive, "pid" to result.pid))
                    result
                }
            }
            val high = healthy("native_720", 720)
            val low = healthy("native_480", 480)
            evidence.stage("same_film_input_dependent_urls") {
                assertFalse(JSONArray(high.json).getJSONObject(0).getString("url") ==
                    JSONArray(low.json).getJSONObject(0).getString("url"))
                assertEquals(high.engineVersion, low.engineVersion)
            }
            val repeat = healthy("fresh_720_repeat", 720)
            evidence.stage("repeat_is_deterministic") { assertEquals(high.json, repeat.json) }

            for ((name, oversized) in listOf("ascii" to " ".repeat(DOCUMENT_LIMIT + 1),
                "multibyte" to "é".repeat(DOCUMENT_LIMIT / 2 + 1))) {
                evidence.stage("parent_rejects_oversize_$name") {
                    var rejected = false
                    try {
                        runBlocking { withTimeout(12_000) { broker.resolve(oversized, FILM_UUID, 720) } }
                    } catch (failure: IllegalArgumentException) {
                        evidence.observe("oversize_$name", obj("exception" to failure.javaClass.name,
                            "error" to failure.message, "characters" to oversized.length,
                            "utf8Bytes" to oversized.toByteArray(Charsets.UTF_8).size))
                        assertEquals("DOCUMENT_LIMIT", failure.message)
                        rejected = true
                    }
                    assertTrue("Oversized input unexpectedly reached a successful result", rejected)
                }
                healthy("healthy_after_oversize_$name", 720)
            }
            // Direct IPC exercises the service's own admission boundary without introducing
            // a production API that accepts source text or allowing unreviewed code to run.
            for ((name, plugin, digest) in listOf(
                Triple("unknown_plugin", "not-an-approved-plugin", ReviewedEmbeddedScript.BLENDER_RESOLVER_SHA256),
                Triple("wrong_digest", ReviewedEmbeddedScript.BLENDER_RESOLVER_ID, "0".repeat(64)))) {
                evidence.stage("ipc_rejects_$name") {
                    ServiceProbe().use { probe ->
                        probe.connect()
                        val requestId = UUID.randomUUID().toString()
                        val reply = probe.request(Bundle().apply {
                            putString("requestId", requestId)
                            putString("pluginId", plugin)
                            putString("sourceSha256", digest)
                            putByteArray("document", document.toByteArray(Charsets.UTF_8))
                            putString("uuid", FILM_UUID)
                            putInt("height", 720)
                        })
                        val data = reply.data
                        evidence.observe(name, obj("what" to reply.what, "sendingUid" to reply.sendingUid,
                            "uid" to data.getInt("uid"), "pid" to data.getInt("pid"),
                            "engineVersion" to data.getString("engineVersion"), "error" to data.getString("error")))
                        assertEquals(102, reply.what)
                        assertEquals(1, reply.arg1)
                        assertEquals(0, reply.arg2)
                        assertTrue(reply.obj == null && reply.replyTo == null)
                        assertEquals(requestId, data.getString("requestId"))
                        assertTrue(data.containsKey("uid") && data.containsKey("pid"))
                        assertEquals(reply.sendingUid, data.getInt("uid"))
                        assertIdentity(data.getInt("uid"), data.getInt("pid"))
                        assertEquals("UNKNOWN_SOURCE", data.getString("error"))
                        assertFalse(data.containsKey("result"))
                        probe.acknowledge(requestId)
                        probe.awaitDeath()
                        evidence.observe("${name}_retirement", obj("binderDeathObserved" to true))
                    }
                }
                healthy("healthy_after_$name", 480)
            }
            evidence.finish("PASSED")
        } catch (failure: Throwable) {
            evidence.finish("FAILED", failure)
            throw failure
        }
    }

    private fun assertIdentity(uid: Int, pid: Int) {
        assertTrue("Service UID must differ from the parent", uid > 0 && uid != Process.myUid())
        assertTrue("Service PID must differ from the parent", pid > 0 && pid != Process.myPid())
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue("Framework does not classify service UID as isolated", Process.isIsolatedUid(uid))
        }
    }

    private fun expectedFile(data: JSONObject, height: Int): JSONObject {
        val rows = mutableListOf<JSONObject>()
        fun collect(array: JSONArray?) {
            for (index in 0 until (array?.length() ?: 0)) rows += array!!.getJSONObject(index)
        }
        collect(data.optJSONArray("files"))
        val playlists = data.optJSONArray("streamingPlaylists")
        for (index in 0 until (playlists?.length() ?: 0)) {
            collect(playlists!!.getJSONObject(index).optJSONArray("files"))
        }
        val exact = rows.filter { it.optInt("height") == height &&
            it.optJSONObject("resolution")?.optInt("id") == height &&
            it.optString("fileUrl") == "https://video.blender.org/object-storage/web_videos/$FILM_UUID-$height.mp4" &&
            it.optBoolean("hasAudio") && it.optBoolean("hasVideo") && it.optLong("size") > 0 }
        assertTrue("Official full-film ${height}p file missing; no fixture or quality fallback", exact.isNotEmpty())
        assertEquals("Conflicting official file sizes", 1, exact.map { it.getLong("size") }.distinct().size)
        return exact.first()
    }

    private inner class ServiceProbe : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private val connectionFailure = AtomicReference<Throwable?>()
        private var bound = false
        lateinit var binder: IBinder
            private set
        private val deathRecipient = IBinder.DeathRecipient { died.countDown() }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    check(name == component)
                    binder = service
                    service.linkToDeath(deathRecipient, 0)
                } catch (failure: Throwable) { connectionFailure.set(failure) }
                finally { connected.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) { /* linkToDeath is the evidence. */ }
            override fun onNullBinding(name: ComponentName) {
                connectionFailure.set(IllegalStateException("NULL_BINDING")); connected.countDown()
            }
            override fun onBindingDied(name: ComponentName) {
                connectionFailure.set(IllegalStateException("BINDING_DIED")); connected.countDown()
            }
        }
        fun connect() {
            assertFalse("Never block Android's main looper", Looper.myLooper() == Looper.getMainLooper())
            bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
            assertTrue("Could not bind isolated service", bound)
            assertTrue("Isolated service bind timed out", connected.await(8, TimeUnit.SECONDS))
            connectionFailure.get()?.let { throw AssertionError("Service connection failed", it) }
            assertTrue(binder.isBinderAlive)
        }
        fun request(fields: Bundle): Message {
            val completed = CountDownLatch(1)
            val response = AtomicReference<Message?>()
            val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
                // Message is recycled after dispatch; own a copy, including sendingUid.
                val copy = Message.obtain().apply { copyFrom(message) }
                if (response.compareAndSet(null, copy)) completed.countDown() else copy.recycle()
                true
            })
            Messenger(binder).send(Message.obtain().apply {
                what = 1; arg1 = 1; arg2 = 0; data = fields; replyTo = callback
            })
            assertTrue("Service admission response timed out", completed.await(8, TimeUnit.SECONDS))
            return checkNotNull(response.get())
        }
        fun acknowledge(requestId: String) {
            if (binder.isBinderAlive) {
                try {
                    Messenger(binder).send(Message.obtain().apply {
                        what = 2; arg1 = 1
                        data = Bundle().apply { putString("requestId", requestId) }
                    })
                } catch (_: android.os.DeadObjectException) { /* Retirement raced ACK. */ }
            }
        }
        fun awaitDeath() {
            assertTrue("Service did not retire its process after one response", died.await(4, TimeUnit.SECONDS))
            assertFalse("Dead endpoint remains alive", binder.isBinderAlive)
        }
        override fun close() {
            if (bound) { context.unbindService(connection); bound = false }
            if (::binder.isInitialized) runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
    }

    private class Evidence(directory: File) {
        private val start = SystemClock.elapsedRealtime()
        private val file = File(directory, "result.json")
        private val stages = JSONArray()
        private val observations = JSONObject()
        private val root = obj("schemaVersion" to 1, "test" to "IsolatedQuickJsTest", "status" to "RUNNING",
            "startedAtEpochMs" to System.currentTimeMillis(), "sdkInt" to Build.VERSION.SDK_INT,
            "appUid" to Process.myUid(), "appPid" to Process.myPid(), "stages" to stages,
            "observations" to observations, "expectedEngineVersion" to EXPECTED_ENGINE_VERSION,
            "scope" to "Live official metadata; fixed reviewed JS; broker IPC; native engine; Binder death",
            "notVerified" to JSONArray(listOf("App-private file access denial", "Socket/network denial from isolated UID",
                "Native loop timeout and OOM (separate host tests)", "Foreign-UID IPC attempts",
                "No bind on parent rejection (no transport spy)", "Playback or media download",
                "Arbitrary third-party JS compatibility")),
            "isolatedUidApiCheck" to if (Build.VERSION.SDK_INT >= 34) "REQUIRED" else "UNAVAILABLE_BEFORE_API_34")
        init { check(directory.isDirectory || directory.mkdirs()); flush() }
        fun <T> stage(name: String, action: () -> T): T {
            val item = obj("name" to name, "status" to "RUNNING", "startedElapsedMs" to elapsed())
            stages.put(item); flush()
            try {
                return action().also { item.put("status", "PASSED") }
            } catch (failure: Throwable) {
                item.put("status", "FAILED").put("exception", failure.javaClass.name)
                throw failure
            } finally { item.put("finishedElapsedMs", elapsed()); flush() }
        }
        fun observe(name: String, value: JSONObject) { observations.put(name, value); flush() }
        fun finish(status: String, failure: Throwable? = null) {
            root.put("status", status).put("elapsedMs", elapsed())
            // Do not persist raw exception messages, arbitrary inputs or source text.
            if (failure != null) root.put("exception", failure.javaClass.name)
            flush()
        }
        private fun elapsed() = SystemClock.elapsedRealtime() - start
        private fun flush() { file.writeText(root.toString(2), Charsets.UTF_8) }
    }

    companion object {
        private const val DOCUMENT_LIMIT = 65_536
        private const val FILM_UUID = "bf1f3fb5-b119-4f9f-9930-8e20e892b898"
        private const val API_URL = "https://video.blender.org/api/v1/videos/$FILM_UUID"
        // NativeQuickJs.version() returns this vendored CONFIG_VERSION + commit string.
        private const val EXPECTED_ENGINE_VERSION = "QuickJS 2026-06-04 (04be246)"
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
    }
}
