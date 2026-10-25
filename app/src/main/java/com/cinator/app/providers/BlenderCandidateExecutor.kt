package com.cinator.app.providers

/** Returns untrusted candidate JSON, never trusted playback sources. */
fun interface BlenderCandidateExecutor {
    fun execute(document: String, uuid: String, height: Int): String
}
