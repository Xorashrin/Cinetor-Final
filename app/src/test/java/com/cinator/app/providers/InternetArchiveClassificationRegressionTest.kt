package com.cinator.app.providers

import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternetArchiveClassificationRegressionTest {
    private fun response(title:String="Big Buck Bunny", date:String="2008", subject:String="open movie", length:String="596.45"):String {
        val id="item"
        return JSONObject().put("metadata",JSONObject()
            .put("identifier",id).put("mediatype","movies").put("title",title)
            .put("date",date).put("subject",subject)
            .put("licenseurl","https://creativecommons.org/licenses/by/3.0/"))
            .put("files",JSONArray()
                .put(JSONObject().put("name","film.mp4").put("source","derivative").put("original","film.avi").put("format","512Kb MPEG4").put("size","123456").put("length",length))
                .put(JSONObject().put("name","film.avi").put("source","original").put("format","Cinepack").put("size","500000").put("length",length))).toString()
    }
    private fun provider(raw:String)=InternetArchivePlaybackProvider(
        ProviderInfo("ia","IA",FeedSection.HOME,ProviderCapabilities(playback=true,download=true),"https://archive.org/"),setOf("item"),
        object:TextHttpClient{override fun get(url:String)=raw})

    @Test fun ordinaryDateAndDecimalDurationAreParsed() {
        val p=provider(response())
        val card=p.catalog().single()
        assertEquals("2008",card.year)
        assertEquals(1,p.resolve(ResolveRequest(card)).size)
    }
    @Test fun excludedContentWordsAreActuallyRejected() {
        for (value in listOf("Anime", "Adult", "Korean drama", "Hentai")) assertTrue(provider(response(subject=value)).catalog().isEmpty())
    }
}
