package com.cinator.app.engines.nuvio.runtime

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class IsolatedJsBrokerPolicyTest {
    private fun policy(): Pair<Class<*>, Any> {
        val type = runCatching { Class.forName("com.cinator.app.engines.nuvio.runtime.BrokerValidation") }.getOrNull()
        assertNotNull("Missing broker admission boundary", type)
        return type!! to type.getField("INSTANCE").get(null)
    }

    @Test fun repliesRequireCorrelatedFrameworkUidExactFieldsAndStrictBoundedJson() {
        val (type, instance) = policy()
        val method = type.getMethod("decodeReply", Map::class.java, Int::class.javaPrimitiveType,
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val id = "00000000-0000-0000-0000-000000000001"
        val fields = mapOf<String, Any?>("requestId" to id, "uid" to 99001, "pid" to 123,
            "engineVersion" to "QuickJS 2026-06-04 (04be246)", "result" to "[]".toByteArray())
        fun call(values: Map<String, Any?> = fields, what: Int = 101, uid: Int = 99001): Any? =
            method.invoke(instance, values, what, id, uid, 10001, 456)
        assertNotNull(call())
        for (values in listOf(
            fields + ("requestId" to "other"), fields + ("uid" to 10001), fields + ("uid" to 99001L),
            fields + ("extra" to "ignored"), fields + ("pid" to 456), fields + ("result" to "[]"),
            fields + ("result" to ByteArray(262145)), fields + ("result" to byteArrayOf(0xc0.toByte(), 0xaf.toByte())),
            fields + ("result" to "[unquoted]".toByteArray()), fields + ("result" to "[] trailing".toByteArray()),
            fields + ("result" to "[1,]".toByteArray()), fields + ("result" to "[NaN]".toByteArray()),
            fields + ("result" to ("[".repeat(66) + "]".repeat(66)).toByteArray())
        )) {
            try { call(values); fail("invalid reply accepted") }
            catch (expected: InvocationTargetException) { assertTrue(expected.cause is IllegalStateException) }
        }
        try { call(uid = 99002); fail("forged payload uid accepted") }
        catch (expected: InvocationTargetException) { assertTrue(expected.cause is IllegalStateException) }
        assertNotNull(call(fields + ("result" to "[{\"x\":[true,false,null,-1.25e+2,\"é\"]}]".toByteArray())))
        val errorFields = fields - "result" + ("error" to "NATIVE_UNAVAILABLE")
        assertNotNull(call(errorFields, what = 102))
        try { call(errorFields + ("error" to "private URL or exception"), what = 102); fail("raw error accepted") }
        catch (expected: InvocationTargetException) { assertTrue(expected.cause is IllegalStateException) }
    }

    @Test fun acceptedUnknownOrAliveGenerationRemainsQuarantinedUntilDeath() {
        val (type, instance) = policy()
        val method = type.getMethod("isRetired", Boolean::class.javaPrimitiveType,
            java.lang.Boolean::class.java, Boolean::class.javaPrimitiveType)
        fun retired(accepted: Boolean, alive: Boolean?, dead: Boolean): Boolean =
            method.invoke(instance, accepted, alive, dead) as Boolean
        assertFalse(retired(true, true, false))
        assertFalse(retired(true, null, false))
        assertTrue(retired(true, true, true))
        assertTrue(retired(true, false, false))
        assertTrue(retired(false, null, false))
    }

    @Test fun admitsOnlyRegistryPinnedSourceAndBoundedCanonicalInput() {
        val (type, instance) = policy()
        val method = type.getMethod("encodeRequest", String::class.java, String::class.java,
            String::class.java, String::class.java, Int::class.javaPrimitiveType)
        val id = "blender-reviewed-document-v1"
        val sha = "4ef8dd159ce0b945a590db2b14f2a23c4b67be6a3161ca69018b15bed3bf29bc"
        val uuid = "00000000-0000-0000-0000-000000000000"
        fun call(plugin: String = id, digest: String = sha, doc: String = "{}", value: String = uuid, height: Int = 720): ByteArray =
            method.invoke(instance, plugin, digest, doc, value, height) as ByteArray
        assertArrayEquals("{}".toByteArray(), call())
        for (action in listOf<() -> Unit>(
            { call(plugin = "unknown") }, { call(digest = "0".repeat(64)) },
            { call(doc = "é".repeat(32769)) }, { call(doc = "\uD800") },
            { call(value = "not-a-uuid") }, { call(height = 143) }, { call(height = 2161) }
        )) {
            try { action(); fail("invalid request accepted") }
            catch (expected: InvocationTargetException) { assertTrue(expected.cause is IllegalArgumentException) }
        }
    }
}
