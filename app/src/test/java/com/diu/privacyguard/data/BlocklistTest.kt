package com.diu.privacyguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {

    private val sample = """
        # Title: StevenBlack/hosts
        127.0.0.1 localhost
        0.0.0.0 0.0.0.0
        0.0.0.0 doubleclick.net
        0.0.0.0	ads.Example.com   # inline comment
          0.0.0.0 tracker.test
        ::1 localhost
    """.trimIndent()

    @Test
    fun parsesOnlyBlockedDomains() {
        val domains = Blocklist.parse(sample.lineSequence())
        assertEquals(setOf("doubleclick.net", "ads.example.com", "tracker.test"), domains)
    }

    @Test
    fun blocksSubdomainsButNotParents() {
        Blocklist.setDomains(Blocklist.parse(sample.lineSequence()))
        assertTrue(Blocklist.isBlocked("doubleclick.net"))
        assertTrue(Blocklist.isBlocked("stats.g.doubleclick.net"))
        assertTrue(Blocklist.isBlocked("ads.example.com"))
        assertFalse(Blocklist.isBlocked("example.com"))
        assertFalse(Blocklist.isBlocked("notdoubleclick.net"))
        assertFalse(Blocklist.isBlocked(""))
    }
}
