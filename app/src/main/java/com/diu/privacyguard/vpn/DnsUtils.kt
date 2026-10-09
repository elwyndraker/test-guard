package com.diu.privacyguard.vpn

/** Reads DNS questions and builds "does not exist" answers. No library needed. */
object DnsUtils {
    private const val HEADER_SIZE = 12

    /**
     * Reads the first question's name, e.g. "ads.example.com".
     * Returns the name and the index where the question ends, or null if the message is not a
     * normal query. Names are stored as length-prefixed labels: [3]www[7]example[3]com[0].
     */
    fun readQuestion(dns: ByteArray): Pair<String, Int>? {
        if (dns.size < HEADER_SIZE + 5) return null
        val isResponse = (dns[2].toInt() and 0x80) != 0
        val questionCount = readUShort(dns, 4)
        if (isResponse || questionCount < 1) return null

        val labels = mutableListOf<String>()
        var i = HEADER_SIZE
        while (i < dns.size) {
            val length = dns[i].toInt() and 0xFF
            if (length == 0) {
                val end = i + 1 + 4 // zero byte + type (2) + class (2)
                if (end > dns.size) return null
                return labels.joinToString(".").lowercase() to end
            }
            // 0xC0 marks a compression pointer, which never appears in a normal question.
            if (length > 63 || i + 1 + length > dns.size) return null
            labels += String(dns, i + 1, length, Charsets.US_ASCII)
            i += 1 + length
        }
        return null
    }

    /** Builds an NXDOMAIN answer: same id and question, flags say "this name does not exist". */
    fun nxdomain(dns: ByteArray, questionEnd: Int): ByteArray {
        val reply = dns.copyOf(questionEnd) // header + question only
        reply[2] = ((dns[2].toInt() or 0x80) and 0xF9).toByte() // QR = answer, clear AA/TC, keep RD
        reply[3] = 0x83.toByte() // RA = 1, RCODE = 3 (NXDOMAIN)
        reply[4] = 0 // exactly one question
        reply[5] = 1
        for (i in 6..11) reply[i] = 0 // no answer, authority or additional records
        return reply
    }

    internal fun readUShort(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}
