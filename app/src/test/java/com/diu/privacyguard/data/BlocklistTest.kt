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

    private fun parse(text: String) = Blocklist.parse(text.lineSequence())

    private fun assertDomains(expected: Set<String>, set: DomainSet) {
        assertEquals(expected.size, set.size)
        for (domain in expected) assertTrue(domain, set.contains(domain))
    }

    @Test
    fun parsesOnlyBlockedDomains() {
        assertDomains(setOf("doubleclick.net", "ads.example.com", "tracker.test"), parse(sample))
    }

    @Test
    fun parsesOtherHostsFormats() {
        val text = """
            ${"﻿"}# GoodbyeAds
            127.0.0.1 localhost.localdomain
            127.0.0.1 ads.one.com
            :: ads.two.com
            0.0.0.0 a.three.com b.three.com
            255.255.255.255 broadcasthost
            fe80::1%lo0 localhost
            1.2.3.4 not-a-sink.com
            0.0.0.0 1.2.3.4
        """.trimIndent()
        assertDomains(
            setOf("ads.one.com", "ads.two.com", "a.three.com", "b.three.com"),
            parse(text),
        )
    }

    @Test
    fun parsesDomainAndWildcardLists() {
        val text = """
            # plain and wildcard lists
            plain.example.org
            *.wild.example.org
            *.example.*
            -bad.example.org
            bad-.example.org
            dots..example.org
            nodot
        """.trimIndent()
        assertDomains(setOf("plain.example.org", "wild.example.org"), parse(text))
    }

    @Test
    fun parsesOnlyDomainRulesFromAdblockLists() {
        val text = """
            [Adblock Plus 2.0]
            ! Title: EasyPrivacy
            ||tracker.example.com^
            ||third.example.com^${'$'}third-party
            ||example.com/path^
            @@||allowed.example.com^
            example.com##.ad-banner
            ad-banner.js
            *-ad.sm.cn*
        """.trimIndent()
        assertDomains(setOf("tracker.example.com"), parse(text))
    }

    @Test
    fun blocksSubdomainsButNotParents() {
        Blocklist.setDomains(parse(sample))
        assertTrue(Blocklist.isBlocked("doubleclick.net"))
        assertTrue(Blocklist.isBlocked("stats.g.doubleclick.net"))
        assertTrue(Blocklist.isBlocked("ads.example.com"))
        assertTrue(Blocklist.isBlocked("Stats.DoubleClick.NET"))
        assertFalse(Blocklist.isBlocked("example.com"))
        assertFalse(Blocklist.isBlocked("notdoubleclick.net"))
        assertFalse(Blocklist.isBlocked(""))
    }
}
