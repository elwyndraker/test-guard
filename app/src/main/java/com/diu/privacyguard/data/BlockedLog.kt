package com.diu.privacyguard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** One row in the Blocked list. [count] is how many times the domain was blocked in a burst. */
data class BlockedEntry(val id: Long, val domain: String, val time: Long, val count: Int = 1)

/** Recently blocked lookups, newest first. Kept in memory only, never saved or sent anywhere. */
object BlockedLog {
    private const val MAX_ENTRIES = 500

    // Apps retry a blocked lookup many times; repeats within this window share one row.
    private const val MERGE_WINDOW_MS = 60_000L

    private val _entries = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val entries: StateFlow<List<BlockedEntry>> = _entries

    private val _todayCount = MutableStateFlow(0)
    val todayCount: StateFlow<Int> = _todayCount

    private var nextId = 0L
    private var day = LocalDate.now()

    @Synchronized
    fun add(domain: String, now: Long = System.currentTimeMillis()) {
        val today = LocalDate.now()
        if (today != day) {
            day = today
            _todayCount.value = 0
        }
        _todayCount.value += 1

        val current = _entries.value
        val recent = current.firstOrNull { it.domain == domain && now - it.time < MERGE_WINDOW_MS }
        val entry = recent?.copy(time = now, count = recent.count + 1)
            ?: BlockedEntry(nextId++, domain, now)
        _entries.value = (listOf(entry) + current.filter { it !== recent }).take(MAX_ENTRIES)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }
}
