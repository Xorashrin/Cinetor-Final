package com.cinator.app.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityMatrixParserTest {
    private val none = """{
        "catalog":false,"search":false,"details":false,"episodes":false,
        "directPlayback":false,"download":false,"externalPlayback":false
    }"""

    private fun entry(
        id: String = "stremio",
        status: String = "SUPPORTED_PROTOCOL",
        capability: String = none,
        evidenceUrl: String = "null",
        version: String = "null"
    ) = """{
        "id":"$id","ecosystem":"Stremio","name":"Stremio HTTP",
        "status":"$status","capability":$capability,
        "reason":"Protocol adapter only","evidenceUrl":$evidenceUrl,"version":$version
    }"""

    private fun matrix(vararg entries: String, schemaVersion: Int = 1) =
        """{"schemaVersion":$schemaVersion,"entries":[${entries.joinToString(",")}]}"""

    @Test fun parsesTypedMatrix() {
        val capabilities = """{
            "catalog":true,"search":true,"details":true,"episodes":true,
            "directPlayback":true,"download":false,"externalPlayback":false
        }"""
        val parsed = CompatibilityMatrixParser.parse(matrix(entry(
            id = "blender", status = "SUPPORTED_E2E", capability = capabilities,
            evidenceUrl = "\"https://evidence.example/blender\"", version = "\"1.0\""
        )))

        assertEquals(1, parsed.schemaVersion)
        val item = parsed.entries.single()
        assertEquals("blender", item.id)
        assertEquals(CompatibilityStatus.SUPPORTED_E2E, item.status)
        assertTrue(item.capability.directPlayback)
        assertFalse(item.capability.download)
        assertEquals("https://evidence.example/blender", item.evidenceUrl)
        assertEquals("1.0", item.version)
    }

    @Test fun preservesExplicitNullEvidenceAndVersion() {
        val item = CompatibilityMatrixParser.parse(matrix(entry())).entries.single()
        assertNull(item.evidenceUrl)
        assertNull(item.version)
    }

    @Test fun rejectsDuplicateIds() {
        assertRejected(matrix(entry(id = "same"), entry(id = "same")))
    }

    @Test fun rejectsUnknownAndErrorLikeStatuses() {
        listOf("SUPPORTED", "ERROR", "error", "").forEach { status ->
            assertRejected(matrix(entry(status = status)))
        }
    }

    @Test fun rejectsControlCharactersInTextFields() {
        val fields = listOf("id", "ecosystem", "name", "reason", "version")
        fields.forEach { field ->
            val base = if (field == "version") entry(version = "\"1\"") else entry()
            val bad = base.replace("\"$field\":\"", "\"$field\":\"bad\\u0000")
            assertRejected(matrix(bad))
        }
        assertRejected(matrix(entry(evidenceUrl = "\"https://example.test/a\\u000Ab\"")))
    }

    @Test fun rejectsInvalidOrNonHttpsEvidenceUrls() {
        listOf(
            "\"http://example.test/evidence\"",
            "\"ftp://example.test/evidence\"",
            "\"https:///missing-host\"",
            "\"not a url\"",
            "\"https://user:pass@example.test/evidence\""
        ).forEach { assertRejected(matrix(entry(evidenceUrl = it))) }
    }

    @Test fun rejectsAnyCapabilityForBlockedAndReferenceStatuses() {
        val statuses = listOf("BLOCKED_SECURITY", "BLOCKED_RIGHTS_OR_API", "REFERENCE_ONLY")
        val names = listOf("catalog", "search", "details", "episodes", "directPlayback", "download", "externalPlayback")
        statuses.forEach { status ->
            names.forEach { name ->
                val capability = none.replace("\"$name\":false", "\"$name\":true")
                assertRejected(matrix(entry(status = status, capability = capability)))
            }
        }
    }

    @Test fun requiresSupportedE2eEvidenceVersionAndCoreCapabilities() {
        val allCore = """{
            "catalog":true,"search":true,"details":true,"episodes":false,
            "directPlayback":true,"download":false,"externalPlayback":false
        }"""
        assertRejected(matrix(entry(status = "SUPPORTED_E2E", capability = allCore, version = "\"1\"")))
        assertRejected(matrix(entry(status = "SUPPORTED_E2E", capability = allCore, evidenceUrl = "\"https://example.test/e\"")))
        listOf("catalog", "search", "details", "directPlayback").forEach { required ->
            val missing = allCore.replace("\"$required\":true", "\"$required\":false")
            assertRejected(matrix(entry(
                status = "SUPPORTED_E2E", capability = missing,
                evidenceUrl = "\"https://example.test/e\"", version = "\"1\""
            )))
        }
    }

    @Test fun rejectsMalformedMissingAndWrongTypedData() {
        assertRejected("not json")
        assertRejected("{}")
        assertRejected("""{"schemaVersion":1,"entries":"wrong"}""")
        assertRejected(matrix(entry().replace("\"catalog\":false,", "")))
        assertRejected(matrix(entry().replace("\"search\":false", "\"search\":\"false\"")))
        assertRejected(matrix(entry().replace("\"reason\":\"Protocol adapter only\"", "\"reason\":\"\"")))
        assertRejected(matrix(entry(id = "")))
        assertRejected(matrix(entry(), schemaVersion = 2))
    }

    @Test fun rejectsUnknownFieldsToKeepSchemaFailClosed() {
        assertRejected(matrix(entry()).replaceFirst("{", "{\"unexpected\":true,"))
        assertRejected(matrix(entry().replaceFirst("{", "{\"unexpected\":true,")))
        assertRejected(matrix(entry(capability = none.replaceFirst("{", "{\"unexpected\":true,"))))
    }

    @Test fun rejectsExternalStatusWithDirectPlaybackOrDownload() {
        listOf("directPlayback", "download").forEach { name ->
            assertRejected(matrix(entry(status = "EXTERNAL_AUTHORIZED", capability =
                none.replace("\"$name\":false", "\"$name\":true"))))
        }
    }

    @Test fun rejectsDownloadWithoutDirectPlayback() {
        assertRejected(matrix(entry(capability = none.replace("\"download\":false", "\"download\":true"))))
    }

    @Test fun rejectsPermissiveJsonSyntaxAndDuplicateObjectKeys() {
        val valid = matrix(entry())
        listOf(
            valid + " trailing",
            valid.replaceFirst("{", "{\"schemaVersion\":1,"),
            valid.replace("\"catalog\":false", "\"catalog\":true,\"catalog\":false"),
            valid.replace("\"schemaVersion\"", "schemaVersion"),
            valid.replace("\"schemaVersion\"", "'schemaVersion'"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":01"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1e0"),
            valid.replace("\"entries\":[", "\"entries\":[,"),
            valid.replace("\"id\":\"stremio\"", "\"id\":\"bad\\u0085\"")
        ).forEach(::assertRejected)
    }

    @Test fun rejectsInvalidPortsAndEscapedUrlControlCharacters() {
        listOf("https://example.test:0/e", "https://example.test:65536/e", "https://example.test/e%0a").forEach {
            assertRejected(matrix(entry(evidenceUrl = "\"$it\"")))
        }
    }

    @Test fun acceptsEveryDeclaredStatusWithHonestCapabilities() {
        CompatibilityStatus.values().filter { it != CompatibilityStatus.SUPPORTED_E2E }.forEach {
            val parsed = CompatibilityMatrixParser.parse(matrix(entry(status = it.name)))
            assertEquals(it, parsed.entries.single().status)
        }
    }

    private fun assertRejected(raw: String) {
        assertThrows(IllegalArgumentException::class.java) {
            CompatibilityMatrixParser.parse(raw)
        }
    }
}
