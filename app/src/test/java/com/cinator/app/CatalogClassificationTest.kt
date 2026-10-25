package com.cinator.app

import com.cinator.app.data.LiveNetworkAdapters
import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogClassificationTest {
    @Test fun tvMazeClassifiesEvidenceInsteadOfRelabelingEverySearchHit() {
        val shows = JSONArray()
        fun add(id: Int, type: String?, language: String?, vararg genres: String) {
            val show = JSONObject().put("id", id).put("name", "Classification test $id")
                .put("genres", JSONArray(genres.toList()))
            type?.let { show.put("type", it) }
            language?.let { show.put("language", it) }
            shows.put(JSONObject().put("show", show))
        }
        add(1, "Animation", "Japanese", "Action")
        add(2, "Animation", "English", "Anime")
        add(3, "Scripted", "Korean", "Drama", "Romance")
        add(4, "Scripted", "Japanese", "Drama")
        add(5, "Reality", "Korean", "Drama")
        add(6, "Animation", "Korean", "Adventure")
        add(7, "Scripted", "English", "Drama")
        add(8, null, null)
        add(9, "Scripted", "Korean", "Comedy")
        val raw = shows.toString()
        val anime = LiveNetworkAdapters.parseTvMazeSearchJson(raw, FeedSection.ANIME)
        assertEquals(listOf("tvmaze:1", "tvmaze:2"), anime.map { it.contentId })
        assertTrue(anime.all { it.kind == MediaKind.ANIME && it.section == FeedSection.ANIME })
        val drama = LiveNetworkAdapters.parseTvMazeSearchJson(raw, FeedSection.KDRAMA)
        assertEquals(listOf("tvmaze:3"), drama.map { it.contentId })
        assertTrue(drama.all { it.kind == MediaKind.K_DRAMA })
        val home = LiveNetworkAdapters.parseTvMazeSearchJson(raw, FeedSection.HOME)
        assertEquals(listOf("tvmaze:4", "tvmaze:5", "tvmaze:6", "tvmaze:7", "tvmaze:9"), home.map { it.contentId })
        assertTrue(home.all { it.kind == MediaKind.SERIES && it.streams.isEmpty() && it.rating == null })
        assertTrue(LiveNetworkAdapters.parseTvMazeSearchJson(raw, FeedSection.LIVETV).isEmpty())
        assertTrue(LiveNetworkAdapters.parseTvMazeSearchJson(raw, FeedSection.ADULT).isEmpty())
    }
}
