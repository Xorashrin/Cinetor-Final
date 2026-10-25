package com.cinator.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ForegroundDownloadJobsTest {
    /** Source wiring guard only; real Activity transitions still require an Android/device test. */
    @Test fun activityWiresStopAndDisposalAndPublishesTerminalOnlyAfterProgressCleanup() {
        val source = listOf(
            File("app/src/main/java/com/cinator/app/MainActivity.kt"),
            File("src/main/java/com/cinator/app/MainActivity.kt")
        ).first { it.isFile }.readText()
        assertTrue(source.contains("Lifecycle.Event.ON_STOP -> downloadJobs.setForeground(false)"))
        assertTrue(source.contains("downloadLifecycle.removeObserver(observer)\n            downloadJobs.close()"))
        assertTrue(source.contains("downloadJobs.launch(scope)"))
        val download = source.substringAfter("fun triggerDownload(").substringBefore("fun wipeAdultCache(")
        assertTrue(download.contains("runInterruptible(Dispatchers.IO)"))
        assertTrue(download.contains("status = DownloadStatus.CANCELLED"))
        assertTrue(download.contains("withContext(NonCancellable) { progressJob.cancelAndJoin() }"))
        assertTrue(download.indexOf("progressJob.cancelAndJoin()") < download.indexOf("terminalEntry?.let"))
        assertTrue(download.contains("finally {\n                    downloadGate.finish(target.absolutePath)"))
        assertTrue("Repository interruption must remain intact", Regex(
            "runInterruptible\\(Dispatchers.IO\\)\\s*\\{\\s*repository.load\\(section, requestedQuery\\)"
        ).containsMatchIn(source))
    }

    @Test fun stopInterruptsBlockingIoAndUntracksOnlyAfterCleanup() = runBlocking {
        withTimeout(3_000) {
            val jobs = ForegroundDownloadJobs()
            jobs.setForeground(true)
            val entered = CompletableDeferred<Unit>()
            val interrupted = CompletableDeferred<Unit>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val download = jobs.launch(this) {
                try {
                    runInterruptible(Dispatchers.IO) {
                        entered.complete(Unit)
                        try {
                            Thread.sleep(60_000)
                        } finally {
                            interrupted.complete(Unit)
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }!!
            try {
                entered.await()
                jobs.setForeground(false)
                interrupted.await()
                cleanupEntered.await()
                assertEquals(1, jobs.trackedJobCount)
            } finally {
                releaseCleanup.complete(Unit)
            }
            download.join()
            assertTrue(download.isCancelled)
            assertEquals(0, jobs.trackedJobCount)
        }
    }

    @Test fun disposalCancelsEveryDownloadAndIsIdempotent() = runBlocking {
        withTimeout(2_000) {
            val jobs = ForegroundDownloadJobs()
            jobs.setForeground(true)
            val downloads = List(3) { jobs.launch(this) { awaitCancellation() }!! }
            jobs.close()
            jobs.close()
            downloads.forEach { it.join(); assertTrue(it.isCancelled) }
            assertEquals(0, jobs.trackedJobCount)
        }
    }

    @Test fun completedJobsAreUntrackedAndDisposalCannotBeReopened() = runBlocking {
        val jobs = ForegroundDownloadJobs()
        jobs.setForeground(true)
        jobs.launch(this) {}!!.join()
        assertEquals(0, jobs.trackedJobCount)
        jobs.close()
        jobs.setForeground(true)
        assertNull(jobs.launch(this) { fail("Disposed downloads must not start") })
    }

    @Test fun backgroundRejectsDownloadsAndForegroundReturnDoesNotRestartCancelledWork() = runBlocking {
        withTimeout(2_000) {
            val jobs = ForegroundDownloadJobs()
            var starts = 0
            assertNull(jobs.launch(this) { starts++ })
            jobs.setForeground(true)
            val cancelled = jobs.launch(this) {
                starts++
                awaitCancellation()
            }!!
            jobs.setForeground(false)
            cancelled.join()
            assertNull(jobs.launch(this) { starts++ })
            jobs.setForeground(true)
            assertTrue(cancelled.isCancelled)
            assertEquals(1, starts)
            jobs.launch(this) { starts++ }!!.join()
            assertEquals(2, starts)
        }
    }

    @Test fun stoppingCancelsDownloadsButLeavesUiLoadingAlive() = runBlocking {
        withTimeout(2_000) {
            val jobs = ForegroundDownloadJobs()
            jobs.setForeground(true)
            val cleaned = CompletableDeferred<Unit>()
            val loading = launch { awaitCancellation() }
            val download = jobs.launch(this) {
                try {
                    awaitCancellation()
                } finally {
                    cleaned.complete(Unit)
                }
            }!!

            jobs.setForeground(false)
            download.join()
            assertTrue(download.isCancelled)
            assertTrue(cleaned.isCompleted)
            assertTrue(loading.isActive)
            loading.cancel()
        }
    }
}
