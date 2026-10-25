package com.cinator.app.engines.nuvio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class IsolatedJsRequestTest {
    private fun validFields(documentSize: Int = 3): Map<String, Any?> = mapOf(
        "requestId" to UUID.randomUUID().toString(),
        "pluginId" to "blender-reviewed-document-v1",
        "sourceSha256" to "4ef8dd159ce0b945a590db2b14f2a23c4b67be6a3161ca69018b15bed3bf29bc",
        "document" to ByteArray(documentSize) { 1 },
        "uuid" to "2a1f3f90-6ba9-4c42-8b08-34a0ee5ad726",
        "height" to 1080,
    )

    @Test fun acceptsOnlyTheExactPinnedRequestShape() {
        val accepted = validate(validFields())
        assertEquals("Accepted", accepted.javaClass.simpleName)
        assertEquals(3, documentOf(accepted).size)
    }

    @Test fun rejectsUnknownMissingAndWrongTypedFields() {
        assertEquals("INVALID_REQUEST", validate(validFields() + ("source" to "code")).error())
        assertEquals("INVALID_REQUEST", validate(validFields() - "height").error())
        assertEquals("INVALID_REQUEST", validate(validFields() + ("height" to 1080L)).error())
    }

    @Test fun rejectsUnknownSourceAndInvalidDigestOrId() {
        assertEquals("UNKNOWN_SOURCE", validate(validFields() + ("pluginId" to "other")).error())
        assertEquals("UNKNOWN_SOURCE", validate(validFields() + ("sourceSha256" to "0".repeat(64))).error())
    }

    @Test fun enforcesDocumentIdentityAndScalarBudgets() {
        assertEquals("DOCUMENT_TOO_LARGE", validate(validFields(65_537)).error())
        assertEquals("INVALID_REQUEST", validate(validFields() + ("requestId" to "not-a-uuid")).error())
        assertEquals("INVALID_REQUEST", validate(validFields() + ("uuid" to "NOT-A-UUID-0000-0000-000000000000")).error())
        assertEquals("INVALID_REQUEST", validate(validFields() + ("height" to 143)).error())
        assertEquals("INVALID_REQUEST", validate(validFields() + ("height" to 2161)).error())
    }

    @Test fun acceptedRequestOwnsItsDocumentBytes() {
        val document = byteArrayOf(7)
        val fields = validFields() + ("document" to document)
        val accepted = validate(fields)
        document[0] = 9
        assertEquals(7, documentOf(accepted)[0].toInt())
    }

    @Test fun authenticatesProtocolEnvelopeBeforeAnyAdmission() {
        val type = Class.forName("com.cinator.app.engines.nuvio.runtime.IsolatedJsRequest")
        val companion = type.getField("Companion").get(null)
        val method = companion.javaClass.methods.singleOrNull { it.name == "isAllowedEnvelope" }
        assertTrue("Missing protocol envelope admission", method != null)
        assertEquals("Missing independent arg2 and message-flag admission", 8, method!!.parameterCount)
        fun allowed(uid: Int = 42, owner: Int = 42, what: Int = 1, version: Int = 1,
                    arg2: Int = 0, flags: Int = 0, obj: Boolean = false, reply: Boolean = true): Boolean =
            method!!.invoke(companion, uid, owner, what, version, arg2, flags, obj, reply) as Boolean
        assertTrue(allowed())
        assertEquals(false, allowed(uid = 99))
        assertEquals(false, allowed(version = 2))
        assertEquals(false, allowed(arg2 = 1))
        assertEquals(false, allowed(flags = 1))
        assertEquals(false, allowed(obj = true))
        assertEquals(false, allowed(reply = false))
        assertEquals(false, allowed(what = 3))
        assertTrue(allowed(what = 2, reply = false))
    }

    private fun validate(fields: Map<String, Any?>): Any {
        val type = runCatching { Class.forName("com.cinator.app.engines.nuvio.runtime.IsolatedJsRequest") }.getOrNull()
        assertTrue("Missing fixed-source admission validator", type != null)
        val companion = type!!.getField("Companion").get(null)
        return companion.javaClass.getMethod("validate", Map::class.java).invoke(companion, fields)
    }

    private fun documentOf(admission: Any): ByteArray {
        val request = admission.javaClass.getMethod("getRequest").invoke(admission)
        return request.javaClass.getMethod("getDocument").invoke(request) as ByteArray
    }

    private fun Any.error(): String = javaClass.getMethod("getError").invoke(this) as String
}
