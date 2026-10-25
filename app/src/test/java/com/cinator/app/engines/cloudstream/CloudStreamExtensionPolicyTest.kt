package com.cinator.app.engines.cloudstream

import org.junit.Assert.*
import org.junit.Test

class CloudStreamExtensionPolicyTest {
    @Test fun pluginDescriptorsMatchUpstreamSchema() {
        val policy = CloudStreamExtensionPolicy()
        val method = try { policy.javaClass.getMethod("parsePlugins", String::class.java) }
        catch (_: NoSuchMethodException) { fail("Missing plugin descriptor validation"); return }
        val plugins = method.invoke(policy, pluginJson()) as List<*>
        val plugin = plugins.single()!!
        assertEquals("Fixture", plugin.javaClass.getMethod("getInternalName").invoke(plugin))
        assertEquals(listOf("Movie", "TvSeries"), plugin.javaClass.getMethod("getTvTypes").invoke(plugin))
        assertEquals("en", plugin.javaClass.getMethod("getLanguage").invoke(plugin))
        assertEquals(1, plugin.javaClass.getMethod("getStatus").invoke(plugin))
        assertEquals(7, plugin.javaClass.getMethod("getVersion").invoke(plugin))
        for ((old, replacement) in listOf(
            "https://example.com/Fixture.cs3" to "http://example.com/Fixture.cs3",
            "https://example.com/Fixture.cs3" to "https://example.com/../Fixture.cs3",
            "https://example.com/Fixture.cs3" to "https://example.com/%252e%252e/Fixture.cs3",
            "https://example.com/Fixture.cs3" to "https://localhost/Fixture.cs3",
            "https://example.com/Fixture.cs3" to "https://example.com/Fixture.jar",
            "https://example.com/Fixture.cs3" to "https://user@example.com/Fixture.cs3",
            "https://example.com/Fixture.cs3" to "https://example.com/Fixture.cs3?token=x",
            "https://example.com/Fixture.cs3" to "https://example.com/" + "a".repeat(1100) + ".cs3",
            "\"version\":7" to "\"version\":\"7\"",
            "\"version\":7" to "\"version\":-1",
            "\"status\":1" to "\"status\":4",
            "\"language\":\"en\"" to "\"language\":true",
            "[\"Movie\",\"TvSeries\"]" to "[1]"
        )) {
            try { method.invoke(policy, pluginJson().replace(old, replacement)); fail("Accepted $replacement") }
            catch (e: java.lang.reflect.InvocationTargetException) { assertNotNull(e.cause) }
        }
        try { method.invoke(policy, "[" + pluginJson().removeSurrounding("[", "]") + "," + pluginJson().removeSurrounding("[", "]") + "]"); fail("Duplicate identity") }
        catch (e: java.lang.reflect.InvocationTargetException) { assertNotNull(e.cause) }
    }

    @Test fun invalidRepositoryDescriptorsAreRejected() {
        val policy = CloudStreamExtensionPolicy()
        listOf(
            "{}", "{\"name\":\"Repo\",\"manifestVersion\":2,\"pluginLists\":[]}",
            "{\"name\":\"Repo\",\"manifestVersion\":1,\"pluginLists\":[1]}",
            "{\"name\":\"Repo\",\"manifestVersion\":1,\"pluginLists\":[\"https://127.0.0.1/plugins.json\"]}",
            " ".repeat(65537)
        ).forEach { raw ->
            try { policy.parseRepository(raw); fail("Accepted invalid repository") } catch (_: Exception) { }
        }
    }

