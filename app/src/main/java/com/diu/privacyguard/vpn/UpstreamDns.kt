package com.diu.privacyguard.vpn

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Sends allowed lookups to the real network's DNS.
 *
 * On Android 10+ the lookup goes through Android's own resolver for the real network
 * (DnsResolver.rawQuery). Android already knows which of the network's DNS servers work,
 * handles IPv6 and Private DNS, and retries; this is exactly how the phone resolves names
 * with the VPN off. On older versions, or if that fails, each of the network's servers is
 * tried with a short timeout (IPv4 first), then Cloudflare 1.1.1.1 as a last resort.
 *
 * Answers must arrive within a few seconds: Chrome and other apps give up on a lookup
 * much sooner than the system resolver does, and a late answer is as bad as none.
 */
class UpstreamDns(private val protect: (DatagramSocket) -> Boolean) {

    companion object {
        private const val SYSTEM_TIMEOUT_MS = 3_000L
        private const val SERVER_TIMEOUT_MS = 1_500
        val FALLBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
    }

    /** The real Wi-Fi or mobile network, or null while there is none. */
    @Volatile
    var network: Network? = null

    /** That network's DNS servers, IPv4 first. */
    @Volatile
    var servers: List<InetAddress> = emptyList()
        set(value) {
            field = value.sortedBy { if (it is Inet4Address) 0 else 1 }
        }

    /** Returns the answer and how it was obtained, or null if every route failed. */
    suspend fun resolve(query: ByteArray): Pair<ByteArray, String>? {
        val net = network
        if (Build.VERSION.SDK_INT >= 29 && net != null) {
            systemResolve(net, query)?.let { return withId(it, query) to "Android resolver" }
        }
        for (server in servers) {
            send(server, query)?.let { return withId(it, query) to server.hostAddress.orEmpty() }
        }
        return send(FALLBACK, query)?.let { withId(it, query) to "1.1.1.1 (fallback)" }
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
                                if (continuation.isActive) continuation.resume(null)
                            }
                        },
                    )
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private fun send(server: InetAddress, query: ByteArray): ByteArray? = try {
        DatagramSocket().use { socket ->
            protect(socket)
            socket.soTimeout = SERVER_TIMEOUT_MS
            socket.send(DatagramPacket(query, query.size, server, 53))
            val buffer = ByteArray(4096)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            buffer.copyOf(reply.length)
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
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
