package com.cinator.app.engines.nuvio.runtime

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.cinator.app.engines.nuvio.ReviewedEmbeddedScript
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One request, one fresh native runtime, one disposable isolated process.
 * The manifest must declare exported=false and isolatedProcess=true. No host bindings,
 * downloads, plugin registration, or caller-provided code are exposed by this endpoint.
 */
class IsolatedQuickJsService : Service() {
    private val handler = Handler(Looper.getMainLooper()) { message ->
        receive(message)
        true
    }
    private val endpoint = Messenger(handler)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "quickjs-self-watchdog").apply { isDaemon = true }
    }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "quickjs-worker").apply { isDaemon = true }
    }
    private val killed = AtomicBoolean(false)
    private var isolated = false
    // Only the main Handler touches lifecycle state; neither watchdog nor JNI takes its lock.
    private var activeId: String? = null
    private var finishing = false
    private var hardDeadline: ScheduledFuture<*>? = null
    private var engineVersion = "UNAVAILABLE"

    override fun onCreate() {
        super.onCreate()
        isolated = Process.myUid() != applicationInfo.uid
        // A missing isolation manifest must never execute JNI or kill the parent process.
        if (isolated) armDeadline() else stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = if (isolated && !killed.get()) endpoint.binder else null

    private fun armDeadline() {
        hardDeadline?.cancel(false)
        hardDeadline = watchdog.schedule({ killSelf() }, 5, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    private fun receive(message: Message) {
        if (!isolated || killed.get()) return
        // Messenger records the Binder principal before dispatch to this Handler.
        // Binder.getCallingUid() here would instead report the service's own UID.
        if (!IsolatedJsRequest.isAllowedEnvelope(
                message.sendingUid, applicationInfo.uid, message.what, message.arg1,
                message.arg2, if (message.isAsynchronous || message.callback != null) 1 else 0,
                message.obj != null, message.replyTo != null,
            )) return
        val data = try {
            message.peekData()?.also { it.classLoader = null }
        } catch (_: RuntimeException) { null }
        if (message.what == 2) {
            val id = try {
                if (data != null && data.keySet() == setOf("requestId") &&
                    data.get("requestId") is String) data.get("requestId") as String else null
            } catch (_: RuntimeException) { null }
            if (id != null && id == activeId) killSelf()
            return
        }
        val reply = message.replyTo ?: return
        var requestId: String? = null
        val admission = try {
            requestId = (data?.get("requestId") as? String)?.takeIf(IsolatedJsRequest::isCanonicalUuid)
            if (data == null || data.size() != IsolatedJsRequest.KEYS.size ||
                data.keySet() != IsolatedJsRequest.KEYS) {
                IsolatedJsRequest.Admission.Rejected("INVALID_REQUEST")
            } else {
                IsolatedJsRequest.validate(IsolatedJsRequest.KEYS.associateWith { data.get(it) })
            }
        } catch (_: RuntimeException) {
            IsolatedJsRequest.Admission.Rejected("INVALID_REQUEST")
        }
        // Invalid IDs cannot be reflected into replies. The idle deadline still retires us.
        val id = requestId ?: return
        if (finishing || activeId != null || consumed.get()) {
            send(reply, id, error = "BUSY")
            return
        }
        when (admission) {
            is IsolatedJsRequest.Admission.Rejected -> {
                activeId = id
                finishing = true
                // Arm retirement before sending: Binder delivery may itself fail or stall.
                scheduleRetirement()
                send(reply, id, error = admission.error)
            }
            is IsolatedJsRequest.Admission.Accepted -> {
                if (!consumed.compareAndSet(false, true)) {
                    send(reply, id, error = "BUSY")
                    return
                }
                activeId = id
                armDeadline()
                worker.execute { evaluate(admission.request, reply) }
            }
        }
    }

    private fun evaluate(request: IsolatedJsRequest, reply: Messenger) {
        var version = "UNAVAILABLE"
        var result: ByteArray? = null
        var error: String? = null
        try {
            val source = ReviewedEmbeddedScript.get(request.pluginId)
            if (source == null || !ReviewedEmbeddedScript.isApproved(request.pluginId, source)) {
                error = "UNKNOWN_SOURCE"
            } else {
                val bytes = source.toByteArray(Charsets.UTF_8)
                if (bytes.size > IsolatedJsRequest.MAX_SOURCE_BYTES) {
                    error = "UNKNOWN_SOURCE"
                } else {
                    version = NativeQuickJs.version().takeIf { it.length in 1..128 &&
                        it.all { character -> character.code in 32..126 } } ?: "UNAVAILABLE"
                    if (version == "UNAVAILABLE") {
                        error = "NATIVE_UNAVAILABLE"
                    } else {
                        result = NativeQuickJs.evaluate(bytes, request.document,
                            request.uuid.toByteArray(Charsets.UTF_8), request.height, 1_000)
                        if (result.size > IsolatedJsRequest.MAX_RESULT_BYTES) {
                            result = null
                            error = "OUTPUT_TOO_LARGE"
                        }
                    }
                }
            }
        } catch (_: LinkageError) {
            error = "NATIVE_UNAVAILABLE"
        } catch (_: OutOfMemoryError) {
            error = "OUT_OF_MEMORY"
        } catch (failure: IllegalStateException) {
            // Exact enum matching only; never return arbitrary JNI/JS exception text.
            error = when (failure.message) {
                "TIMEOUT", "OUT_OF_MEMORY" -> failure.message
                "OUTPUT_LIMIT" -> "OUTPUT_TOO_LARGE"
                else -> "EVALUATION_FAILED"
            }
        } catch (_: Exception) {
            error = "EVALUATION_FAILED"
        }
        val output = result
        val failure = error
        handler.post {
            if (!killed.get() && !finishing && activeId == request.requestId) {
                finishing = true
                engineVersion = version
                // This independent timer also covers a blocked Binder reply.
                scheduleRetirement()
                send(reply, request.requestId, output, failure)
            }
        }
    }

    private fun send(reply: Messenger, requestId: String, result: ByteArray? = null, error: String? = null) {
        val success = error == null && result != null
        val payload = Bundle().apply {
            putString("requestId", requestId)
            putInt("uid", Process.myUid())
            putInt("pid", Process.myPid())
            putString("engineVersion", engineVersion)
            if (success) putByteArray("result", result) else putString("error", error ?: "EVALUATION_FAILED")
        }
        val response = Message.obtain().apply {
            what = if (success) 101 else 102
            arg1 = 1
            arg2 = 0
            obj = null
            replyTo = null
            data = payload
        }
        try {
            reply.send(response)
        } catch (_: Exception) {
            killSelf()
        }
    }

    private fun scheduleRetirement() {
        // Keep the already-armed hard deadline. The broker ACK/unbind normally retires us;
        // a delayed main looper must not make an already-delivered reply invalid.
        if (hardDeadline == null || hardDeadline?.isDone == true) armDeadline()
    }

    private fun killSelf() {
        if (isolated && killed.compareAndSet(false, true)) Process.killProcess(Process.myPid())
    }

    override fun onUnbind(intent: Intent?): Boolean {
        killSelf()
        return false
    }

    override fun onDestroy() {
        // Never join, await, or close the runtime from another thread: JNI may be hung.
        killSelf()
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        // Static as well as instance state: framework service recreation cannot reuse this process.
        val consumed = AtomicBoolean(false)
    }
}
