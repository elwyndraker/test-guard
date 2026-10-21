package com.diu.privacyguard.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
 * The set of blocked domains, merged from several public lists. Loaded from the downloaded
 * copy in filesDir, or from the copy shipped in assets so blocking works on first launch,
 * even offline.
 */
object Blocklist {
    /** Downloaded and merged by [update]. Hosts files, plain domain lists and adblock lists all work. */
    val SOURCES = listOf(
        "https://raw.githubusercontent.com/jdlingyu/ad-wars/master/hosts",
        "https://raw.githubusercontent.com/r-a-y/mobile-hosts/master/AdguardMobileSpyware.txt",
        "https://raw.githubusercontent.com/FadeMind/hosts.extras/master/add.2o7Net/hosts",
        "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-domains.txt",
        "https://raw.githubusercontent.com/AdroitAdorKhan/antipopads-re/master/formats/domains.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.txt",
        "https://easylist-downloads.adblockplus.org/easyprivacy.txt",
        "https://hblock.molinero.dev/hosts_domains.txt",
        "https://raw.githubusercontent.com/rentianyu/Ad-set-hosts/master/hosts",
        "https://raw.githubusercontent.com/TG-Twilight/AWAvenue-Ads-Rule/main/AWAvenue-Ads-Rule.txt",
        "https://raw.githubusercontent.com/jerryn70/GoodbyeAds/master/Hosts/GoodbyeAds.txt",
        "https://www.github.developerdan.com/hosts/lists/ads-and-tracking-extended.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://gitlab.com/quidsup/notrack-blocklists/-/raw/master/trackers.list",
        "https://raw.githubusercontent.com/notracking/hosts-blocklists/master/hostnames.txt",
        "https://hosts.flossboxin.org.in/files/hosts",
        "https://raw.githubusercontent.com/bongochong/CombinedPrivacyBlockLists/master/NoFormatting/cpbl-wildcard-blacklist.txt",
        "https://easylist-downloads.adblockplus.org/indianlist.txt",
    )

    private const val FILE_NAME = "hosts.txt"
    private const val TEMP_NAME = "hosts.tmp"

    // The merged list has about a million entries; anything far smaller is a broken download.
    private const val MIN_DOMAINS = 10_000

    private val SPACES = Regex("\\s+")
    private val SINK_ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0")
    private val LOCAL_NAMES = setOf("localhost", "localhost.localdomain", "local", "broadcasthost")
    private val mutex = Mutex()

    @Volatile
    private var domains: DomainSet = DomainSet.EMPTY

    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size

    /** Checks the name and each parent: a.b.doubleclick.net, b.doubleclick.net, doubleclick.net. */
    fun isBlocked(name: String): Boolean {
        val set = domains
        var start = 0
        while (start < name.length) {
            if (set.contains(name, start)) return true
            val dot = name.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
        }
        return false
    }

    /** Loads the list once. Safe to call from both the screen and the VPN service. */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        // Don't wait behind a running update when a list is already in memory.
        if (domains.size > 0) return@withContext
        mutex.withLock {
            if (domains.size > 0) return@withLock
            val file = File(context.filesDir, FILE_NAME)
            val fromFile = if (file.exists()) {
                try {
                    file.bufferedReader().useLines { parse(it) }
                } catch (e: IOException) {
                    DomainSet.EMPTY
                }
            } else {
                DomainSet.EMPTY
            }
            setDomains(
                if (fromFile.size >= MIN_DOMAINS) fromFile
                else context.assets.open(FILE_NAME).bufferedReader().useLines { parse(it) }
            )
        }
    }

    /**
     * Downloads all [SOURCES] and merges them. Sources that fail are skipped; returns how many
     * failed. Throws IOException if the merged list looks broken, and then keeps the old list.
     */
    suspend fun update(context: Context): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val builder = DomainSet.Builder()
            var failed = 0
            var lastError: IOException? = null
            for (url in SOURCES) {
                ensureActive()
                try {
                    download(url, builder)
                } catch (e: IOException) {
                    failed++
                    lastError = e
                }
            }
            if (builder.size < MIN_DOMAINS) {
                throw lastError ?: IOException("list looks incomplete (${builder.size} domains)")
            }

            val fresh = builder.build()
            val tmp = File(context.filesDir, TEMP_NAME)
            tmp.outputStream().buffered().use { fresh.writeTo(it) }
            if (!tmp.renameTo(File(context.filesDir, FILE_NAME))) {
                tmp.delete()
                throw IOException("could not save the list")
            }
            setDomains(fresh)
            Settings.setLastUpdated(context, System.currentTimeMillis(), SOURCES.hashCode())
            failed
        }
    }

    private fun download(url: String, into: DomainSet.Builder) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "PrivacyGuard")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("${connection.url.host} answered $code")
            connection.inputStream.bufferedReader().useLines { parse(it, into) }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(lines: Sequence<String>): DomainSet =
        DomainSet.Builder().also { parse(lines, it) }.build()

    /**
     * Understands the common list formats, one rule per line:
     * hosts files ("0.0.0.0 ads.example.com", also 127.0.0.1 and ::), plain domains
     * ("ads.example.com"), wildcards ("*.ads.example.com") and adblock domain rules
     * ("||ads.example.com^"). Other adblock rules (paths, options, element hiding, exceptions)
     * can't be applied to DNS and are skipped, as are comments, IP addresses and localhost.
     */
    internal fun parse(lines: Sequence<String>, into: DomainSet.Builder) {
        // In an adblock list a bare word like "ad-banner.js" is a URL pattern, not a domain.
        var adblock = false
        for (raw in lines) {
            val line = raw.removePrefix("\uFEFF").trim()
            if (line.startsWith("[Adblock") || line.startsWith("||")) adblock = true
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
            if ("##" in line || "#@#" in line || "#?#" in line || "#$#" in line) continue
            val parts = line.substringBefore('#').trim().split(SPACES)
            if (parts.size >= 2) {
                if (parts[0] !in SINK_ADDRESSES) continue
                for (i in 1 until parts.size) addIfDomain(parts[i], into)
            } else {
                val rule = parts[0]
                when {
                    rule.startsWith("||") ->
                        if (rule.endsWith("^")) addIfDomain(rule.substring(2, rule.length - 1), into)
                    adblock -> {}
                    rule.startsWith("*.") ->addIfDomain(rule.substring(2), into)
                    else -> addIfDomain(rule, into)
                }
            }
        }
    }

    private fun addIfDomain(candidate: String, into: DomainSet.Builder) {
        val name = candidate.lowercase()
        if (name in LOCAL_NAMES || !isDomain(name)) return
        into.add(name)
    }

    /**
     * Letters, digits, '-' and '_' in at least two dot-separated labels, no label starting or
     * ending with '-', and not an IP address.
     */
    internal fun isDomain(name: String): Boolean {
        if (name.length > 253) return false
        var labels = 1
        var letters = false
        var labelStart = 0
        for (i in 0..name.length) {
            if (i == name.length || name[i] == '.') {
                if (i == labelStart || name[labelStart] == '-' || name[i - 1] == '-') return false
                if (i < name.length) labels++
                labelStart = i + 1
                continue
            }
            when (name[i]) {
                in 'a'..'z', '-', '_' -> letters = true
                in '0'..'9' -> {}
                else -> return false
            }
        }
        return labels >= 2 && letters
    }

    internal fun setDomains(newDomains: DomainSet) {
        domains = newDomains
        _size.value = newDomains.size
    }
}
