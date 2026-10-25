package com.cinator.app.engines.nuvio.runtime

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
import com.cinator.app.engines.nuvio.ReviewedEmbeddedScript
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Diagnostic identity only; JSON is still an untrusted candidate, not a playback URL. */
data class IsolatedJsResult(val json: String, val uid: Int, val pid: Int, val engineVersion: String)

/** Fixed reviewed document resolver, using only the explicitly bound isolated service. */
class IsolatedJsBroker(context: Context) {
    private val appContext = context.applicationContext

    suspend fun resolve(document: String, uuid: String, height: Int): IsolatedJsResult =
        resolveRequest(document, uuid, height)

    /** Tests may vary admission pins; unknown pins are rejected before any binding. No source parameter. */
    internal suspend fun resolveRequest(
        document: String,
        uuid: String,
        height: Int,
        pluginId: String = ReviewedEmbeddedScript.BLENDER_RESOLVER_ID,
        sourceSha256: String = ReviewedEmbeddedScript.BLENDER_RESOLVER_SHA256,
    ): IsolatedJsResult {
        val bytes = BrokerValidation.encodeRequest(pluginId, sourceSha256, document, uuid, height)
        // Includes time queued behind other callers; external cancellation is never converted to an error.
        return withTimeoutOrNull(8000L) {
            permits.withPermit {
                withContext(Dispatchers.Main.immediate) {
                    previous?.let {
                        check(it.isRetired()) { "QUARANTINED" }
                        it.disposeDeathWatch()
                        previous = null
                    }
                    val component = ComponentName(appContext, SERVICE_CLASS)
                    try {
                        @Suppress("DEPRECATION")
                        val info = appContext.packageManager.getServiceInfo(component, 0)
                        check(!info.exported && info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0 &&
                            info.applicationInfo.uid == appContext.applicationInfo.uid) { "INVALID_ENDPOINT" }
                    } catch (_: Exception) {
                        throw IllegalStateException("INVALID_ENDPOINT")
                    }
                    val generation = Generation(appContext, component, UUID.randomUUID().toString(),
                        pluginId, sourceSha256, bytes, uuid, height)
                    try {
                        generation.awaitReply()
                    } finally {
                        // NonCancellable keeps the slot until ABORT/unbind and bounded retirement complete.
                        withContext(NonCancellable) {
                            generation.close()
                            previous = generation
                            if (!generation.isRetired()) {
                                withTimeoutOrNull(1000L) { generation.dead.await() }
                            }
                            if (generation.isRetired()) {
                                generation.disposeDeathWatch()
                                previous = null
                            }
                            // An alive endpoint stays quarantined, even if the grace expired.
                        }
                    }
                }
            }
        } ?: throw IllegalStateException("TIMEOUT")
    }

