package com.cinator.app

import com.cinator.app.data.*
import com.cinator.app.domain.FeedSection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackendClassificationRegressionTest {
    @Test fun unsafeArtworkIsDroppedAndMissingCatalogTypeIsSchemaError() {
        val raw = """{"metas":[{"id":"fixture","name":"Fixture","type":"movie","poster":"https://user:secret@example.com/image","background":"https://[::1]/image"}]}"""
        val item = CinemetaCatalogParser.parse(raw).single()
        assertNull(item.posterUrl)
        assertNull(item.backdropUrl)
        val tv = """[{"show":{"id":1,"name":"Fixture","type":"Scripted","language":"English","genres":["Drama"],"image":{"medium":"https://[::1]/image","original":"https://example.com:8443/image"}}}]"""
        assertNull(TVMazeCatalogParser.parse(tv, FeedSection.HOME).single().posterUrl)
        try { CinemetaCatalogParser.parse("""{"metas":[{"id":"fixture","name":"Missing type"}]}"""); fail("Missing type is invalid schema") } catch (_: Exception) { }
    }

    @Test fun tvMazeRequiresActualClassificationEvidence() {
        val rows = JSONArray()
        fun add(id: Int, type: String, language: String, country: String, vararg genres: String) {
            rows.put(JSONObject().put("show", JSONObject().put("id", id).put("name", "Fixture $id")
                .put("type", type).put("language", language).put("genres", JSONArray(genres.toList()))
                .put("network", JSONObject().put("name", "SBS").put("country", JSONObject().put("code", country)))))
        }
        add(1, "Scripted", "Japanese", "JP", "Drama")
        add(2, "Animation", "Japanese", "JP", "Adventure")
        add(3, "Animation", "English", "US", "Anime")
        add(4, "Animation", "Korean", "KR", "Drama")
        add(5, "Scripted", "Korean", "KR", "Drama")
        add(6, "Scripted", "English", "AU", "Drama")
        add(7, "Reality", "Korean", "KR", "Drama")
        add(8, "Scripted", "Korean", "KR", "Comedy")
        add(9, "Animation", "English", "JP", "Adventure")
        add(10, "Scripted", "English", "KR", "Drama")
        add(11, "", "", "")
        val raw = rows.toString()
        assertEquals(listOf("Fixture 2", "Fixture 3", "Fixture 9"), TVMazeCatalogParser.parse(raw, FeedSection.ANIME).map { it.title })
        assertEquals(listOf("Fixture 5", "Fixture 10"), TVMazeCatalogParser.parse(raw, FeedSection.KDRAMA).map { it.title })
        val home = TVMazeCatalogParser.parse(raw, FeedSection.HOME)
        assertEquals(listOf("Fixture 1", "Fixture 4", "Fixture 6", "Fixture 7", "Fixture 8"), home.map { it.title })
        assertTrue(home.all { it.streams.isEmpty() && it.rating.isNullOrBlank() })
        assertTrue(TVMazeCatalogParser.parse(raw, FeedSection.LIVETV).isEmpty())
        assertTrue(TVMazeCatalogParser.parse(raw, FeedSection.ADULT).isEmpty())
    }

    @Test fun cinemetaHomeExcludesAnimeKdramaAndUnclassifiedSeries() {
        val metas = JSONArray()
        fun add(id: Int, type: String, country: String?, vararg genres: String) {
            metas.put(JSONObject().put("id", "fixture$id").put("type", type).put("name", "Fixture $id")
                .put("country", country).put("genres", JSONArray(genres.toList())))
        }
        add(1, "movie", "Japan", "Animation")
        add(2, "series", "South Korea", "Drama")
        add(3, "series", null, "Drama")
        add(4, "series", "United States", "Drama")
        add(5, "movie", null, "Drama")
        add(6, "series", "Japan", "Drama")
        add(7, "series", "US", "Anime")
        add(8, "mystery", "US", "Drama")
        add(9, "series", null, "Animation")
        val raw = JSONObject().put("metas", metas).toString()
        assertEquals(listOf("Fixture 4", "Fixture 5", "Fixture 6"), CinemetaCatalogParser.parse(raw).map { it.title })
        assertTrue(CinemetaCatalogParser.parse(raw, FeedSection.ANIME).isEmpty())
        assertTrue(CinemetaCatalogParser.parse(raw, FeedSection.KDRAMA).isEmpty())
        assertTrue(CinemetaCatalogParser.parse(raw).all { it.streams.isEmpty() })
    }

    @Test fun tvMazeSchemaErrorsAreNotEmptyCatalogSuccess() {
        for (raw in listOf("", "{}", "[{}]", "[1]", "[{\"show\":{}}]")) {
            try { TVMazeCatalogParser.parse(raw, FeedSection.HOME); fail("Expected invalid schema rejection") } catch (_: Exception) { }
        }
        assertTrue(TVMazeCatalogParser.parse("[]", FeedSection.HOME).isEmpty())
    }
}
