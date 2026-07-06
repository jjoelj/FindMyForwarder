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
}
