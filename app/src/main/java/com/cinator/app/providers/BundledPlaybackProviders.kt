package com.cinator.app.providers

import com.cinator.app.data.BoundedHttpClient
import com.cinator.app.data.TextHttpClient

/** Bundled audited registry only; not a user-supplied URL or add-on loader. */
object BundledPlaybackProviders {
    fun fromJson(raw: String, http: TextHttpClient = BoundedHttpClient(),
        candidateExecutor: BlenderCandidateExecutor? = null): List<PlaybackProvider> =
        listOf(BlenderPlaybackProvider(raw, http, candidateExecutor))
}
