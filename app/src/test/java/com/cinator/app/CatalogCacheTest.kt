package com.cinator.app

import com.cinator.app.data.CatalogCache
import com.cinator.app.domain.*
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogCacheTest {
    @get:Rule val temp = TemporaryFolder(System.getenv("TMPDIR")?.let(::File))

    private fun card(id: String = "tt0133093", section: FeedSection = FeedSection.HOME,
        kind: MediaKind = MediaKind.MOVIE): MediaCard = MediaCard(
        id = id, title = "Cache metadata test", subtitle = "Test only", synopsis = "Metadata round-trip",
        section = section, providerId = "test-provider", providerName = "Test provider", year = "1999",
        rating = "8.7", genres = listOf("Action", "Science-Fiction"),
        externalUrl = "https://www.imdb.com/title/$id/",
        posterUrl = "https://images.example.com/$id/poster.jpg",
        backdropUrl = "https://images.example.com/$id/background.jpg", kind = kind, contentId = "imdb:$id",
        streams = listOf(StreamSource(
            id = "public-stream", label = "Public MP4", url = "https://cdn.example.com/$id/video.mp4",
            protocol = StreamProtocol.MP4, quality = "", isDownloadable = true,
            providerId = "test-provider", contentId = "imdb:$id", section = section
        ))
    )

    @Test fun preparedSnapshotIsInvisibleUntilPublicationAndDiscardKeepsPreviousSnapshot() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        cache.write(FeedSection.HOME, listOf(card("old")))
        cache.prepare(FeedSection.HOME, listOf(card("new"))).use { prepared ->
            assertEquals(listOf(card("old")), cache.read(FeedSection.HOME))
            assertTrue(root.listFiles()!!.any { it.name.endsWith(".tmp") })
            assertTrue(prepared.publish())
            assertFalse("A prepared snapshot is one-shot", prepared.publish())
        }
        assertEquals(listOf(card("new")), cache.read(FeedSection.HOME))
        cache.prepare(FeedSection.HOME, listOf(card("stale"))).close()
        assertEquals(listOf(card("new")), cache.read(FeedSection.HOME))
        assertEquals(listOf("${FeedSection.HOME.cacheKey}.json"), root.listFiles()!!.map { it.name })
    }

    @Test fun clearingInvalidatesPreparedSnapshotsAcrossInstancesWithoutAffectingOtherSections() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        val adult = card("adult", FeedSection.ADULT, MediaKind.ADULT)
        cache.prepare(FeedSection.HOME, listOf(card("home"))).use { home ->
            cache.prepare(FeedSection.ADULT, listOf(adult)).use { pending ->
                CatalogCache(root).clear(FeedSection.ADULT)
                assertFalse("A pending write must not resurrect opt-out data", pending.publish())
                assertTrue(home.publish())
            }
        }
        assertTrue(cache.read(FeedSection.ADULT).isEmpty())
        assertEquals(listOf(card("home")), cache.read(FeedSection.HOME))
        cache.write(FeedSection.ADULT, listOf(adult))
        assertEquals(listOf(adult), cache.read(FeedSection.ADULT))
    }

    @Test fun preparingAnotherSnapshotDoesNotDeleteAnUnpublishedSnapshot() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        cache.prepare(FeedSection.HOME, listOf(card("first"))).use { first ->
            CatalogCache(root).prepare(FeedSection.HOME, listOf(card("second"))).use { second ->
                assertTrue(first.publish())
                assertEquals(listOf(card("first")), cache.read(FeedSection.HOME))
                assertTrue(second.publish())
            }
        }
        assertEquals(listOf(card("second")), cache.read(FeedSection.HOME))
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun supersededPreparationCannotReplaceANewerSnapshotOrLeaveStagingFiles() = kotlinx.coroutines.runBlocking {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        val coordinator = com.cinator.app.ui.SearchDebounceCoordinator(this)
        val old = coordinator.cancel(FeedSection.HOME)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slowItems = object : AbstractList<MediaCard>() {
            override val size = 1
            override fun get(index: Int): MediaCard {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return card("old")
            }
        }
        val pending = async(kotlinx.coroutines.Dispatchers.IO) {
            cache.prepare(FeedSection.HOME, slowItems).use { prepared ->
                coordinator.commitIfLatestSuspending(FeedSection.HOME, old) { prepared.publish() }
            }
        }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val latest = coordinator.cancel(FeedSection.HOME)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cache.prepare(FeedSection.HOME, listOf(card("new"))).use { prepared ->
                    assertTrue(coordinator.commitIfLatestSuspending(FeedSection.HOME, latest) { prepared.publish() })
                }
            }
            release.countDown()
            assertFalse(pending.await())
            assertEquals(listOf(card("new")), cache.read(FeedSection.HOME))
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally {
            release.countDown()
        }
    }

    @Test fun metadataArtworkAndPublicStreamsSurviveANewCacheInstance() {
        val root = temp.newFolder("cache")
        val first = CatalogCache(root)
        assertTrue(first.read(FeedSection.HOME).isEmpty())
        val item = card()
        first.write(FeedSection.HOME, listOf(item))
        assertEquals(listOf(item), CatalogCache(root).read(FeedSection.HOME))
        assertTrue(CatalogCache(root).read(FeedSection.ANIME).isEmpty())
    }

    @Test fun cacheNeverPersistsSignedOrHeaderAuthenticatedStreams() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        val item = card()
        val public = item.streams.single()
        val secret = "NEVER_PERSIST_THIS_SECRET"
        val unsafe = listOf(
            public.copy(id = "query", url = public.url + "?token=$secret"),
            public.copy(id = "opaqueQuery", url = public.url + "?x=$secret"),
            public.copy(id = "fragment", url = public.url + "#$secret"),
            public.copy(id = "headers", headers = mapOf("Authorization" to "Bearer $secret")),
            public.copy(id = "path", url = "https://cdn.example.com/token/$secret/stream.m3u8"),
            public.copy(id = "private", url = "https://192.168.1.1/video.mp4"),
            public.copy(id = "wrongContent", contentId = "imdb:other"),
            public.copy(id = "wrongSection", section = FeedSection.ADULT),
            public.copy(id = "unknownContent", contentId = null),
            public.copy(id = "liveVodMix", isLive = true)
        )
        cache.write(FeedSection.HOME, listOf(item.copy(streams = listOf(public) + unsafe,
            posterUrl = "https://127.0.0.1/poster.jpg", externalUrl = "https://example.com/?token=$secret")))
        val restored = cache.read(FeedSection.HOME).single()
        assertEquals(listOf(public), restored.streams)
        assertNull(restored.posterUrl)
        assertNull(restored.externalUrl)
        val file = File(root, "${FeedSection.HOME.cacheKey}.json")
        assertFalse(file.readText().contains(secret))
        // Treat disk contents as untrusted too, not only write input.
        val tampered = JSONObject(file.readText())
        tampered.getJSONArray("items").getJSONObject(0).apply {
            put("posterUrl", "https://169.254.169.254/poster.jpg")
            getJSONArray("streams").getJSONObject(0).put("url", public.url + "?token=$secret")
        }
        file.writeText(tampered.toString())
        assertTrue(cache.read(FeedSection.HOME).single().streams.isEmpty())
        assertNull(cache.read(FeedSection.HOME).single().posterUrl)
        tampered.getJSONArray("items").getJSONObject(0).getJSONArray("streams").getJSONObject(0)
            .put("url", public.url).put("headers", JSONObject().put("Cookie", secret))
        file.writeText(tampered.toString())
        assertTrue(cache.read(FeedSection.HOME).single().streams.isEmpty())
    }

    @Test fun snapshotBudgetsBoundItemCountAndRejectOversizedWritesBeforeReplacing() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        val items = (0 until 600).map { card("item$it") }
        cache.write(FeedSection.HOME, items)
        assertEquals(items.take(500), cache.read(FeedSection.HOME))
        val file = File(root, "${FeedSection.HOME.cacheKey}.json")
        val before = file.readBytes()
        val oversized = card().copy(synopsis = "x".repeat(2 * 1024 * 1024))
        assertTrue("Oversized metadata must fail before replacing the old snapshot",
            runCatching { cache.write(FeedSection.HOME, listOf(oversized)) }.isFailure)
        assertArrayEquals(before, file.readBytes())
        assertTrue(file.length() <= 2 * 1024 * 1024)
        file.writeText(" ".repeat(2 * 1024 * 1024 + 1))
        assertTrue(cache.read(FeedSection.HOME).isEmpty())
        cache.write(FeedSection.HOME, listOf(card()))
        assertEquals(listOf(card()), cache.read(FeedSection.HOME))
    }

    @Test fun replacementIsAtomicForReadersHoldingThePreviousSnapshot() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        val file = File(root, "${FeedSection.HOME.cacheKey}.json")
        cache.write(FeedSection.HOME, listOf(card("old")))
        val previous = file.readBytes()
        file.inputStream().use { oldReader ->
            CatalogCache(root).write(FeedSection.HOME, listOf(card("new")))
            assertArrayEquals("An open snapshot must not be truncated during replacement", previous, oldReader.readBytes())
        }
        assertEquals(listOf(card("new")), CatalogCache(root).read(FeedSection.HOME))
        assertEquals(listOf(file.name), root.listFiles()!!.map { it.name })
    }

    @Test fun clearOnlyRemovesTheSelectedAdultSnapshot() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        val home = card()
        val adult = card("opted-in", FeedSection.ADULT, MediaKind.ADULT)
        cache.write(FeedSection.HOME, listOf(home))
        cache.write(FeedSection.ADULT, listOf(adult))
        assertEquals(listOf(adult), cache.read(FeedSection.ADULT))
        cache.clear(FeedSection.ADULT)
        assertTrue(CatalogCache(root).read(FeedSection.ADULT).isEmpty())
        assertEquals(listOf(home), CatalogCache(root).read(FeedSection.HOME))
    }

    @Test fun clearingAdultAlsoRemovesOnlyItsInterruptedWriteFiles() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        cache.write(FeedSection.HOME, listOf(card()))
        cache.write(FeedSection.ADULT, listOf(card("adult", FeedSection.ADULT, MediaKind.ADULT)))
        val interruptedAdult = File(root, ".${FeedSection.ADULT.cacheKey}.json.interrupted.tmp").apply { writeText("private") }
        val interruptedHome = File(root, ".${FeedSection.HOME.cacheKey}.json.interrupted.tmp").apply { writeText("public") }
        cache.clear(FeedSection.ADULT)
        assertFalse("Opt-out must not leave an interrupted adult snapshot", interruptedAdult.exists())
        assertTrue(interruptedHome.exists())
        assertEquals(listOf(card()), cache.read(FeedSection.HOME))
    }

    @Test fun adversarialNestingAndInvalidRowsCannotCrashOrHideValidMetadata() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        cache.write(FeedSection.HOME, listOf(card()))
        val file = File(root, "${FeedSection.HOME.cacheKey}.json")
        val valid = JSONObject(file.readText())
        valid.getJSONArray("items").put(JSONObject().put("kind", "NOT_A_KIND"))
        file.writeText(valid.toString())
        assertEquals(listOf(card()), cache.read(FeedSection.HOME))
        file.writeText("{\"schemaVersion\":1,\"section\":\"HOME\",\"items\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}")
        assertTrue(cache.read(FeedSection.HOME).isEmpty())
    }

    @Test fun corruptOrUnsupportedCacheFallsBackToEmptyWithoutInventedDefaults() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        cache.write(FeedSection.HOME, listOf(card()))
        val file = File(root, "${FeedSection.HOME.cacheKey}.json")
        val valid = file.readText()
        listOf("{not json", "[]", "{\"schemaVersion\":1,\"section\":\"HOME\"}",
            JSONObject(valid).put("schemaVersion", 999).toString(),
            JSONObject(valid).apply { remove("schemaVersion") }.toString(),
            JSONObject(valid).put("items", "not an array").toString()).forEach { corrupt ->
            file.writeText(corrupt)
            assertEquals("Corrupt cache must be disposable: $corrupt", emptyList<MediaCard>(), cache.read(FeedSection.HOME))
        }
        cache.write(FeedSection.HOME, listOf(card()))
        assertEquals(listOf(card()), cache.read(FeedSection.HOME))
        cache.clear(FeedSection.HOME)
        cache.clear(FeedSection.HOME)
        assertTrue(cache.read(FeedSection.HOME).isEmpty())
    }

    @Test fun rejectsCrossFeedOrUnknownKindsOnWriteAndOnRead() {
        val root = temp.newFolder("cache")
        val cache = CatalogCache(root)
        val home = card()
        val anime = card("anime", FeedSection.ANIME, MediaKind.ANIME)
        cache.write(FeedSection.HOME, listOf(home, anime,
            home.copy(kind = MediaKind.ANIME), home.copy(kind = MediaKind.ADULT), home.copy(kind = MediaKind.UNKNOWN)))
        assertEquals(listOf(home), cache.read(FeedSection.HOME))
        cache.write(FeedSection.ANIME, listOf(anime))
        val homeFile = File(root, "${FeedSection.HOME.cacheKey}.json")
        val animeFile = File(root, "${FeedSection.ANIME.cacheKey}.json")
        homeFile.writeText(animeFile.readText())
        assertTrue(cache.read(FeedSection.HOME).isEmpty())
        val wrongSectionItem = JSONObject(animeFile.readText()).put("section", FeedSection.HOME.name)
        homeFile.writeText(wrongSectionItem.toString())
        assertTrue(cache.read(FeedSection.HOME).isEmpty())
        wrongSectionItem.getJSONArray("items").getJSONObject(0).put("section", FeedSection.HOME.name)
        homeFile.writeText(wrongSectionItem.toString())
        assertTrue(cache.read(FeedSection.HOME).isEmpty())
        assertEquals(listOf(anime), cache.read(FeedSection.ANIME))
    }
}
