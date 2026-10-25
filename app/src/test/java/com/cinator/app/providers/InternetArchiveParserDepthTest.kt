package com.cinator.app.providers

import com.cinator.app.data.TextHttpClient
import com.cinator.app.domain.FeedSection
import org.junit.Assert.*
import org.junit.Test

class InternetArchiveParserDepthTest {
    @Test fun nestedRemotePayloadIsRejectedBeforeJsonParser() {
        val nested="[".repeat(40)+"0"+"]".repeat(40)
        val raw="""{"metadata":{"identifier":"item","mediatype":"movies","title":"Open film","licenseurl":"https://creativecommons.org/licenses/by/3.0/"},"files":[],"extra":$nested}"""
        val p=InternetArchivePlaybackProvider(ProviderInfo("ia","IA",FeedSection.HOME,
            ProviderCapabilities(playback=true),"https://archive.org/"),setOf("item"),object:TextHttpClient{override fun get(url:String)=raw})
        assertThrows(IllegalArgumentException::class.java){p.catalog()}
    }
}
