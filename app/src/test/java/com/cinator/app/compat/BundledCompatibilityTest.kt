package com.cinator.app.compat

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BundledCompatibilityTest {
    private fun asset(name: String): String = listOf(File("src/main/assets/$name"),
        File("app/src/main/assets/$name")).first { it.isFile }.readText()

    @Test fun generatedMatrixUsesExactKnownReferenceIdsAndDoesNotGrantRuntime() {
        val matrix = CompatibilityMatrixParser.parse(asset("provider_compatibility.json"))
        assertEquals(matrix.entries.size, matrix.entries.map { it.id }.toSet().size)
        assertEquals(79, matrix.entries.count { it.status == CompatibilityStatus.REFERENCE_ONLY &&
            it.ecosystem in setOf("nuvio", "cloudstream") })
        for (id in listOf("nuvio-js", "cloudstream-dex")) {
            val entry = matrix.entries.single { it.id == id }
            assertEquals(CompatibilityStatus.BLOCKED_SECURITY, entry.status)
            assertFalse(entry.capability.directPlayback)
            assertFalse(entry.capability.download)
        }
        val verified = matrix.entries.filter { it.status == CompatibilityStatus.SUPPORTED_E2E }
        assertEquals(listOf("blender-open-films"), verified.map { it.id })
        assertTrue(verified.single().reason.contains("one-film", ignoreCase=true))
        assertEquals("19683fb", verified.single().version)
        assertTrue(verified.single().evidenceUrl!!.contains("v0.4.0-preview"))
    }
}
