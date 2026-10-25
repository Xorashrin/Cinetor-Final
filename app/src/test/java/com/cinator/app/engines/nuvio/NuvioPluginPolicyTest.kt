package com.cinator.app.engines.nuvio

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class NuvioPluginPolicyTest {
    private val origin = "https://plugins.example"
    private val manifestUrl = "$origin/reviewed/manifest.json"
    private val raw = """{"name":"Reviewed fixture","version":"1.0.0","scrapers":[{"id":"fixture","name":"Fixture","filename":"fixture.js"}]}"""
    private fun call(name: String, vararg args: Any): Any? {
        val cls = Class.forName("com.cinator.app.engines.nuvio.NuvioPluginPolicy")
        val method = cls.methods.single { it.name == name && it.parameterCount == args.size }
        return try { method.invoke(cls.getField("INSTANCE").get(null), *args) }
        catch (e: InvocationTargetException) { throw e.targetException }
    }
    private fun parse(json: String = raw, url: String = manifestUrl, origins: Set<String> = setOf(origin)) =
        call("parseManifest", json, url, origins)!!
    private fun property(value: Any, name: String): Any? = value.javaClass.getMethod("get$name").invoke(value)
    private fun rejected(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun upstreamScrapersShapeResolvesRelativeJavascriptWithoutExecuting() {
        val manifest = parse()
        assertEquals("1.0.0", property(manifest, "Version"))
        val scraper = (property(manifest, "Scrapers") as List<*>).single()!!
        assertEquals("fixture", property(scraper, "Id"))
        assertEquals("$origin/reviewed/fixture.js", property(scraper, "ScriptUrl"))
        assertEquals("BLOCKED_SECURITY", call("status"))
    }
    @Test fun rejectsUnknownFieldsInvalidVersionsIdsDuplicatesAndTypes() {
        listOf(
            "{}", "[]", raw + " true", raw.replace("1.0.0", "1"), raw.replace("1.0.0", "01.0.0"),
            raw.replace("\"fixture\"", "\"../fixture\""), raw.replace("\"fixture\"", "false"),
            raw.replace("\"name\":\"Fixture\"", "\"name\":\"Fixture\",\"eval\":true"),
            raw.replace("\"scrapers\":[", "\"scrapers\":[{\"id\":\"fixture\",\"name\":\"Other\",\"filename\":\"other.js\"},"),
            raw.replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.0\",\"version\":\"2.0.0\""),
            raw.replace("\"name\":\"Reviewed fixture\"", "\"name\":\"bad\\u0000\""),
            raw.replace("\"name\"", "'name'"), raw.replace("}]}", "},]}"),
            " ".repeat(65537) + raw
        ).forEach { bad -> rejected { parse(bad) } }
    }
    @Test fun rejectsTraversalAndNonJavascriptAndForeignOrigins() {
        listOf("../x.js", "/x.js", "a/../../x.js", "a//x.js", "a/./x.js", "a\\\\x.js", "%2e%2e/x.js",
            "a%252fx.js", "x.JS", "x.wasm", "x.js?auth=x", "x.js#x", "//evil.example/x.js",
            "http://plugins.example/x.js", "https://plugins.example.evil/x.js", "https://user@plugins.example/x.js",
            "https://plugins.example:443/x.js", "https://plugins.example./x.js", "https://plugins.example/a/../x.js"
        ).forEach { bad -> rejected { parse(raw.replace("fixture.js", bad)) } }
        rejected { parse(origins = emptySet()) }
        rejected { parse(url = "http://plugins.example/manifest.json") }
        rejected { parse(origins = setOf("https://plugins.example/path")) }
        rejected { parse(url = "https://127.0.0.1/manifest.json", origins = setOf("https://127.0.0.1")) }
        rejected { parse(url = "https://localhost/manifest.json", origins = setOf("https://localhost")) }
    }
    @Test fun acceptsNestedAndExplicitExactAllowedJavascriptUrls() {
        for (filename in listOf("scripts/fixture.js", "$origin/scripts/fixture.js")) parse(raw.replace("fixture.js", filename))
    }

    private val mediaUrl = "https://media.example/authorized/movie.mp4"
    private val allowed = setOf(mediaUrl)
    private val requestJson = """{"schemaVersion":1,"requestId":"r1","pluginId":"fixture","method":"getStreams","params":{"tmdbId":"123","mediaType":"movie"}}"""
    private fun request(raw: String = requestJson) = call("validateRequest", raw, allowed)!!
    @Test fun sandboxContractIsValidationOnlyWithFixedLeastPrivilegeQuotas() {
        val c = call("sandboxContract")!!
        assertEquals("VALIDATION_ONLY_NO_RUNTIME", property(c, "Implementation"))
        assertEquals("BLOCKED_SECURITY", property(c, "Status"))
        for (name in listOf("DirectNetwork", "Filesystem", "Auth", "Wasm", "Eval", "Dom", "Settings"))
            assertEquals(false, property(c, name))
        assertEquals(true, property(c, "ParentMediatedHttpsOnly"))
        val expected = mapOf("MaxInputBytes" to 65536, "MaxOutputBytes" to 262144, "MaxScriptBytes" to 1048576,
            "MaxMemoryBytes" to 16777216, "MaxFetches" to 8, "MaxFetchResponseBytes" to 131072,
            "MaxTotalFetchBytes" to 524288, "MaxResults" to 16, "DeadlineMs" to 5000)
        expected.forEach { (key, value) -> assertEquals(value, property(c, key)) }
    }
    @Test fun sandboxRequestAllowsOnlyTypedIdentityNoCodeOrSecrets() {
        assertEquals("r1", property(request(), "RequestId"))
        listOf(requestJson.replace("getStreams", "eval"), requestJson.replace("123", "../123"),
            requestJson.replace("movie", "adult"), requestJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            requestJson.replace("\"params\":{", "\"params\":{\"auth\":\"secret\","),
            requestJson.replace("\"method\":", "\"code\":\"while(true){}\",\"method\":"),
            requestJson.replace("\"mediaType\":\"movie\"", "\"mediaType\":\"tv\""),
            requestJson.replace("\"mediaType\":\"movie\"", "\"mediaType\":\"movie\",\"season\":1,\"episode\":1"),
            " ".repeat(65537) + requestJson, "[".repeat(17) + "0" + "]".repeat(17)
        ).forEach { bad -> rejected { request(bad) } }
        val tv = requestJson.replace("\"mediaType\":\"movie\"", "\"mediaType\":\"tv\",\"season\":1,\"episode\":2")
        assertEquals(2, property(request(tv), "Episode"))
        rejected { request(tv.replace("\"episode\":2", "\"episode\":0")) }
        rejected { call("validateRequest", requestJson, setOf("https://localhost/x")) }
    }
    @Test fun parentFetchMessagesDenyCredentialsRedirectsForeignUrlsAndQuotaOverruns() {
        val req = request()
        val fetch = """{"schemaVersion":1,"requestId":"r1","method":"GET","url":"$mediaUrl"}"""
        call("validateFetchRequest", fetch, req, allowed, 7, 4999)
        for (bad in listOf(fetch.replace("GET", "POST"), fetch.replace(mediaUrl, "$mediaUrl/other"),
            fetch.replace(mediaUrl, "http://media.example/x"), fetch.replace("\"method\":", "\"headers\":{\"Authorization\":\"x\"},\"method\":"),
            fetch.replace("r1", "r2"))) rejected { call("validateFetchRequest", bad, req, allowed, 0, 0) }
        for (count in listOf(-1, 8, Int.MAX_VALUE)) rejected { call("validateFetchRequest", fetch, req, allowed, count, 0) }
        for (elapsed in listOf(-1, 5000)) rejected { call("validateFetchRequest", fetch, req, allowed, 0, elapsed) }
        val response = """{"schemaVersion":1,"requestId":"r1","url":"$mediaUrl","status":200,"body":"ok"}"""
        call("validateFetchResponse", response, req, allowed, 0, 0)
        rejected { call("validateFetchResponse", response.replace("200", "302"), req, allowed, 0, 0) }
        rejected { call("validateFetchResponse", response.replace("\"status\":", "\"headers\":{\"Set-Cookie\":\"x\"},\"status\":"), req, allowed, 0, 0) }
        rejected { call("validateFetchResponse", response, req, allowed, 524287, 0) }
        rejected { call("validateFetchResponse", response, req, allowed, -1, 0) }
        rejected { call("validateFetchResponse", response.replace("ok", "x".repeat(131073)), req, allowed, 0, 0) }
    }
    @Test fun sandboxOutputIsBoundedExactIdentityAndAllowlistedDataNotPlayableEvidence() {
        val req = request()
        val result = """{"name":"Fixture","title":"Authorized sample","url":"$mediaUrl"}"""
        val output = """{"schemaVersion":1,"requestId":"r1","tmdbId":"123","streams":[$result]}"""
        call("validateResponse", output, req, allowed, 4999)
        listOf(output.replace("r1", "r2"), output.replace("123", "456"), output.replace(mediaUrl, "$mediaUrl?token=secret"),
            output.replace("\"streams\":", "\"auth\":\"secret\",\"streams\":"),
            output.replace("\"name\":", "\"headers\":{\"Cookie\":\"x\"},\"name\":"),
            output.replace("[$result]", "[" + List(17) { result }.joinToString(",") + "]"),
            " ".repeat(262145) + output).forEach { bad -> rejected { call("validateResponse", bad, req, allowed, 0) } }
        rejected { call("validateResponse", output, req, allowed, 5000) }
    }

    @Test fun digestIsExactSha256BytesNotAClaimOfTrust() {
        val bytes = "abc".toByteArray()
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(true, call("matchesExpectedSha256", bytes, expected))
        assertEquals(false, call("matchesExpectedSha256", "abd".toByteArray(), expected))
        for (bad in listOf("", "a".repeat(63), "g".repeat(64), expected.uppercase(), "sha256:$expected")) {
            assertEquals(false, call("matchesExpectedSha256", bytes, bad))
        }
        assertEquals(false, call("matchesExpectedSha256", ByteArray(1048577), expected))
        val scraper = (property(parse(), "Scrapers") as List<*>).single()!!
        assertEquals(false, call("isEmbeddedScriptApproved", scraper, bytes))
    }
}
