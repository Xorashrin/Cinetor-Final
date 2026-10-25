package com.cinator.app.providers

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BoundedLosslessJsonTest {
    private fun parse(raw: String) = BoundedLosslessJson.parse(raw, 64 * 1024)

    @Test fun numbersRemainExactEvenInAndroidJsonContainers() {
        val data = parse("""{"height":720.0000000000000001,"size":110916740.000000001,"ratio":0.125,"items":[7.2e2,-0.25,true,false,null]}""") as JSONObject
        assertEquals(BigDecimal("720.0000000000000001"), data.opt("height"))
        assertEquals(BigDecimal("110916740.000000001"), data.opt("size"))
        assertEquals(BigDecimal("0.125"), data.opt("ratio"))
        val items = data.getJSONArray("items")
        assertEquals(BigDecimal("7.2e2"), items.opt(0))
        assertEquals(BigDecimal("-0.25"), items.opt(1))
        assertEquals(true, items.opt(2))
        assertEquals(false, items.opt(3))
        assertSame(JSONObject.NULL, items.opt(4))
    }

    @Test fun strictSyntaxRejectsAmbiguousAndMalformedTokens() {
        val invalid = listOf("", " ", "[1,]", "[,1]", "[1;2]", "{'a':1}", "{a:1}",
            "{\"a\":1,}", "{\"a\":1,\"\\u0061\":2}", "{\"a\":null,\"a\":1}",
            "01", "-01", "+1", ".1", "1.", "1e", "1e+", "--1", "NaN", "Infinity",
            "true false", "TRUE", "nullx", "/*x*/1", "[\u000b]", "[\u000c]",
            "\"\\x20\"", "\"\\u000g\"", "\"\\u123\"", "\"\n\"", "\"unterminated",
            "\"\\uD800\"", "\"\\uDC00\"", "\"\\uD800a\"", "\"\uD800\"")
        invalid.forEach { raw -> assertThrows(raw, IllegalArgumentException::class.java) { parse(raw) } }
        assertEquals("\"\\/\b\u000c\n\r\t😀", parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\uD83D\\uDE00\""))
    }

    @Test fun depthArraysNodesAndTokensHaveHardInclusiveBudgets() {
        parse("[".repeat(16) + "0" + "]".repeat(16))
        assertThrows(IllegalArgumentException::class.java) { parse("[".repeat(17) + "0" + "]".repeat(17)) }
        fun array(count: Int) = List(count) { "0" }.joinToString(",", "[", "]")
        assertEquals(256, (parse(array(256)) as JSONArray).length())
        assertThrows(IllegalArgumentException::class.java) { parse(array(257)) }
        // One root + 31*(one array + 256 values) + (one array + 223 values) = 8192.
        fun nodes(last: Int) = (List(31) { array(256) } + array(last)).joinToString(",", "[", "]")
        parse(nodes(223))
        assertThrows(IllegalArgumentException::class.java) { parse(nodes(224)) }
        parse("1".repeat(128))
        assertThrows(IllegalArgumentException::class.java) { parse("1".repeat(129)) }
        parse("1e128")
        parse("1e-128")
        for (token in listOf("1e129", "1e-129", "1e99999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { parse(token) }
        }
        assertEquals("a".repeat(16384), parse("\"" + "a".repeat(16384) + "\""))
        assertThrows(IllegalArgumentException::class.java) { parse("\"" + "a".repeat(16385) + "\"" ) }
    }

    @Test fun byteLimitsCountUtf8AndInterruptionIsPreserved() {
        assertEquals("é", BoundedLosslessJson.parse("\"é\"", 4))
        assertThrows(IllegalArgumentException::class.java) { BoundedLosslessJson.parse("\"é\"", 3) }
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) { parse("{}") }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
