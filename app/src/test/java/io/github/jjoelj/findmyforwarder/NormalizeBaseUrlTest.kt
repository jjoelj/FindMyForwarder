package io.github.jjoelj.findmyforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizeBaseUrlTest {
    @Test
    fun normalizes() {
        assertEquals("https://example.com", normalizeBaseUrl("example.com"))
        assertEquals("https://example.com", normalizeBaseUrl(" https://example.com/ "))
        assertEquals("https://example.com", normalizeBaseUrl("https://example.com/set"))
        assertEquals("https://example.com", normalizeBaseUrl("https://example.com/set/"))
        assertEquals("http://example.com:8080/api", normalizeBaseUrl("http://example.com:8080/api/"))
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("ftp://example.com"))
        assertNull(normalizeBaseUrl("not a url"))
    }

    @Test
    fun splitsScannedPayload() {
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals(
            "https://iphone.tail1234.ts.net" to token,
            parseScannedCredentials("https://iphone.tail1234.ts.net/?token=$token", "https://old.example.com")
        )
        // Bare token: keep whatever base URL is already configured.
        assertEquals(
            "https://old.example.com" to token,
            parseScannedCredentials(token, "https://old.example.com")
        )
        // Non-default port survives; default 443 does not come back.
        assertEquals(
            "http://192.168.1.5:8080" to token,
            parseScannedCredentials("http://192.168.1.5:8080/?token=$token", "")
        )
        assertNull(parseScannedCredentials("https://", ""))
    }
}
