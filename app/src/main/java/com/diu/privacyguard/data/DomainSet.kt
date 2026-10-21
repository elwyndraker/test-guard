package com.diu.privacyguard.data

import java.io.OutputStream

/**
 * A set of domain names packed into two arrays instead of one String object per domain.
 * About a million domains take ~35 MB this way, against well over 100 MB as a HashSet<String>.
 *
 * Each domain is stored once in [bytes] as a length byte followed by its ASCII characters.
 * [table] is an open-addressing hash table holding (offset in [bytes] + 1), 0 meaning empty.
 */
class DomainSet private constructor(
    private val bytes: ByteArray,
    private val table: IntArray,
    val size: Int,
) {
    /** True if name[start..] is in the set. Letters are compared case-insensitively. */
    fun contains(name: String, start: Int = 0): Boolean {
        if (size == 0) return false
        val length = name.length - start
        if (length <= 0 || length > MAX_LENGTH) return false
        val mask = table.size - 1
        var slot = hash(name, start) and mask
        while (true) {
            val entry = table[slot]
            if (entry == 0) return false
            if (matches(bytes, entry - 1, name, start)) return true
            slot = (slot + 1) and mask
        }
    }

    /** Writes one domain per line. */
    fun writeTo(out: OutputStream) = writeEntries(bytes, bytes.size, out)

    /** Collects domains. Duplicates and names that are too long are ignored. */
    class Builder {
        private var bytes = ByteArray(1 shl 16)
        private var used = 0
        private var table = IntArray(1 shl 12)
        var size = 0
            private set

        /** [domain] must already be lowercase ASCII. Returns false if it was already there. */
        fun add(domain: String): Boolean {
            val length = domain.length
            if (length == 0 || length > MAX_LENGTH) return false
            var mask = table.size - 1
            var slot = hash(domain, 0) and mask
            while (table[slot] != 0) {
                if (matches(bytes, table[slot] - 1, domain, 0)) return false
                slot = (slot + 1) and mask
            }

            if (used + length + 1 > bytes.size) {
                bytes = bytes.copyOf(maxOf(bytes.size * 2, used + length + 1))
            }
            bytes[used] = length.toByte()
            for (i in 0 until length) bytes[used + 1 + i] = domain[i].code.toByte()
            table[slot] = used + 1
            used += length + 1
            size++

            // Keep the table at most half full so lookups stay short.
            if (size * 2 > table.size) {
                val bigger = IntArray(table.size * 2)
                mask = bigger.size - 1
                for (entry in table) {
                    if (entry == 0) continue
                    var s = hashBytes(bytes, entry - 1) and mask
                    while (bigger[s] != 0) s = (s + 1) and mask
                    bigger[s] = entry
                }
                table = bigger
            }
            return true
        }

        fun build(): DomainSet = DomainSet(bytes.copyOf(used), table, size)
    }

    companion object {
        val EMPTY = DomainSet(ByteArray(0), IntArray(1), 0)

        // DNS names are at most 253 characters, so the length fits in one byte.
        private const val MAX_LENGTH = 253

        private fun lower(c: Int) = if (c in 'A'.code..'Z'.code) c + 32 else c

        private fun mix(h: Int): Int {
            var x = h
            x = x xor (x ushr 16)
            x *= 0x45d9f3b
            x = x xor (x ushr 16)
            return x
        }

        // FNV-1a over the characters; both versions must give the same result for the same name.
        private fun hash(name: String, start: Int): Int {
            var h = -0x7ee3623b
            for (i in start until name.length) h = (h xor lower(name[i].code)) * 0x01000193
            return mix(h)
        }

        private fun hashBytes(bytes: ByteArray, offset: Int): Int {
            val length = bytes[offset].toInt() and 0xFF
            var h = -0x7ee3623b
            for (i in 1..length) h = (h xor (bytes[offset + i].toInt() and 0xFF)) * 0x01000193
            return mix(h)
        }

        private fun matches(bytes: ByteArray, offset: Int, name: String, start: Int): Boolean {
            val length = bytes[offset].toInt() and 0xFF
            if (length != name.length - start) return false
            for (i in 0 until length) {
                if ((bytes[offset + 1 + i].toInt() and 0xFF) != lower(name[start + i].code)) return false
            }
            return true
        }

        private fun writeEntries(bytes: ByteArray, used: Int, out: OutputStream) {
            var offset = 0
            while (offset < used) {
                val length = bytes[offset].toInt() and 0xFF
                out.write(bytes, offset + 1, length)
                out.write('\n'.code)
                offset += length + 1
            }
        }
    }
}
