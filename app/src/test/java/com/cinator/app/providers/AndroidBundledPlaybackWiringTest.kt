package com.cinator.app.providers

import java.io.File
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** Wiring/worker-adapter tests only: this suite does not exercise Binder or native QuickJS. */
class AndroidBundledPlaybackWiringTest {
    private fun source(name: String): String {
        val root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val file = listOf(File(root, "app/src/main/java/com/cinator/app/$name"),
            File(root, "src/main/java/com/cinator/app/$name")).firstOrNull { it.isFile }
        assertNotNull("Missing production wiring: $name", file)
        return file!!.readText()
    }

    @Test fun activitySelectsAndroidFactoryWithoutMovingResolutionOntoMain() {
        val activity = source("MainActivity.kt")
        assertTrue("Activity must select native factory", activity.contains("AndroidBundledPlaybackProviders.fromContext(context)"))
        assertFalse(activity.contains("BundledPlaybackProviders.fromJson("))
        assertTrue(Regex("runInterruptible\\(Dispatchers.IO\\)\\s*\\{\\s*resolver.resolve\\(").containsMatchIn(activity))
        val factory = source("providers/AndroidBundledPlaybackProviders.kt")
        assertTrue(factory.contains("assets.open(\"open_films.json\")"))
        assertTrue(factory.contains("candidateExecutor = AndroidBlenderCandidateExecutor(context, onNativeResult)"))
    }

    private fun bridge(onMainThread: Boolean = false, action: suspend () -> String): String {
        val type = runCatching { Class.forName("com.cinator.app.providers.NativeWorkerBridge") }.getOrNull()
        assertNotNull("Missing worker-only interrupt-preserving bridge", type)
        try {
            return type!!.getMethod("execute", Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java)
                .invoke(type.getField("INSTANCE").get(null), onMainThread, action) as String
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }
    }

    @Test fun interruptedWorkerCancelsSuspensionAndPreservesInterruptFlag() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val preserved = java.util.concurrent.atomic.AtomicBoolean()
        val worker = Thread {
            try {
                bridge {
                    entered.countDown()
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { cancelled.countDown() }
                }
            } catch (error: Throwable) {
                failure.set(error)
                preserved.set(Thread.currentThread().isInterrupted)
            }
        }.apply { isDaemon = true }
        worker.start()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000)
            assertFalse("Interrupted worker must exit", worker.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertTrue("Interrupt flag must survive runBlocking", preserved.get())
            assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            worker.interrupt()
            worker.join(2_000)
        }
    }

    @Test fun alreadyInterruptedWorkerNeverStartsNativeWork() {
        var called = false
        Thread.currentThread().interrupt()
        try {
            try {
                bridge { called = true; "[]" }
                fail("Pre-interrupted work was accepted")
            } catch (expected: InterruptedException) {
                assertTrue("Existing interrupt must survive", Thread.currentThread().isInterrupted)
                assertFalse(called)
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun interruptionRaisedAtCompletionCannotReturnCandidates() {
        try {
            bridge { Thread.currentThread().interrupt(); "[]" }
            fail("Interrupted result was published")
        } catch (expected: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun workerReturnsExactCandidateJsonAndPropagatesFailuresWithoutFallback() {
        val json = "[{\"untrusted\":true}]"
        assertEquals(json, bridge { json })
        val nativeFailure = IllegalStateException("NATIVE_UNAVAILABLE")
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        for (failure in listOf(nativeFailure, cancellation)) {
            try {
                bridge { throw failure }
                fail("Native failure or cancellation returned a fallback")
            } catch (actual: Throwable) {
                assertSame(failure, actual)
            }
        }
    }

    @Test fun androidExecutorUsesRealBrokerResultAndLooperGuard() {
        val executor = source("providers/AndroidBlenderCandidateExecutor.kt")
        assertTrue(executor.contains("private val broker = IsolatedJsBroker(context)"))
        assertTrue(executor.contains("NativeWorkerBridge.execute(Looper.myLooper() == Looper.getMainLooper())"))
        assertTrue(executor.contains("val result = broker.resolve(document, uuid, height)"))
        assertTrue(executor.contains("onNativeResult?.invoke(result)"))
        assertTrue(executor.contains("result.json"))
        assertFalse("Must not manufacture native evidence", executor.contains("IsolatedJsResult("))
    }

    @Test fun mainThreadIsRejectedBeforeExecutingWork() {
        var called = false
        try {
            bridge(onMainThread = true) { called = true; "[]" }
            fail("Main-thread native blocking was accepted")
        } catch (expected: IllegalStateException) {
            assertFalse(called)
        }
    }
}
