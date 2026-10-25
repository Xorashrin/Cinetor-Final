package com.cinator.app

import com.cinator.app.data.*
import com.cinator.app.domain.FeedSection
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RepositoryCancellationTest {
    @Test fun cancellationDoesNotTriggerOtherEndpointsOrProviders() {
        val source = EmbeddedSource("tvmaze-home", "TVMaze", FeedSection.HOME, "TVMAZE",
            listOf("https://api.tvmaze.com/search/shows?q=first", "https://api.tvmaze.com/search/shows?q=second"), true, "metadata")
        var calls = 0
        val repository = CatalogRepository(EmbeddedCatalog(listOf(source)), object : TextHttpClient {
            override fun get(url: String): String { calls++; throw CancellationException("cancelled") }
        })
        try {
            repository.load(FeedSection.HOME)
            fail("Cancellation must propagate instead of becoming a provider failure")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }
}
