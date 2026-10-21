package com.diu.privacyguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DomainSetTest {

    @Test
    fun keepsEveryDomainOnceAcrossTableGrowth() {
        val builder = DomainSet.Builder()
        for (i in 0 until 50_000) assertTrue(builder.add("host$i.example.com"))
        assertFalse(builder.add("host123.example.com"))
        val set = builder.build()

        assertEquals(50_000, set.size)
        for (i in 0 until 50_000) assertTrue(set.contains("host$i.example.com"))
        assertFalse(set.contains("host50000.example.com"))
        assertFalse(set.contains("example.com"))
    }

    @Test
    fun checksSuffixFromStartIndex() {
        val set = DomainSet.Builder().apply { add("doubleclick.net") }.build()
        assertTrue(set.contains("stats.g.doubleclick.net", 8))
        assertTrue(set.contains("DoubleClick.Net"))
        assertFalse(set.contains("stats.g.doubleclick.net", 7))
        assertFalse(set.contains("doubleclick.net", 15))
        assertFalse(DomainSet.EMPTY.contains("doubleclick.net"))
    }

    @Test
    fun writesOneDomainPerLine() {
        val set = DomainSet.Builder().apply {
            add("a.com")
            add("b.org")
            add("a.com")
        }.build()
        val out = ByteArrayOutputStream()
        set.writeTo(out)
        assertEquals("a.com\nb.org\n", out.toString(Charsets.US_ASCII.name()))
    }
}