    private class Generation(
        private val context: Context,
        private val component: ComponentName,
        private val requestId: String,
        private val pluginId: String,
        private val digest: String,
        private val document: ByteArray,
        private val uuid: String,
        private val height: Int,
    ) : ServiceConnection {
        val dead = CompletableDeferred<Unit>()
        private var binder: IBinder? = null
        private var remote: Messenger? = null
        private var bound = false
        // No binder yet is NOT proof of retirement after a successful bind request.
        private var bindingAccepted = false
        private var closed = false
        private var finished = false
        private var sent = false
        private var deathLinked = false
        private var continuation: CancellableContinuation<IsolatedJsResult>? = null
        private val main = Handler(Looper.getMainLooper())
        private val replies = Handler(Looper.getMainLooper()) { message ->
            receive(message)
            true
        }
        private val replyMessenger = Messenger(replies)
        private val deathRecipient = IBinder.DeathRecipient {
            dead.complete(Unit)
            main.post { fail("BINDER_DIED") }
        }

        suspend fun awaitReply(): IsolatedJsResult = suspendCancellableCoroutine { pending ->
            continuation = pending
            pending.invokeOnCancellation { main.post { close() } }
            if (!pending.isActive) {
                close()
            } else {
                try {
                    bound = context.bindService(Intent().setComponent(component), this, Context.BIND_AUTO_CREATE)
                    bindingAccepted = bound
                    if (!bound) fail("BIND_FAILED")
                } catch (_: Exception) {
                    fail("BIND_FAILED")
                }
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (name != component || binder != null) {
                fail("INVALID_ENDPOINT")
                return
            }
            binder = service
            try {
                service.linkToDeath(deathRecipient, 0)
                deathLinked = true
                if (!service.isBinderAlive || dead.isCompleted) {
                    fail("BINDER_DIED")
                    return
                }
                remote = Messenger(service)
                // A late connection still establishes a death watch, but never receives work.
                if (closed || finished || continuation?.isActive != true) return
                val request = Message.obtain().apply {
                    what = 1
                    arg1 = 1
                    arg2 = 0
                    obj = null
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString("requestId", requestId)
                        putString("pluginId", pluginId)
                        putString("sourceSha256", digest)
                        putByteArray("document", document)
                        putString("uuid", uuid)
                        putInt("height", height)
                    }
                }
                sent = true
                remote!!.send(request)
            } catch (_: Exception) {
                fail("TRANSPORT_FAILED")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (binder?.isBinderAlive == false) dead.complete(Unit)
            fail("BINDER_DIED")
        }

        override fun onBindingDied(name: ComponentName) {
            if (binder?.isBinderAlive == false) dead.complete(Unit)
            fail("BINDING_DIED")
        }

        override fun onNullBinding(name: ComponentName) { fail("NULL_BINDING") }

        @Suppress("DEPRECATION")
        private fun receive(message: Message) {
            if (closed || finished || !sent || continuation?.isActive != true) return
            if (binder?.isBinderAlive != true || dead.isCompleted) {
                fail("BINDER_DIED")
                return
            }
            try {
                check(message.arg1 == 1 && message.arg2 == 0 && message.obj == null &&
                    message.replyTo == null && message.what in 101..102) { "INVALID_REPLY" }
                val bundle = message.peekData() ?: throw IllegalStateException("INVALID_REPLY")
                val keys = bundle.keySet()
                check(keys == BrokerValidation.replyKeys(message.what)) { "INVALID_REPLY" }
                val fields = keys.associateWith { bundle.get(it) }
                val result = BrokerValidation.decodeReply(fields, message.what, requestId,
                    message.sendingUid, context.applicationInfo.uid, Process.myPid())
                if (Build.VERSION.SDK_INT >= 34) {
                    check(Process.isIsolatedUid(result.uid)) { "INVALID_REPLY" }
                }
                if (message.what == 102) {
                    fail(fields["error"] as String)
                } else {
                    finished = true
                    continuation?.resume(result)
                }
            } catch (_: Exception) {
                fail("INVALID_REPLY")
            }
        }

        private fun fail(error: String) {
            if (closed || finished) return
            finished = true
            continuation?.let { if (it.isActive) it.resumeWithException(IllegalStateException(error)) }
        }

        /** Main-looper only; idempotent on normal replies, timeouts, cancellation and binder death. */
        fun close() {
            if (closed) return
            closed = true
            continuation = null
            replies.removeCallbacksAndMessages(null)
            if (binder?.isBinderAlive == true) {
                try {
                    remote?.send(Message.obtain().apply {
                        what = 2
                        arg1 = 1
                        arg2 = 0
                        obj = null
                        replyTo = null
                        data = Bundle().apply { putString("requestId", requestId) }
                    })
                } catch (_: Exception) { /* endpoint may already have retired */ }
            }
            if (bound) {
                bound = false
                try { context.unbindService(this) } catch (_: Exception) { }
            }
        }

        fun isRetired(): Boolean = BrokerValidation.isRetired(bindingAccepted, binder?.isBinderAlive, dead.isCompleted)

        fun disposeDeathWatch() {
            if (deathLinked) {
                deathLinked = false
                try { binder?.unlinkToDeath(deathRecipient, 0) } catch (_: Exception) { }
            }
            main.removeCallbacksAndMessages(null)
        }
    }

    companion object {
        private const val SERVICE_CLASS = "com.cinator.app.engines.nuvio.runtime.IsolatedQuickJsService"
        private val permits = Semaphore(1)
        // Protected by permits and accessed on the main looper, across all broker instances.
        private var previous: Generation? = null
    }
}

/** Pure admission and wire validation. Payload identity is never sufficient without sendingUid. */
internal object BrokerValidation {
    const val DOCUMENT_LIMIT = 65536
    const val OUTPUT_LIMIT = 262144
    private val canonicalUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val versionPattern = Regex("[ -~]{1,128}")
    private val commonKeys = setOf("requestId", "uid", "pid", "engineVersion")
    private val errors = setOf("INVALID_REQUEST", "DOCUMENT_TOO_LARGE", "UNKNOWN_SOURCE", "BUSY",
        "TIMEOUT", "OUT_OF_MEMORY", "OUTPUT_TOO_LARGE", "EVALUATION_FAILED", "NATIVE_UNAVAILABLE")

    fun isRetired(bindingAccepted: Boolean, binderAlive: Boolean?, deathSignaled: Boolean): Boolean =
        deathSignaled || binderAlive == false || (!bindingAccepted && binderAlive == null)

    fun replyKeys(what: Int): Set<String> = commonKeys + if (what == 101) "result" else "error"

