package com.cinator.app.ui

/** Thread-safe admission and progress throttling. Terminal delivery closes the gate. */
class DownloadUiGate {
    private val active = mutableMapOf<String, Long?>()

    @Synchronized fun begin(key: String): Boolean {
        if (active.containsKey(key)) return false
        active[key] = null
        return true
    }

    @Synchronized fun acceptProgress(key: String, nowMs: Long): Boolean {
        if (!active.containsKey(key)) return false
        val last = active[key]
        if (last != null && nowMs - last < 250L) return false
        active[key] = nowMs
        return true
    }

    @Synchronized fun finish(key: String) { active.remove(key) }
}
