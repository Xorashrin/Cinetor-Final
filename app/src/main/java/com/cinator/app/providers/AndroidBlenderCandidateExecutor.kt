package com.cinator.app.providers

import android.content.Context
import android.os.Looper
import com.cinator.app.engines.nuvio.runtime.IsolatedJsBroker
import com.cinator.app.engines.nuvio.runtime.IsolatedJsResult
import kotlinx.coroutines.runBlocking

/** Executes only the broker's fixed reviewed resolver; returned JSON remains untrusted. */
class AndroidBlenderCandidateExecutor(
    context: Context,
    private val onNativeResult: ((IsolatedJsResult) -> Unit)? = null,
) : BlenderCandidateExecutor {
    private val broker = IsolatedJsBroker(context)

    override fun execute(document: String, uuid: String, height: Int): String =
        NativeWorkerBridge.execute(Looper.myLooper() == Looper.getMainLooper()) {
            val result = broker.resolve(document, uuid, height)
            onNativeResult?.invoke(result)
            result.json
        }
}

/** JVM-testable blocking boundary, not a replacement for the Android broker. */
internal object NativeWorkerBridge {
    fun execute(onMainThread: Boolean, action: suspend () -> String): String {
        check(!onMainThread) { "Native candidate resolution must run on a worker thread" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Native resolution interrupted")
        return try {
            val result = runBlocking { action() }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Native resolution interrupted")
            result
        } catch (interrupted: InterruptedException) {
            // runBlocking clears the interrupt while cancelling its coroutine; preserve it for callers.
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }
}
