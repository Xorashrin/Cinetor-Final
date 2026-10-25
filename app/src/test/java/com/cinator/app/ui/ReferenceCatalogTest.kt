package com.cinator.app.ui

import org.junit.Assert.*
import org.junit.Test

class ReferenceCatalogTest {
    @Test fun directoryIsMetadataOnlyDeduplicatedSearchableAndPaged() {
        val raw = """{"schemaVersion":1,"uniqueEntries":999,"capturedAt":"snapshot","coverage":"bounded","entries":[
          {"id":"repo-a","name":"Alpha","ecosystem":"nuvio","endpoint":"https://example.com/manifest.json","status":"REFERENCE_ONLY","runtimeEnabled":false,"subplugins":[{"name":"Hidden Match","description":"descriptor"}]},
          {"id":"repo-a","name":"Alpha alias","ecosystem":"nuvio","status":"REFERENCE_ONLY","runtimeEnabled":false},
          {"id":"repo-b","name":"Beta","ecosystem":"cloudstream","status":"REFERENCE_ONLY","runtimeEnabled":false}
        ]}"""
        val snapshot = ReferenceCatalog.parse(raw)
        assertEquals(2, snapshot.entries.size)
        assertEquals(mapOf("nuvio" to 1, "cloudstream" to 1), snapshot.ecosystemCounts)
        assertEquals(1, snapshot.descriptorCount)
        assertEquals("repo-a", snapshot.search("hidden").single().id)
        assertEquals("repo-b", snapshot.page("", 1, 1).single().id)
        assertTrue(snapshot.entries.all { it.status == "REFERENCE_ONLY" && !it.runtimeEnabled })
    }

    @Test fun bundledAssetHasEveryUniqueRepositoryAndAllPagesAreReachable() {
        val file = listOf(java.io.File("app/src/main/assets/embedded_directory_snapshot.json"),
            java.io.File("src/main/assets/embedded_directory_snapshot.json")).first { it.isFile }
        val raw = file.readText()
        val source = org.json.JSONObject(raw).getJSONArray("entries")
        val expectedIds = (0 until source.length()).map { source.getJSONObject(it).getString("id") }.toSet()
        val snapshot = ReferenceCatalog.parse(raw)
        assertEquals(79, expectedIds.size)
        assertEquals(expectedIds, snapshot.entries.map { it.id }.toSet())
        val pages = (0 until (snapshot.entries.size + 9) / 10).flatMap { snapshot.page("", it, 10) }
        assertEquals(expectedIds, pages.map { it.id }.toSet())
        assertEquals(snapshot.entries.size, pages.size)
        assertEquals(mapOf("nuvio" to 11, "cloudstream" to 68), snapshot.ecosystemCounts)
        assertEquals(162, snapshot.descriptorCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun referenceSnapshotCannotEnableRuntime() {
        ReferenceCatalog.parse("""{"schemaVersion":1,"entries":[{"id":"x","name":"X","ecosystem":"nuvio","status":"ACTIVE","runtimeEnabled":true}]}""")
    }
}
