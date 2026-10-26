package com.cinator.app.engines.nuvio.runtime

import android.app.Service
import android.content.Intent
import android.os.*
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DEBUG ONLY: compiled adversarial fixtures, never a script/path/host/PID execution API.
 * Separate from production admission and watchdog. Manifest must be unexported/isolated.
 * The host-side file/socket probes measure Android UID privileges, not JS bindings.
 */
class NativeContainmentProbeService : Service() {
    private enum class Case { ASYNC_SUCCESS, TIMEOUT, OOM, STACK, OUTPUT_LIMIT, CAPABILITIES, PRIVATE_FILE, SOCKET, WATCHDOG_HANG, SELF_CRASH }
    private val main = Handler(Looper.getMainLooper()) { receive(it); true }
    private val endpoint = Messenger(main)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "containment-independent-watchdog").apply { isDaemon = true }
    }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "containment-probe-worker").apply { isDaemon = true }
    }
    private var isolated = false
    private var active: Case? = null
    private var deadline: ScheduledFuture<*>? = null
    private val killed = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        isolated = Process.myUid() != applicationInfo.uid
        if (isolated) arm(8000) else stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = if (isolated && !killed.get()) endpoint.binder else null

    @Suppress("DEPRECATION")
    private fun receive(message: Message) {
        if (!isolated || killed.get() || message.sendingUid != applicationInfo.uid ||
            message.what !in 1..2 || message.arg1 != 1 || message.arg2 != 0 ||
            message.obj != null || message.callback != null || message.isAsynchronous ||
            (message.what == 1) != (message.replyTo != null)) return
        val case = try {
            val fields = message.peekData() ?: return
            fields.classLoader = null
            if (fields.keySet() != setOf("case") || fields.get("case") !is String) return
            Case.entries.firstOrNull { it.name == fields.get("case") } ?: return
        } catch (_: RuntimeException) { return }
        if (message.what == 2) {
            if (active == case && case !in setOf(Case.WATCHDOG_HANG, Case.SELF_CRASH)) killSelf()
            return
        }
        if (active != null || !consumed.compareAndSet(false, true)) return
        active = case
        arm(if (case == Case.WATCHDOG_HANG) 1500 else 4000)
        val reply = message.replyTo ?: return
        worker.execute { execute(case, reply) }
    }

    private fun execute(case: Case, reply: Messenger) {
        val start = SystemClock.elapsedRealtime()
        if (case == Case.WATCHDOG_HANG || case == Case.SELF_CRASH) {
            if (case == Case.WATCHDOG_HANG) {
                val entered = CountDownLatch(1)
                main.post { entered.countDown(); blockForever() }
                // The STARTED reply witnesses entry into the deliberately blocked main task.
                if (!entered.await(500, TimeUnit.MILLISECONDS)) {
                    send(reply, case, "RESULT", "MAIN_NOT_BLOCKED", "UNEXPECTED_ERROR", start)
                    return
                }
            }
            // No main-loop task is needed for this reply or the independent timer.
            send(reply, case, "STARTED", "ARMED",
                if (case == Case.WATCHDOG_HANG) "BLOCKED_WORKER_AND_MAIN" else "SELF_SIGKILL", start)
            if (case == Case.SELF_CRASH) {
                // Abrupt own-process crash injection; never a caller-provided PID.
                killSelf()
            } else {
                blockForever()
            }
            return
        }
        var version = "NOT_APPLICABLE"
        var outcome: String
        var detail = "NONE"
        var bytes = 0
        var permission = -2
        // Locally derived fixed fixture only; no request can supply a path.
        val marker = if (case == Case.PRIVATE_FILE)
            File(applicationInfo.dataDir, "files/native-containment-private.txt") else null
        when (case) {
            Case.PRIVATE_FILE -> {
                val result = privateFile(requireNotNull(marker))
                outcome = result.first; detail = result.second
            }
            Case.SOCKET -> {
                permission = checkSelfPermission(android.Manifest.permission.INTERNET)
                val result = socket()
                outcome = result.first; detail = result.second
            }
            else -> {
                try {
                    version = NativeQuickJs.version()
                    val result = NativeQuickJs.evaluate(source(case).toByteArray(Charsets.UTF_8),
                        "{}".toByteArray(Charsets.UTF_8), UUID.toByteArray(Charsets.UTF_8), 720,
                        if (case == Case.TIMEOUT) 50 else 1000)
                    bytes = result.size
                    outcome = if (case == Case.CAPABILITIES && result.toString(Charsets.UTF_8) ==
                        "undefined,undefined,undefined,undefined,undefined,undefined,undefined")
                        "NO_HOST_BINDINGS" else if (case == Case.ASYNC_SUCCESS &&
                            result.toString(Charsets.UTF_8) == "async:42") {
                            detail = "async:42"
                            "ASYNC_SETTLED"
                        } else "UNEXPECTED_SUCCESS"
                } catch (error: IllegalStateException) {
                    // Only exact known JNI categories are returned, no exception text reflection.
                    outcome = when (error.message) {
                        "TIMEOUT" -> "TIMEOUT"
                        "OUT_OF_MEMORY" -> "OUT_OF_MEMORY"
                        "ENGINE_ERROR" -> "ENGINE_ERROR"
                        "OUTPUT_LIMIT" -> "OUTPUT_LIMIT"
                        else -> "UNEXPECTED_ERROR"
                    }
                } catch (_: LinkageError) { outcome = "NATIVE_UNAVAILABLE" }
                catch (_: OutOfMemoryError) { outcome = "JVM_OUT_OF_MEMORY" }
                catch (_: Exception) { outcome = "UNEXPECTED_ERROR" }
            }
        }
        send(reply, case, "RESULT", outcome, detail, start, version, bytes, permission, marker?.absolutePath)
    }

    private fun source(case: Case): String = when (case) {
        Case.ASYNC_SUCCESS -> "var module={exports:{getStreams:async function(d,u,h){await Promise.resolve(); return 'async:'+await Promise.resolve('42');}}};"
        Case.TIMEOUT -> "var module={exports:{getStreams:function(d,u,h){while(true){}}}};"
        Case.OOM -> "var a=new ArrayBuffer(32*1024*1024);"
        Case.STACK -> "function recurse(){return recurse();} recurse();"
        Case.OUTPUT_LIMIT -> "var module={exports:{getStreams:function(d,u,h){return 'x'.repeat(262145);}}};"
        Case.CAPABILITIES -> "var module={exports:{getStreams:function(d,u,h){return [typeof std,typeof os,typeof fetch,typeof require,typeof Java,typeof console,typeof XMLHttpRequest].join(',');}}};"
        else -> error("NOT_NATIVE_CASE")
    }

    private fun privateFile(marker: File): Pair<String, String> {
        // Android may hide the app data mount: preserve ENOENT, never relabel it DENIED.
        return try {
            val closeOnExec = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
            val fd = Os.open(marker.absolutePath, OsConstants.O_RDONLY or closeOnExec, 0)
            try {
                Os.read(fd, ByteArray(1), 0, 1)
                "READABLE" to "OPEN_AND_READ_SUCCEEDED"
            } finally { Os.close(fd) }
        } catch (error: ErrnoException) {
            when (error.errno) {
                OsConstants.EACCES -> "DENIED" to "EACCES"
                OsConstants.EPERM -> "DENIED" to "EPERM"
                OsConstants.ENOENT -> "NOT_FOUND" to "ENOENT"
                else -> "IO_ERROR" to "OTHER_ERRNO"
            }
        } catch (_: SecurityException) { "DENIED" to "SECURITY_EXCEPTION" }
        catch (_: Exception) { "IO_ERROR" to "UNEXPECTED_ERROR" }
    }

    private fun socket(): Pair<String, String> {
        // Direct creation preserves errno; no connection, DNS or destination needed.
        val fd = try { Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0) }
        catch (_: SecurityException) { return "DENIED" to "SECURITY_EXCEPTION" }
        catch (error: ErrnoException) {
            return if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM)
                "DENIED" to "EACCES_OR_EPERM" else "IO_ERROR" to "OTHER_ERRNO"
        } catch (_: Exception) { return "IO_ERROR" to "UNEXPECTED_ERROR" }
        // A close failure must never turn successful creation into a denial pass.
        try { Os.close(fd) } catch (_: Exception) { return "IO_ERROR" to "CLOSE_ERROR" }
        return "CREATED" to "TCP_SOCKET_CREATED"
    }

    private fun send(reply: Messenger, case: Case, event: String, outcome: String, detail: String,
        start: Long, version: String = "NOT_APPLICABLE", bytes: Int = 0, permission: Int = -2,
        markerPath: String? = null) {
        val message = Message.obtain().apply {
            what = 101; arg1 = 1
            data = Bundle().apply {
                putString("case", case.name); putString("event", event)
                if (case == Case.PRIVATE_FILE) putString("markerPath", requireNotNull(markerPath))
                putString("outcome", outcome); putString("detail", detail)
                putString("engineVersion", version.take(128))
                putInt("uid", Process.myUid()); putInt("pid", Process.myPid())
                putLong("elapsedMs", SystemClock.elapsedRealtime() - start)
                putInt("resultBytes", bytes); putInt("internetPermission", permission)
            }
        }
        try { reply.send(message) } catch (_: Exception) { killSelf() }
    }
    private fun blockForever() {
        val latch = CountDownLatch(1)
        while (true) { try { latch.await() } catch (_: InterruptedException) { /* Deliberately ignores cancellation. */ } }
    }
    private fun arm(timeoutMs: Long) {
        deadline?.cancel(false)
        deadline = watchdog.schedule({ killSelf() }, timeoutMs, TimeUnit.MILLISECONDS)
    }
    private fun killSelf() {
        if (isolated && killed.compareAndSet(false, true)) Process.killProcess(Process.myPid())
    }
    override fun onUnbind(intent: Intent?): Boolean { killSelf(); return false }
    override fun onDestroy() {
        killSelf()
        worker.shutdownNow(); watchdog.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    private companion object {
        const val UUID = "d5382e6e-7c96-42bb-96e8-155fbe765d5a"
        val consumed = AtomicBoolean(false)
    }
}
