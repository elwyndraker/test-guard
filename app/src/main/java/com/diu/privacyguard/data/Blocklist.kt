package com.diu.privacyguard.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The set of blocked domains. Loaded from the downloaded copy in filesDir, or from the
 * copy shipped in assets so blocking works on first launch, even offline.
 */
object Blocklist {
    const val SOURCE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val FILE_NAME = "hosts.txt"
    private const val TEMP_NAME = "hosts.tmp"

    // A real list has ~80,000 entries; anything far smaller is a broken download.
    private const val MIN_DOMAINS = 10_000

    private val SPACES = Regex("\\s+")
    private val mutex = Mutex()

    @Volatile
    private var domains: Set<String> = emptySet()

    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size

    /** Checks the name and each parent: a.b.doubleclick.net, b.doubleclick.net, doubleclick.net. */
    fun isBlocked(name: String): Boolean {
        val set = domains
        var d = name
        while (d.isNotEmpty()) {
            if (d in set) return true
            d = d.substringAfter('.', "")
        }
        return false
    }

    /** Loads the list once. Safe to call from both the screen and the VPN service. */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (domains.isNotEmpty()) return@withLock
            val file = File(context.filesDir, FILE_NAME)
            val fromFile = if (file.exists()) {
                try {
                    file.bufferedReader().useLines { parse(it) }
                } catch (e: IOException) {
                    emptySet()
                }
            } else {
                emptySet()
            }
            setDomains(
                if (fromFile.size >= MIN_DOMAINS) fromFile
                else context.assets.open(FILE_NAME).bufferedReader().useLines { parse(it) }
            )
        }
    }

    /** Downloads a fresh list. Throws IOException on failure and keeps the old list. */
    suspend fun update(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tmp = File(context.filesDir, TEMP_NAME)
            val connection = URL(SOURCE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("server answered $code")
                connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }

            val fresh = tmp.bufferedReader().useLines { parse(it) }
            if (fresh.size < MIN_DOMAINS) {
                tmp.delete()
                throw IOException("list looks incomplete (${fresh.size} domains)")
            }
            if (!tmp.renameTo(File(context.filesDir, FILE_NAME))) {
                throw IOException("could not save the list")
            }
            setDomains(fresh)
            Settings.setLastUpdated(context, System.currentTimeMillis())
        }
    }

    /** Lines look like "0.0.0.0 ads.example.com". Skips comments and "0.0.0.0 0.0.0.0". */
    internal fun parse(lines: Sequence<String>): Set<String> {
        val set = HashSet<String>(120_000)
        for (line in lines) {
            val parts = line.substringBefore('#').trim().split(SPACES)
            if (parts.size >= 2 && parts[0] == "0.0.0.0" && parts[1] != "0.0.0.0") {
                set += parts[1].lowercase()
            }
        }
        return set
    }

    internal fun setDomains(newDomains: Set<String>) {
        domains = newDomains
        _size.value = newDomains.size
    }
}
