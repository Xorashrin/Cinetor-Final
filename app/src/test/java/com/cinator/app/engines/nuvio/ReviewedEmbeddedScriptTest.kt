package com.cinator.app.engines.nuvio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ReviewedEmbeddedScriptTest {
    // Reflection lets RED fail as an assertion when the production class is absent.
    private object ReviewedEmbeddedScript {
        private fun target(): Class<*> {
            val cls = runCatching { Class.forName("com.cinator.app.engines.nuvio.ReviewedEmbeddedScript") }.getOrNull()
            assertTrue("Missing reviewed script admission", cls != null)
            return cls!!
        }
        val BLENDER_RESOLVER_ID: String get() = target().getField("BLENDER_RESOLVER_ID").get(null) as String
        val BLENDER_RESOLVER_SHA256: String get() = target().getField("BLENDER_RESOLVER_SHA256").get(null) as String
        fun get(id: String): String? = target().let { it.getMethod("get", String::class.java).invoke(it.getField("INSTANCE").get(null), id) as String? }
        fun isApproved(id: String, source: String): Boolean = target().let {
            it.getMethod("isApproved", String::class.java, String::class.java).invoke(it.getField("INSTANCE").get(null), id, source) as Boolean
        }
    }

    @Test fun admitsOnlyTheSourceEmbeddedResolverWithItsPinnedDigest() {
        val source = ReviewedEmbeddedScript.get(ReviewedEmbeddedScript.BLENDER_RESOLVER_ID)
            ?: error("reviewed resolver missing")
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(ReviewedEmbeddedScript.BLENDER_RESOLVER_SHA256, actual)
        assertTrue(ReviewedEmbeddedScript.isApproved(ReviewedEmbeddedScript.BLENDER_RESOLVER_ID, source))
        assertFalse(ReviewedEmbeddedScript.isApproved(ReviewedEmbeddedScript.BLENDER_RESOLVER_ID, "$source\n"))
        assertFalse(ReviewedEmbeddedScript.isApproved(ReviewedEmbeddedScript.BLENDER_RESOLVER_ID, source.replaceFirst("use strict", "use sloppy")))
        assertFalse(ReviewedEmbeddedScript.isApproved(ReviewedEmbeddedScript.BLENDER_RESOLVER_ID, ""))
        assertFalse(ReviewedEmbeddedScript.isApproved("other", source))
        assertNull(ReviewedEmbeddedScript.get("other"))
    }

    @Test fun resolverIsTheRestrictedExplicitDocumentSubsetNotFullNuvioCompatibility() {
        val source = ReviewedEmbeddedScript.get(ReviewedEmbeddedScript.BLENDER_RESOLVER_ID)!!

        assertTrue(source.contains("module.exports.getStreams = function (parentDocument, expectedUuid, preferredHeight)"))
        assertTrue(source.contains("JSON.parse(parentDocument)"))
        assertTrue(source.contains("JSON.stringify(results)"))
        assertTrue(source.contains("https://video.blender.org/object-storage/web_videos/"))
        assertFalse(Regex("\\b(require|fetch|XMLHttpRequest|WebSocket|eval|Function|WebAssembly)\\b").containsMatchIn(source))
    }
}