    fun encodeRequest(pluginId: String, digest: String, document: String, uuid: String, height: Int): ByteArray {
        require(pluginId == ReviewedEmbeddedScript.BLENDER_RESOLVER_ID) { "UNKNOWN_PLUGIN" }
        require(digest == ReviewedEmbeddedScript.BLENDER_RESOLVER_SHA256) { "SOURCE_MISMATCH" }
        val source = ReviewedEmbeddedScript.get(pluginId)
        require(source != null && ReviewedEmbeddedScript.isApproved(pluginId, source)) { "SOURCE_MISMATCH" }
        require(canonicalUuid.matches(uuid) && height in 144..2160) { "INVALID_INPUT" }
        require(document.length <= DOCUMENT_LIMIT) { "DOCUMENT_LIMIT" }
        val bytes = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(document))
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("INVALID_UTF8")
        }
        require(bytes.remaining() <= DOCUMENT_LIMIT) { "DOCUMENT_LIMIT" }
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }

    fun decodeReply(fields: Map<String, Any?>, what: Int, requestId: String,
        sendingUid: Int, appUid: Int, appPid: Int): IsolatedJsResult {
        check(what in 101..102 && fields.keys == replyKeys(what)) { "INVALID_REPLY" }
        check(fields["requestId"] is String && fields["requestId"] == requestId) { "INVALID_REPLY" }
        val uid = fields["uid"] as? Int ?: throw IllegalStateException("INVALID_REPLY")
        val pid = fields["pid"] as? Int ?: throw IllegalStateException("INVALID_REPLY")
        val version = fields["engineVersion"] as? String ?: throw IllegalStateException("INVALID_REPLY")
        check(uid >= 0 && uid == sendingUid && uid != appUid && pid > 0 && pid != appPid &&
            versionPattern.matches(version)) { "INVALID_REPLY" }
        if (what == 102) {
            check(fields["error"] is String && fields["error"] in errors) { "INVALID_REPLY" }
            return IsolatedJsResult("", uid, pid, version)
        }
        val bytes = fields["result"] as? ByteArray ?: throw IllegalStateException("INVALID_REPLY")
        check(bytes.size <= OUTPUT_LIMIT) { "INVALID_REPLY" }
        val json = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalStateException("INVALID_REPLY")
        }
        check(StrictJson(json).validArray()) { "INVALID_REPLY" }
        return IsolatedJsResult(json, uid, pid, version)
    }

    // A strict, bounded-depth recognizer avoids org.json's permissive unquoted strings/trailing tokens.
    // It validates syntax only: consumer policy must validate identities and URLs before use.
    private class StrictJson(private val text: String) {
        private var cursor = 0
        fun validArray(): Boolean {
            return try {
                whitespace()
                demand(cursor < text.length && text[cursor] == '[')
                value(0)
                whitespace()
                cursor == text.length
            } catch (_: IllegalStateException) { false }
        }
        private fun demand(ok: Boolean) { if (!ok) throw IllegalStateException("INVALID_JSON") }
        private fun whitespace() { while (cursor < text.length && text[cursor] in " \t\r\n") cursor++ }
        private fun take(c: Char): Boolean {
            if (cursor < text.length && text[cursor] == c) { cursor++; return true }
            return false
        }
        private fun value(depth: Int) {
            demand(depth <= 64)
            whitespace()
            demand(cursor < text.length)
            when (text[cursor]) {
                '[' -> {
                    cursor++; whitespace()
                    if (!take(']')) {
                        do { value(depth + 1); whitespace() } while (take(','))
                        demand(take(']'))
                    }
                }
                '{' -> {
                    cursor++; whitespace()
                    if (!take('}')) {
                        do {
                            whitespace(); string(); whitespace(); demand(take(':'))
                            value(depth + 1); whitespace()
                        } while (take(','))
                        demand(take('}'))
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }
        private fun literal(word: String) {
            demand(text.startsWith(word, cursor)); cursor += word.length
        }
        private fun string() {
            demand(take('"'))
            while (cursor < text.length) {
                val c = text[cursor++]
                if (c == '"') return
                demand(c >= ' ')
                if (c == '\\') {
                    demand(cursor < text.length)
                    when (text[cursor++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            demand(cursor < text.length && text[cursor] in "0123456789abcdefABCDEF"); cursor++
                        }
                        else -> demand(false)
                    }
                }
            }
            demand(false)
        }
        private fun digits() {
            val start = cursor
            while (cursor < text.length && text[cursor] in '0'..'9') cursor++
            demand(cursor > start)
        }
        private fun number() {
            take('-')
            if (!take('0')) {
                demand(cursor < text.length && text[cursor] in '1'..'9'); digits()
            }
            if (take('.')) digits()
            if (take('e') || take('E')) { if (!take('+')) take('-'); digits() }
        }
    }
}
