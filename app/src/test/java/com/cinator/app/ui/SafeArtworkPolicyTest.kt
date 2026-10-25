package com.cinator.app.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress

class SafeArtworkPolicyTest {
    @Test fun rejectsPrivateOrMixedDnsAnswersAndKeepsTheActualCheckedAddresses() {
        val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val private = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        assertSame(public, SafeArtworkPolicy.checkedAddresses("images.example.com", listOf(public)).single())
        for (answers in listOf(emptyList(), listOf(private), listOf(public, private))) {
            rejected { SafeArtworkPolicy.checkedAddresses("images.example.com", answers) }
        }
        rejected { SafeArtworkPolicy.checkedAddresses("localhost", listOf(public)) }
        val privateV6 = InetAddress.getByAddress(ByteArray(16).apply { this[0] = 0xfc.toByte() })
        rejected { SafeArtworkPolicy.checkedAddresses("images.example.com", listOf(privateV6)) }
    }

    @Test fun checksInitialAndEveryRedirectTargetWithFourHopBudget() {
        val base = "https://images.example.com/a/image.jpg"
        assertEquals("https://images.example.com/next.jpg", SafeArtworkPolicy.redirect(base, "../next.jpg", 3))
        assertEquals("https://other.example.com/image.jpg", SafeArtworkPolicy.redirect(base, "//other.example.com/image.jpg", 0))
        rejected { SafeArtworkPolicy.redirect(base, "/next.jpg", 4) }
        for (location in listOf("http://other.example.com/a", "https://127.0.0.1/a",
            "https://u:p@other.example.com/a", "https://other.example.com:8443/a",
            "https://other.example.com/a%0a", " /next.jpg", "\n/next.jpg")) {
            rejected { SafeArtworkPolicy.redirect(base, location, 0) }
        }
        rejected { SafeArtworkPolicy.redirect(base, null, 0) }
        rejected { SafeArtworkPolicy.checkUrl("http://images.example.com/a") }
    }

    @Test fun acceptsExactlyEightMiBEvenWhenLengthIsUnknown() {
        val bytes = ByteArray(SafeArtworkPolicy.MAX_BYTES.toInt()) { 42 }
        assertArrayEquals(bytes, SafeArtworkPolicy.readBody(ByteArrayInputStream(bytes), -1))
        assertArrayEquals(bytes, SafeArtworkPolicy.readBody(ByteArrayInputStream(bytes), bytes.size.toLong()))
    }

    @Test fun rejectsOversizedAndTruncatedBodiesIncludingUnknownOrLyingLength() {
        val bytes = ByteArray(SafeArtworkPolicy.MAX_BYTES.toInt() + 1)
        rejected { SafeArtworkPolicy.readBody(ByteArrayInputStream(bytes), -1) }
        rejected { SafeArtworkPolicy.readBody(ByteArrayInputStream(bytes), 1) }
        rejected { SafeArtworkPolicy.readBody(ByteArrayInputStream(byteArrayOf(1)), 2) }
        rejected { SafeArtworkPolicy.readBody(ByteArrayInputStream(byteArrayOf()), bytes.size.toLong()) }
    }

    @Test fun rejectsSpecialIpv6RangesAndAcceptsGlobalUnicast() {
        for (literal in listOf("::1", "fe80::1", "2001:db8::1", "2002:7f00:1::", "2001::1")) {
            rejected { SafeArtworkPolicy.checkedAddresses("images.example.com", listOf(InetAddress.getByName(literal))) }
        }
        val global = InetAddress.getByName("2606:4700:4700::1111")
        assertSame(global, SafeArtworkPolicy.checkedAddresses("images.example.com", listOf(global)).single())
    }

    private fun rejected(block: () -> Any?) {
        try { block(); fail("Expected artwork policy rejection") } catch (_: IOException) { }
    }
}
