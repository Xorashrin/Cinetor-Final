package com.cinator.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EmbeddedAssetsTest {
    private fun asset(name: String): JSONObject {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return JSONObject(candidates.first { it.isFile }.readText())
    }

    @Test fun directorySnapshotHasUniqueIdsAndTruthfulCounts() {
        val root = asset("embedded_directory_snapshot.json")
        val entries = root.getJSONArray("entries")
        val ids = (0 until entries.length()).map { entries.getJSONObject(it).getString("snapshotId") }
        assertEquals(entries.length(), ids.toSet().size)
        assertEquals(root.getInt("uniqueEntries"), entries.length())
        val counts = root.getJSONObject("counts")
        for (ecosystem in listOf("nuvio", "cloudstream")) {
            assertEquals(counts.getInt(ecosystem), (0 until entries.length()).count {
                entries.getJSONObject(it).getString("ecosystem") == ecosystem
            })
        }
        var subplugins = 0
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            assertEquals("REFERENCE_ONLY", e.getString("status"))
            assertFalse(e.getBoolean("runtimeEnabled"))
            subplugins += e.getJSONArray("subplugins").length()
        }
        assertEquals(root.getInt("nuvioSubpluginDescriptors"), subplugins)
    }

    @Test fun defaultBundleContainsNoManualConfigurationOrAdultNetworkCalls() {
        val root = asset("embedded_catalog.json")
        assertEquals(1, root.getInt("schemaVersion"))
        val sources = root.getJSONArray("sources")
        val sections = mutableSetOf<String>()
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            assertTrue(source.has("enabled"))
            assertFalse(source.toString().contains("example.org"))
            if (source.getBoolean("enabled")) {
                sections.add(source.getString("section"))
                assertNotEquals("ADULT", source.getString("section"))
                assertTrue(source.getJSONArray("endpoints").length() > 0)
            }
        }
        assertEquals(setOf("HOME", "ANIME", "KDRAMA", "LIVETV"), sections)
    }
}
