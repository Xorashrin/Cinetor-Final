package com.cinator.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Tracks download jobs only; cancelling them must not cancel the shared UI scope. */
class ForegroundDownloadJobs {
    private val jobs = mutableSetOf<Job>()
    private var foreground = false
    private var closed = false

    internal val trackedJobCount: Int
        get() = synchronized(this) { jobs.size }

    @Synchronized
    fun setForeground(foreground: Boolean) {
        this.foreground = foreground && !closed
        if (!foreground) {
            jobs.toList().forEach {
                it.cancel(CancellationException("Foreground download stopped"))
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        setForeground(false)
    }

    @Synchronized
    fun launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job? {
        if (!foreground) return null
        // Enter the block before returning so immediate ON_STOP still runs its finally cleanup.
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED, block = block)
        jobs.add(job)
        job.invokeOnCompletion { synchronized(this) { jobs.remove(job) } }
        return job
    }
}
