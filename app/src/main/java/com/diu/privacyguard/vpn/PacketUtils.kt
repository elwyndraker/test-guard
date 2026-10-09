package com.diu.privacyguard.vpn

/** A DNS question pulled out of an IPv4/UDP packet from the tunnel. */
class DnsQuery(
    val ipHeaderLength: Int,
    val dns: ByteArray,
    val domain: String,
    val questionEnd: Int,
)

/** Reads IPv4/UDP packets from the tunnel and builds answer packets to write back. */
object PacketUtils {
    private const val IPV4_HEADER = 20
    private const val UDP_HEADER = 8
    private const val PROTOCOL_UDP = 17
    private const val DNS_PORT = 53

    /** Returns the DNS question in this packet, or null if it is not an IPv4 UDP packet to port 53. */
    fun parseDnsQuery(packet: ByteArray): DnsQuery? {
        if (packet.size < IPV4_HEADER + UDP_HEADER) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER || packet[9].toInt() != PROTOCOL_UDP) return null

        // Skip fragments: only whole packets carry a full DNS question.
        val fragment = DnsUtils.readUShort(packet, 6)
        if ((fragment and 0x3FFF) != 0) return null

        val totalLength = minOf(DnsUtils.readUShort(packet, 2), packet.size)
        if (totalLength < ihl + UDP_HEADER + 12) return null
        if (DnsUtils.readUShort(packet, ihl + 2) != DNS_PORT) return null

        val dns = packet.copyOfRange(ihl + UDP_HEADER, totalLength)
        val (domain, questionEnd) = DnsUtils.readQuestion(dns) ?: return null
        return DnsQuery(ihl, dns, domain, questionEnd)
    }

    /**
     * Wraps a DNS answer in a new IPv4 + UDP packet going back to whoever asked:
     * addresses and ports are swapped, lengths set, IPv4 checksum computed.
     * The UDP checksum is left at 0, which IPv4 allows.
     */
    fun buildResponse(query: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val out = ByteArray(IPV4_HEADER + UDP_HEADER + dns.size)
        out[0] = 0x45 // IPv4, 20-byte header
        putShort(out, 2, out.size) // total length
        putShort(out, 6, 0x4000) // don't fragment
        out[8] = 64 // time to live
        out[9] = PROTOCOL_UDP.toByte()
        query.copyInto(out, 12, 16, 20) // new source IP = old destination
        query.copyInto(out, 16, 12, 16) // new destination IP = old source
        putShort(out, 10, ipChecksum(out, IPV4_HEADER))

        query.copyInto(out, 20, ihl + 2, ihl + 4) // source port = 53
        query.copyInto(out, 22, ihl, ihl + 2) // destination port = the asker's port
        putShort(out, 24, UDP_HEADER + dns.size) // UDP length; checksum bytes 26-27 stay 0
        dns.copyInto(out, IPV4_HEADER + UDP_HEADER)
        return out
    }

    internal fun ipChecksum(bytes: ByteArray, length: Int): Int {
        var sum = 0
        for (i in 0 until length step 2) {
            sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun putShort(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value shr 8).toByte()
        bytes[at + 1] = value.toByte()
    }
}
