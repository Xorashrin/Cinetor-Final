package com.cinator.app.providers

import android.content.Context
import com.cinator.app.data.BoundedHttpClient
import com.cinator.app.data.TextHttpClient
import com.cinator.app.engines.nuvio.runtime.IsolatedJsResult

/** Production registry wiring: native failures never select the legacy Kotlin executor. */
object AndroidBundledPlaybackProviders {
    fun fromContext(
        context: Context,
        http: TextHttpClient = BoundedHttpClient(),
        onNativeResult: ((IsolatedJsResult) -> Unit)? = null,
    ): List<PlaybackProvider> =
        context.assets.open("open_films.json").bufferedReader(Charsets.UTF_8).use {
            BundledPlaybackProviders.fromJson(
                raw = it.readText(),
                http = http,
                candidateExecutor = AndroidBlenderCandidateExecutor(context, onNativeResult),
            )
        }
}
