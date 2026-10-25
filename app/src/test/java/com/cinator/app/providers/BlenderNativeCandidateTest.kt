package com.cinator.app.providers

import com.cinator.app.data.TextHttpClient
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Host seam validation only: these fakes are NOT native execution evidence. */
class BlenderNativeCandidateTest {
    private fun resource(name: String) = listOf(File("app/src/test/resources/blender/$name"),
        File("src/test/resources/blender/$name")).first { it.isFile }.readText()
    private fun registry() = resource("audited-registry.json")
    private val uuid = "bf1f3fb5-b119-4f9f-9930-8e20e892b898"
    private fun api() = JSONObject(resource("$uuid.json"))
    private fun provider(document: String = api().toString(), raw: String = registry(),
        execute: (String, String, Int) -> String): PlaybackProvider {
        val client = object : TextHttpClient { override fun get(url: String) = document }
        return BundledPlaybackProviders.fromJson(raw, client, BlenderCandidateExecutor(execute)).single()
    }
    private fun resolve(p: PlaybackProvider) = p.resolve(ResolveRequest(p.catalog().first()))

    private fun candidate(height: Int, data: JSONObject = api()): String {
        val files = data.getJSONArray("files")
        val file = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getInt("height") == height }
        return "[" + JSONObject().put("uuid", uuid).put("height", height)
            .put("url", file.getString("fileUrl")).put("size", file.get("size")) + "]"
    }

    @Test fun acceptedCandidatesUseFreshApiIdentityAndReviewedDownloadRights() {
        val calls = mutableListOf<Int>()
        val p = provider { _, _, height -> calls += height; candidate(height) }
        val sources = resolve(p)
        assertEquals(listOf(720, 480, 360), calls)
        assertEquals(listOf("720p", "480p", "360p"), sources.map { it.quality })
        assertTrue(sources.all { it.contentId == "blender:$uuid" && it.isDownloadable })
    }

    @Test fun nativeOutputMustBeOneStrictExactTupleOrEmptyArray() {
        val valid = candidate(720)
        val objectText = valid.removePrefix("[").removeSuffix("]")
        val bad = listOf(valid + " trailing", "/*comment*/" + valid,
            valid.replace("\"height\":720", "\"height\":720,\"height\":720"),
            valid.replace("\"height\":720", "height:720"), valid.replace('"', '\''),
            "[$objectText,]", "[$objectText,$objectText]", "[null]", "{}", "null", "[[]]",
            valid.replace("\"height\":720", "\"height\":\"720\""),
            valid.replace("\"height\":720", "\"height\":720.5"),
            valid.replace(uuid, "0eb052d0-fd51-43e6-aa33-ecdbf77a5d40"),
            valid.replace("-720.mp4", "-480.mp4"),
            valid.replace(".mp4", ".mp4?token=x"),
            valid.replace("}", ",\"downloadEnabled\":true}"),
            valid.replace(Regex("\"size\":[0-9]+"), "\"size\":1"),
            " ".repeat(16385) + "[]")
        bad.forEach { output ->
            assertThrows("Rejected output: ${output.take(250)}", IllegalArgumentException::class.java) {
                val data = api().apply {
                    put("files", org.json.JSONArray().put(getJSONArray("files").getJSONObject(0)))
                    remove("streamingPlaylists")
                }
                resolve(provider(data.toString()) { _, _, _ -> output })
            }
        }
        assertEquals(3, resolve(provider { _, _, height -> " \n" + candidate(height) + "\t" }).size)
    }

    @Test fun parentRejectsOversizedOrMalformedDocumentGroupsBeforeNativeExecution() {
        val invalid = listOf(
            api().put("description", "é".repeat(33000)).toString(),
            api().put("streamingPlaylists", org.json.JSONArray(List(9) { JSONObject().put("files", org.json.JSONArray()) })).toString(),
            api().put("files", org.json.JSONArray(List(257) { JSONObject() })).toString(),
            api().put("files", JSONObject()).toString(),
            api().put("streamingPlaylists", org.json.JSONArray().put(JSONObject())).toString(),
            api().put("streamingPlaylists", JSONObject()).toString())
        invalid.forEach { document ->
            assertThrows(IllegalArgumentException::class.java) {
                resolve(provider(document) { _, _, _ -> fail("Invalid document reached native seam"); "[]" })
            }
        }
    }

    @Test fun nativeAndParentAgreeOnStrictSafeNumericApiFields() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("height", "720") }, { it.put("height", 720.5) },
            { it.getJSONObject("resolution").put("id", "720") },
            { it.put("size", "110916740") }, { it.put("size", 110916740.5) },
            { it.put("size", 9007199254740992L) }, { it.put("size", 0) })
        edits.forEach { edit ->
            val data = api()
            val file = data.getJSONArray("files").getJSONObject(0).also(edit)
            data.put("files", org.json.JSONArray().put(file)).remove("streamingPlaylists")
            assertTrue(resolve(provider(data.toString()) { _, _, _ -> fail("Invalid numeric row reached native"); "[]" }).isEmpty())
        }
        val duration = api().getLong("duration")
        listOf<Any>(duration.toString(), duration + 0.5).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                resolve(provider(api().put("duration", value).toString()) { _, _, _ -> fail("Invalid duration reached native"); "[]" })
            }
        }
    }

    @Test fun rawApiFractionsCannotRoundIntoSafeIntegersOnAndroid() {
        val edits = listOf(
            "\"height\":720" to "\"height\":720.0000000000000001",
            "\"id\":720" to "\"id\":720.0000000000000001",
            "\"size\":110916740" to "\"size\":110916740.000000001")
        for (playlist in listOf(false, true)) for ((before, after) in edits) {
            val data = api()
            val files = org.json.JSONArray().put(data.getJSONArray("files").getJSONObject(0))
            data.put("files", if (playlist) org.json.JSONArray() else files)
            data.remove("streamingPlaylists")
            if (playlist) data.put("streamingPlaylists", org.json.JSONArray().put(JSONObject().put("files", files)))
            val original = data.toString()
            val raw = original.replace(before, after)
            assertNotEquals("Raw fixture must contain target", original, raw)
            assertTrue(resolve(provider(raw) { _, _, _ -> fail("Fractional API row reached native: $after"); "[]" }).isEmpty())
        }
    }

    @Test fun rawApiFractionalDurationIsRejectedBeforeExecutor() {
        val data = api()
        val seconds = data.getLong("duration")
        val original = data.toString()
        val raw = original.replace("\"duration\":$seconds", "\"duration\":$seconds.0000000000000001")
        assertNotEquals(original, raw)
        assertThrows(IllegalArgumentException::class.java) {
            resolve(provider(raw) { _, _, _ -> fail("Fractional duration reached native"); "[]" })
        }
    }

    @Test fun rawDocumentsRequireStrictBoundedJsonBeforeExecutor() {
        val original = api().toString()
        val invalid = listOf(
            original + " trailing", "/*comment*/" + original,
            original.replace("\"duration\":596", "\"duration\":596,\"duration\":596"),
            original.replace("\"uuid\":", "\"u\\u0075id\":\"other\",\"uuid\":"),
            original.dropLast(1) + ",}",
            "{\"extra\":" + "[".repeat(17) + "0" + "]".repeat(17) + "," + original.drop(1),
            "{\"extra\":1e129," + original.drop(1),
            "{\"extra\":1e-129," + original.drop(1),
            "{\"extra\":" + "1".repeat(129) + "," + original.drop(1),
            "{\"extra\":[" + List(257) { "0" }.joinToString(",") + "]," + original.drop(1),
            "{\"extra\":\"\\uD800\"," + original.drop(1),
            "{\"extra\":\"\\x41\"," + original.drop(1))
        invalid.forEach { raw ->
            assertNotEquals("Fixture edit must apply", original, raw)
            assertThrows(IllegalArgumentException::class.java) {
                resolve(provider(raw) { _, _, _ -> fail("Malformed document reached executor"); "[]" })
            }
        }
    }

    @Test fun ordinaryDecimalMetadataAndExactIntegerDecimalsRemainValid() {
        val document = api().toString().replace("\"height\":720", "\"height\":720.000")
            .replace("\"id\":720", "\"id\":7.2e2")
            .replace("\"size\":110916740", "\"size\":110916740.000000000")
            .let { "{\"decimalMetadata\":1.234567890123456789,\"escaped\":\"\\uD83D\\uDE00\\n\\t\\/\\\\\\\"\"," + it.drop(1) }
        assertEquals(3, resolve(provider(document) { _, _, h -> candidate(h) }).size)
    }

    @Test(timeout = 5000) fun malformedCandidateDoesNotScanEachPossibleSuffix() {
        val output = "[{" + " ".repeat(16000) + "x}]"
        assertThrows(IllegalArgumentException::class.java) {
            resolve(provider { _, _, _ -> output })
        }
    }

    @Test fun cancellationAfterNativeSuccessCannotReturnPartialSources() {
        val p = provider { _, _, height ->
            if (height == 360) Thread.currentThread().interrupt()
            candidate(height)
        }
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) { resolve(p) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun nativeExceptionsNeverReturnPriorSourcesOrLegacyFallback() {
        val failures = listOf(java.io.IOException("native failure"),
            java.util.concurrent.CancellationException("cancelled"), InterruptedException("interrupted"))
        failures.forEach { failure ->
            val calls = mutableListOf<Int>()
            val p = provider { _, _, height ->
                calls += height
                if (height == 480) throw failure
                candidate(height)
            }
            try {
                if (failure is InterruptedException) {
                    assertThrows(java.util.concurrent.CancellationException::class.java) { resolve(p) }
                    assertTrue(Thread.currentThread().isInterrupted)
                } else {
                    assertSame(failure, assertThrows(failure.javaClass) { resolve(p) })
                }
                assertEquals(listOf(720, 480), calls)
            } finally { Thread.interrupted() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolve(provider { _, _, height -> if (height == 480) "[{}]" else candidate(height) })
        }
    }

    @Test fun freshProvenanceAndRightsRemainParentOwned() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("uuid", "0eb052d0-fd51-43e6-aa33-ecdbf77a5d40") },
            { it.getJSONObject("account").put("name", "other") },
            { it.getJSONObject("channel").put("host", "example.invalid") },
            { it.getJSONObject("privacy").put("id", 3) }, { it.put("duration", 1) })
        edits.forEach { edit ->
            assertThrows(IllegalArgumentException::class.java) {
                resolve(provider(api().also(edit).toString()) { _, _, _ -> fail("Untrusted API reached native"); "[]" })
            }
        }
        assertTrue(resolve(provider(api().put("downloadEnabled", false).toString()) { _, _, h -> candidate(h) })
            .all { !it.isDownloadable })
        listOf("licenseUrl" to "", "preserveCompleteEndCredits" to false).forEach { (key, value) ->
            val raw = JSONObject(registry()).apply { getJSONArray("films").getJSONObject(0).put(key, value) }.toString()
            assertTrue(resolve(provider(raw = raw) { _, _, h -> candidate(h) }).all { !it.isDownloadable })
        }
        val changed = api().apply { getJSONArray("files").getJSONObject(0).put("size", 123456) }
        assertThrows(IllegalArgumentException::class.java) {
            resolve(provider(changed.toString()) { _, _, h -> candidate(h) })
        }
        assertEquals(listOf("480p", "360p"), resolve(provider { _, _, h -> if (h == 720) "[]" else candidate(h) }).map { it.quality })
    }

    @Test fun strictWireTokensRejectRoundingAndNonJsonWhitespaceWithoutStackOverflow() {
        val data = api().apply {
            put("files", org.json.JSONArray().put(getJSONArray("files").getJSONObject(0)))
            remove("streamingPlaylists")
        }
        val valid = candidate(720)
        val invalid = listOf("[\u000b]", "[\u000c]",
            valid.replace("110916740", "110916740.00000000000001"),
            valid.replace("110916740", "\"110916740\""),
            valid.replace("110916740", "9007199254740992"),
            valid.replace("110916740", "-1"),
            valid.replace(uuid, "a".repeat(7000)))
        invalid.forEach { output ->
            assertThrows(IllegalArgumentException::class.java) {
                resolve(provider(data.toString()) { _, _, _ -> output })
            }
        }
    }

    @Test fun exactUtf8BudgetsAndAggregateRowLimitAreEnforced() {
        val document = api().toString()
        val padded = document + " ".repeat(65536 - document.toByteArray(Charsets.UTF_8).size)
        assertEquals(3, resolve(provider(padded) { _, _, h -> candidate(h) }).size)
        assertThrows(IllegalArgumentException::class.java) {
            resolve(provider(padded + " ") { _, _, _ -> fail("Oversized input reached executor"); "[]" })
        }
        assertTrue(resolve(provider { _, _, _ -> "[]" + " ".repeat(16382) }).isEmpty())
        val grouped = api().apply {
            put("files", org.json.JSONArray(List(250) { JSONObject() }))
            put("streamingPlaylists", org.json.JSONArray().put(JSONObject().put("files",
                org.json.JSONArray(List(7) { JSONObject() }))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolve(provider(grouped.toString()) { _, _, _ -> fail("Too many total rows"); "[]" })
        }
    }

    @Test fun nativePlaylistSelectionUsesFirstValidOfficialFile() {
        val data = api()
        val files = data.getJSONArray("files")
        val invalid = JSONObject(files.getJSONObject(0).toString()).put("hasAudio", false).put("size", 1)
        val top = org.json.JSONArray().put(invalid)
        data.put("files", top)
        data.put("streamingPlaylists", org.json.JSONArray().put(JSONObject().put("files", files)))
        assertEquals(listOf("720p", "480p", "360p"),
            resolve(provider(data.toString()) { _, _, h -> candidate(h) }).map { it.quality })
    }

    @Test fun emptyNativeChoicesCannotReturnLegacySources() {
        val document = api().toString()
        val heights = mutableListOf<Int>()
        val p = provider(document) { received, id, height ->
            assertEquals(document, received)
            assertEquals(uuid, id)
            heights += height
            "[]"
        }
        assertTrue(resolve(p).isEmpty())
        assertEquals(listOf(720, 480, 360), heights)
    }
}
