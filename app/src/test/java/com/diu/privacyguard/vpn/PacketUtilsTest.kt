package com.diu.privacyguard.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketUtilsTest {

    /** A DNS "A" question for [name] with id 0x1234 and recursion desired. */
    private fun dnsQuery(name: String): ByteArray {
        val header = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)
        val qname = name.split('.').flatMap { label ->
            listOf(label.length.toByte()) + label.toByteArray().toList()
        } + listOf<Byte>(0)
        return header + qname.toByteArray() + byteArrayOf(0, 1, 0, 1)
    }

    /** IPv4 + UDP packet from 10.111.222.1:40000 to 10.111.222.2:53 carrying [dns]. */
    private fun ipPacket(dns: ByteArray, protocol: Int = 17, dstPort: Int = 53): ByteArray {
        val total = 20 + 8 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[8] = 64; p[9] = protocol.toByte()
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        p[20] = (40000 shr 8).toByte(); p[21] = 40000.toByte()
        p[22] = (dstPort shr 8).toByte(); p[23] = dstPort.toByte()
        p[24] = ((8 + dns.size) shr 8).toByte(); p[25] = (8 + dns.size).toByte()
        dns.copyInto(p, 28)
        return p
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    @Test
    fun readsDomainFromQuery() {
        val query = PacketUtils.parseDnsQuery(ipPacket(dnsQuery("Ads.Example.com")))!!
        assertEquals("ads.example.com", query.domain)
        assertEquals(20, query.ipHeaderLength)
        assertEquals(query.dns.size, query.questionEnd)
    }

    @Test
    fun ignoresNonDnsPackets() {
        assertNull(PacketUtils.parseDnsQuery(ipPacket(dnsQuery("a.com"), protocol = 6)))
        assertNull(PacketUtils.parseDnsQuery(ipPacket(dnsQuery("a.com"), dstPort = 853)))
        assertNull(PacketUtils.parseDnsQuery(ByteArray(10)))
    }

    @Test
    fun responseSwapsAddressesAndPorts() {
        val packet = ipPacket(dnsQuery("doubleclick.net"))
        val query = PacketUtils.parseDnsQuery(packet)!!
        val answer = DnsUtils.nxdomain(query.dns, query.questionEnd)
        val response = PacketUtils.buildResponse(packet, query.ipHeaderLength, answer)

        assertEquals(response.size, u16(response, 2))
        assertArrayEquals(packet.copyOfRange(16, 20), response.copyOfRange(12, 16))
        assertArrayEquals(packet.copyOfRange(12, 16), response.copyOfRange(16, 20))
        assertEquals(53, u16(response, 20))
        assertEquals(40000, u16(response, 22))
        assertEquals(8 + answer.size, u16(response, 24))
        // A correct IPv4 header checksums to zero when the checksum field is included.
        assertEquals(0, PacketUtils.ipChecksum(response, 20))
        assertArrayEquals(answer, response.copyOfRange(28, response.size))
    }

    @Test
    fun nxdomainKeepsIdAndQuestion() {
        val dns = dnsQuery("tracker.example.org")
        val (_, end) = DnsUtils.readQuestion(dns)!!
        val reply = DnsUtils.nxdomain(dns, end)

        assertEquals(0x1234, u16(reply, 0))
        assertEquals(0x81, reply[2].toInt() and 0xFF) // answer + recursion desired
        assertEquals(0x83, reply[3].toInt() and 0xFF) // recursion available + NXDOMAIN
        assertEquals(1, u16(reply, 4))
        assertEquals(0, u16(reply, 6))
        assertArrayEquals(dns.copyOfRange(12, end), reply.copyOfRange(12, reply.size))
    }

    @Test
    fun rejectsResponsesAndBrokenNames() {
        val response = dnsQuery("a.com").also { it[2] = 0x81.toByte() }
        assertNull(DnsUtils.readQuestion(response))
        val truncated = dnsQuery("example.com").copyOf(16)
        assertNull(DnsUtils.readQuestion(truncated))
    }
}
