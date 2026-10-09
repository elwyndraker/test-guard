package com.diu.privacyguard.vpn

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.resume

/**
 * Sends allowed lookups to the real network's DNS, as a staggered race (see [firstSuccess]):
 *
 * 1. Android's own resolver for the real network (DnsResolver.rawQuery, Android 10+). It knows
 *    which of the network's servers work and handles IPv6 and Private DNS, exactly as the phone
 *    does with the VPN off.
 * 2. After 0.6 s, the network's DNS servers directly, over that network.
 * 3. After 1.2 s, public fallbacks (Cloudflare 1.1.1.1, Google 8.8.8.8).
 *
 * If a route fails, the later ones start at once. The network is read fresh for every lookup,
 * so a Wi-Fi/mobile data switch can't leave us asking a network that is gone.
 *
 * Answers must be fast: apps give up on a lookup within a few seconds.
 */
class UpstreamDns(
    private val connectivity: ConnectivityManager,
    private val scope: CoroutineScope,
    private val protect: (DatagramSocket) -> Boolean,
) {
    companion object {
        private const val SERVERS_AFTER_MS = 600L
        private const val FALLBACK_AFTER_MS = 1_200L
        private const val TOTAL_TIMEOUT_MS = 4_000L
        private const val SYSTEM_TIMEOUT_MS = 3_500L
        private const val SOCKET_TIMEOUT_MS = 2_000
        private val FALLBACKS = listOf(
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
            InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
        )
    }

    /** Last network the callback reported; used only if Android reports no active network. */
    @Volatile
    var lastKnownNetwork: Network? = null

    /** The most recent failure on any route, for the diagnostics line. */
    @Volatile
    var lastError: String = ""
        private set

    /** Returns the answer and the route that produced it, or null if every route failed. */
    suspend fun resolve(query: ByteArray): Outcome<ByteArray>? {
        val network = connectivity.activeNetwork ?: lastKnownNetwork
        val servers = network?.let { connectivity.getLinkProperties(it)?.dnsServers }.orEmpty()
            .sortedBy { if (it is Inet4Address) 0 else 1 }

        val attempts = buildList {
            if (Build.VERSION.SDK_INT >= 29 && network != null) {
                add(Attempt(0, "Android resolver") { systemResolve(network, query) })
            }
            for (server in servers) {
                val start = if (isEmpty()) 0 else SERVERS_AFTER_MS
                add(Attempt(start, server.hostAddress.orEmpty()) { send(server, query, network) })
            }
            for (server in FALLBACKS) {
                val start = if (isEmpty()) 0 else FALLBACK_AFTER_MS
                add(Attempt(start, "${server.hostAddress} (fallback)") { send(server, query, null) })
            }
        }
        val outcome = firstSuccess(scope, attempts, TOTAL_TIMEOUT_MS)
        if (outcome == null && lastError.isEmpty()) lastError = "no route answered within 4 s"
        return outcome?.let { Outcome(withId(it.value, query), it.label) }
    }

    /** "Wi-Fi", "Mobile data" or "none", for diagnostics. */
    fun networkType(): String {
        val network = connectivity.activeNetwork ?: return "none"
        val caps = connectivity.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
    }

    /** DNS servers and Private DNS state of the current network, for diagnostics. */
    fun networkDnsSummary(): String {
        val network = connectivity.activeNetwork ?: return "no network"
        val lp = connectivity.getLinkProperties(network) ?: return "no link info"
        val servers = lp.dnsServers.joinToString { it.hostAddress.orEmpty() }.ifEmpty { "none" }
        val privateDns = when {
            Build.VERSION.SDK_INT < 28 -> "n/a"
            lp.privateDnsServerName != null -> "on (${lp.privateDnsServerName})"
            lp.isPrivateDnsActive -> "automatic, active"
            else -> "off or not in use"
        }
        return "servers: $servers; Private DNS: $privateDns"
    }

    // getInstance() is deprecated from Android 17 in favor of a new constructor, but it is the
    // only option on Android 10-16 and still works on 17.
    @Suppress("DEPRECATION")
    @RequiresApi(29)
    private suspend fun systemResolve(network: Network, query: ByteArray): ByteArray? =
        withTimeoutOrNull(SYSTEM_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                try {
                    DnsResolver.getInstance().rawQuery(
                        network, query, DnsResolver.FLAG_EMPTY, Runnable::run, cancel,
                        object : DnsResolver.Callback<ByteArray> {
                            override fun onAnswer(answer: ByteArray, rcode: Int) {
                                if (continuation.isActive) continuation.resume(answer)
                            }

                            override fun onError(error: DnsResolver.DnsException) {
                                lastError = "Android resolver: error ${error.code} ${error.cause?.message.orEmpty()}".trim()
                                if (continuation.isActive) continuation.resume(null)
                            }
                        },
                    )
                } catch (e: Exception) {
                    lastError = "Android resolver: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } ?: run {
            if (lastError.isEmpty()) lastError = "Android resolver: no answer in 3.5 s"
            null
        }

    /** Plain UDP lookup. [network] pins the socket to that network; null uses the default. */
    private fun send(server: InetAddress, query: ByteArray, network: Network?): ByteArray? = try {
        DatagramSocket().use { socket ->
            protect(socket)
            network?.bindSocket(socket)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.send(DatagramPacket(query, query.size, server, 53))
            val buffer = ByteArray(4096)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            buffer.copyOf(reply.length)
        }
    } catch (e: SocketTimeoutException) {
        lastError = "${server.hostAddress}: no answer in 2 s"
        null
    } catch (e: IOException) {
        lastError = "${server.hostAddress}: ${e.message.orEmpty()}"
        null
    } catch (e: SecurityException) {
        lastError = "${server.hostAddress}: not allowed (${e.message.orEmpty()})"
        null
    }

    /** The answer must carry the asker's query id, or the asking app ignores it. */
    private fun withId(answer: ByteArray, query: ByteArray): ByteArray {
        if (answer.size >= 2) {
            answer[0] = query[0]
            answer[1] = query[1]
        }
        return answer
    }
}
