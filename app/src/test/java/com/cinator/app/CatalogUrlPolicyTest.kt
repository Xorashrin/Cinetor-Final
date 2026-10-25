package com.cinator.app

import com.cinator.app.data.UrlPolicyValidator
import org.junit.Assert.*
import org.junit.Test

class CatalogUrlPolicyTest {
    @Test fun rejectsNonPublicOrAmbiguousNetworkTargetsWithoutDns() {
        val unsafe = listOf(
            "http://example.com/video", "https://user:pass@example.com/video",
            "https://example.com:8443/video", "https://localhost/", "https://localhost./",
            "https://sub.localhost/", "https://router.local/", "https://metadata.google.internal/",
            "https://0.0.0.0/", "https://10.1.2.3/", "https://127.0.0.1/",
            "https://169.254.169.254/", "https://172.16.0.1/", "https://172.31.255.255/",
            "https://192.168.1.1/", "https://100.64.0.1/", "https://198.19.1.1/",
            "https://224.0.0.1/", "https://255.255.255.255/", "https://2130706433/",
            "https://127.1/", "https://0177.0.0.1/", "https://0x7f000001/",
            "https://[::1]/", "https://[::]/", "https://[fc00::1]/", "https://[fd12::1]/",
            "https://[fe80::1]/", "https://[fec0::1]/", "https://[ff02::1]/",
            "https://[::ffff:127.0.0.1]/", "https://[::ffff:7f00:1]/",
            "https://[fe80::1%25eth0]/", "https://[2002:7f00:1::]/",
            "https://example.com/video\n", "\thttps://example.com/video",
            "https://example.com/a%0D%0Aheader", "https://example.com/a%00b", "https://example.com/a%7fb"
        )
        val accepted = unsafe.filter(UrlPolicyValidator::isSafeHttpsUrl)
        assertEquals("Unsafe URLs must all be rejected", emptyList<String>(), accepted)
        listOf("https://example.com/path?q=catalog", "https://example.com:443/",
            "https://8.8.8.8/", "https://[2606:4700:4700::1111]/").forEach {
            assertTrue(it, UrlPolicyValidator.isSafeHttpsUrl(it))
        }
        assertTrue(UrlPolicyValidator.isDeclaredBdixEndpoint("https://movies.discoveryftp.net"))
        assertFalse(UrlPolicyValidator.isDeclaredBdixEndpoint("https://movies.discoveryftp.net.evil.com"))
    }
}
