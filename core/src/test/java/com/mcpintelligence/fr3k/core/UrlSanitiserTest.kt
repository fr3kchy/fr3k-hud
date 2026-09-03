package com.mcpintelligence.fr3k.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSanitiserTest {

    private val s = UrlSanitiser()

    @Test fun stripsUtmParams() {
        val out = s.clean("https://example.com/article?utm_source=tw&id=42&utm_campaign=foo")
        assertEquals("https://example.com/article?id=42", out.clean)
        assertTrue(out.removed.containsAll(listOf("utm_source", "utm_campaign")))
    }

    @Test fun stripsFbclidAndGclid() {
        val out = s.clean("https://x.com/foo?fbclid=abc&gclid=def&q=ok")
        assertEquals("https://x.com/foo?q=ok", out.clean)
        assertTrue(out.removed.contains("fbclid"))
        assertTrue(out.removed.contains("gclid"))
    }

    @Test fun preservesYoutubeVParam() {
        val out = s.clean("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share&si=abc")
        assertTrue("must preserve v", out.clean.contains("v=dQw4w9WgXcQ"))
        assertTrue(out.removed.contains("feature"))
        assertTrue(out.removed.contains("si"))
    }

    @Test fun returnsInputWhenNotParseable() {
        val out = s.clean("not a url")
        assertEquals("not a url", out.clean)
    }

    @Test fun handlesEmptyQuery() {
        val out = s.clean("https://example.com/page")
        assertEquals("https://example.com/page", out.clean)
        assertTrue(out.removed.isEmpty())
    }
}