    @Test fun onlyExactEmbeddedPinAdmitsBoundedInventoryButNeverExecution() {
        val policyClass = CloudStreamExtensionPolicy::class.java
        val pinClass = try { Class.forName("com.cinator.app.engines.cloudstream.CloudStreamEmbeddedDigestPin") }
        catch (_: ClassNotFoundException) { fail("Missing embedded digest admission"); return }
        val bytes = fixtureZip(listOf("manifest.json" to manifest(), "classes.dex" to byteArrayOf(1, 2, 3)))
        val digest = sha256(bytes)
        val pin = pinClass.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java)
            .newInstance("Fixture", "https://example.com/Fixture.cs3", 7, digest)
        val policy = policyClass.getConstructor(Set::class.java).newInstance(setOf(pin))
        val plugin = policy.parsePlugins(pluginJson()).single()
        val method = policyClass.getMethod("inspectEmbedded", CloudStreamPluginDescriptor::class.java, ByteArray::class.java)
        val admitted = method.invoke(policy, plugin, bytes)
        assertEquals("BLOCKED_SECURITY", admitted.javaClass.getMethod("getRuntimeStatus").invoke(admitted))
        assertEquals(false, admitted.javaClass.getMethod("getExecutionAllowed").invoke(admitted))
        assertEquals(digest, admitted.javaClass.getMethod("getSha256").invoke(admitted))
        assertEquals(2, (admitted.javaClass.getMethod("getEntries").invoke(admitted) as List<*>).size)
        fun rejected(target: CloudStreamExtensionPolicy, descriptor: CloudStreamPluginDescriptor, payload: ByteArray) {
            try { method.invoke(target, descriptor, payload); fail("Accepted unpinned or unsafe payload") }
            catch (e: java.lang.reflect.InvocationTargetException) { assertNotNull(e.cause) }
        }
        rejected(CloudStreamExtensionPolicy(), plugin, bytes)
        rejected(policy, plugin.copy(version = 8), bytes)
        rejected(policy, plugin.copy(url = "https://other.example.com/Fixture.cs3"), bytes)
        rejected(policy, plugin, bytes + byteArrayOf(0))
        val badArchives = listOf(
            fixtureZip(listOf("manifest.json" to manifest(), "../classes.dex" to byteArrayOf(1))),
            fixtureZip(listOf("manifest.json" to manifest(), "/classes.dex" to byteArrayOf(1))),
            fixtureZip(listOf("manifest.json" to manifest(), "a\\classes.dex" to byteArrayOf(1))),
            fixtureZip(listOf("manifest.json" to manifest(), "%2e%2e/x" to byteArrayOf(1))),
            fixtureZip(listOf("manifest.json" to manifest(), "MANIFEST.JSON" to manifest())),
            fixtureZip(listOf("classes.dex" to byteArrayOf(1))),
            fixtureZip(listOf("manifest.json" to manifest(8))),
            fixtureZip(listOf("manifest.json" to "{}".toByteArray())),
            fixtureZip(listOf("manifest.json" to ByteArray(65537) { 32 })),
            fixtureZip(listOf("manifest.json" to manifest(), "large" to ByteArray(2 * 1024 * 1024))),
            fixtureZip(listOf("manifest.json" to manifest()) + (0..128).map { "file$it" to byteArrayOf(1) }),
            bytes.copyOf(bytes.size - 22),
            byteArrayOf(1, 2, 3),
            ByteArray(2 * 1024 * 1024 + 1)
        )
        badArchives.forEach { archive ->
            val badPin = pinClass.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java)
                .newInstance("Fixture", plugin.url, 7, sha256(archive))
            rejected(policyClass.getConstructor(Set::class.java).newInstance(setOf(badPin)), plugin, archive)
        }
        val invalidPin = pinClass.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java)
            .newInstance("Fixture", plugin.url, 7, "not-a-sha256")
        try { policyClass.getConstructor(Set::class.java).newInstance(setOf(invalidPin)); fail("Invalid pin") }
        catch (e: java.lang.reflect.InvocationTargetException) { assertNotNull(e.cause) }
    }

    private fun manifest(version: Int = 7): ByteArray =
        """{"name":"Fixture metadata","version":$version,"pluginClassName":"fixtures.InertPlugin"}""".toByteArray()

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fixtureZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun deeplyNestedUntrustedMetadataIsRejectedBeforeJsonParsing() {
        val raw = pluginJson().replace("\"authors\":[\"Test\"]",
            "\"authors\":[\"Test\"],\"extra\":" + "[".repeat(40) + "0" + "]".repeat(40))
        try { CloudStreamExtensionPolicy().parsePlugins(raw); fail("Unbounded nesting") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun embeddedAllowlistCannotBeChangedAfterConstruction() {
        val bytes = fixtureZip(listOf("manifest.json" to manifest()))
        val pins = mutableSetOf<CloudStreamEmbeddedDigestPin>()
        val policy = CloudStreamExtensionPolicy(pins)
        val plugin = policy.parsePlugins(pluginJson()).single()
        pins += CloudStreamEmbeddedDigestPin(plugin.internalName, plugin.url, plugin.version, sha256(bytes))
        try { policy.inspectEmbedded(plugin, bytes); fail("Mutable trust configuration") }
        catch (_: IllegalStateException) { }
    }

    private fun pluginJson(): String = """[{"url":"https://example.com/Fixture.cs3","status":1,
      "version":7,"apiVersion":1,"name":"Fixture metadata","internalName":"Fixture",
      "authors":["Test"],"language":"en","tvTypes":["Movie","TvSeries"]}]"""

    @Test fun repositoryUsesActualPluginListsAndNeverGrantsExecution() {
        // Reflection keeps this first regression executable before the API exists.
        val type = try { Class.forName("com.cinator.app.engines.cloudstream.CloudStreamExtensionPolicy") }
        catch (_: ClassNotFoundException) { fail("Missing metadata-only CloudStream policy"); return }
        val policy = type.getConstructor().newInstance()
        val repo = type.getMethod("parseRepository", String::class.java).invoke(policy,
            """{"name":"Reviewed fixtures","description":"Metadata only","manifestVersion":1,
              "pluginLists":["https://example.com/reviewed/plugins.json"]}""")
        assertEquals(listOf("https://example.com/reviewed/plugins.json"), repo.javaClass.getMethod("getPluginLists").invoke(repo))
        assertEquals("BLOCKED_SECURITY", type.getMethod("getRuntimeStatus").invoke(policy))
    }
}
