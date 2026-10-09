package com.diu.privacyguard.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.diu.privacyguard.MainActivity
import com.diu.privacyguard.R
import com.diu.privacyguard.data.BlockedLog
import com.diu.privacyguard.data.Blocklist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local VPN that receives only DNS lookups. Android sends lookups to a fake DNS address that is
 * routed into the tunnel; everything else goes straight to the internet and never reaches us.
 */
class PrivacyVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.diu.privacyguard.action.STOP"
        private const val TAG = "PrivacyVpn"
        private const val CHANNEL_ID = "protection"
        private const val NOTIFICATION_ID = 1
        private const val TUNNEL_ADDRESS = "10.111.222.1"
        private const val FAKE_DNS = "10.111.222.2"
        private const val DNS_TIMEOUT_MS = 5_000
        private val FALLBACK_DNS: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
    }

    /** One open tunnel. Its own flag means an old loop can never keep running on a new tunnel. */
    private class Tunnel(val fd: ParcelFileDescriptor) {
        val active = AtomicBoolean(true)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tunnel: Tunnel? = null
    private var starting = false

    // Bumped on every start and stop, so a slow start that finishes after a stop is thrown away.
    private var generation = 0
    private var callbackRegistered = false

    // The real DNS server of the current Wi-Fi or mobile network. Our app is excluded from the
    // VPN, so this callback always sees the real network.
    @Volatile
    private var upstream: InetAddress = FALLBACK_DNS

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            linkProperties.dnsServers.firstOrNull()?.let { upstream = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        // Anything else, including a null intent from Android's Always-on VPN, means "start".
        startInForeground()
        if (tunnel == null && !starting) startTunnel()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user turned us off in Android settings.
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PrivacyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.turn_off), stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTunnel() {
        starting = true
        val myGeneration = ++generation
        VpnController.setRunning(true)
        scope.launch {
            // Load the list here too: Android can start this service without the app's screen.
            Blocklist.load(applicationContext)

            val connectivity = getSystemService(ConnectivityManager::class.java)
            upstream = connectivity.getLinkProperties(connectivity.activeNetwork)
                ?.dnsServers?.firstOrNull() ?: FALLBACK_DNS

            val fd = try {
                Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(TUNNEL_ADDRESS, 24)
                    .addDnsServer(FAKE_DNS)
                    .addRoute(FAKE_DNS, 32)
                    .addDisallowedApplication(packageName) // our own downloads skip the tunnel
                    .setBlocking(false)
                    .also { if (Build.VERSION.SDK_INT >= 29) it.setMetered(false) }
                    .establish()
            } catch (e: Exception) {
                Log.e(TAG, "Could not open the tunnel", e)
                null
            }

            mainHandler.post {
                if (myGeneration != generation) {
                    // The user turned protection off while we were starting.
                    fd?.close()
                    return@post
                }
                starting = false
                if (fd == null) {
                    // Not allowed, e.g. the VPN permission was revoked.
                    shutdown()
                    return@post
                }
                val newTunnel = Tunnel(fd)
                tunnel = newTunnel
                connectivity.registerDefaultNetworkCallback(networkCallback)
                callbackRegistered = true
                thread(name = "PrivacyGuard-tunnel") { runLoop(newTunnel) }
                Log.i(TAG, "Tunnel open, upstream DNS $upstream, ${Blocklist.size.value} domains")
            }
        }
    }

    /**
     * Reads packets from the tunnel. The tunnel is non-blocking, so we wait with poll() and a
     * 1-second timeout instead of spinning; the timeout also lets the loop notice a stop request.
     */
    private fun runLoop(tunnel: Tunnel) {
        val input = FileInputStream(tunnel.fd.fileDescriptor)
        val output = FileOutputStream(tunnel.fd.fileDescriptor)
        val buffer = ByteArray(32767)
        val pollFd = StructPollfd().apply {
            fd = tunnel.fd.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        try {
            while (tunnel.active.get()) {
                pollFd.revents = 0
                val ready = try {
                    Os.poll(arrayOf(pollFd), 1000)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (ready <= 0) continue
                val length = input.read(buffer)
                if (length > 0) handlePacket(buffer.copyOf(length), output)
            }
        } catch (e: Exception) {
            if (tunnel.active.get()) {
                Log.e(TAG, "Tunnel loop stopped", e)
                mainHandler.post { shutdown() }
            }
        } finally {
            try {
                tunnel.fd.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        val query = PacketUtils.parseDnsQuery(packet) ?: return
        if (Blocklist.isBlocked(query.domain)) {
            BlockedLog.add(query.domain)
            val answer = DnsUtils.nxdomain(query.dns, query.questionEnd)
            write(output, PacketUtils.buildResponse(packet, query.ipHeaderLength, answer))
        } else {
            // Each lookup gets its own coroutine so one slow site doesn't hold up the others.
            scope.launch {
                val server = upstream
                val answer = forward(server, query.dns)
                    ?: if (server != FALLBACK_DNS) forward(FALLBACK_DNS, query.dns) else null
                if (answer != null) {
                    write(output, PacketUtils.buildResponse(packet, query.ipHeaderLength, answer))
                }
            }
        }
    }

    private fun forward(server: InetAddress, dns: ByteArray): ByteArray? = try {
        DatagramSocket().use { socket ->
            protect(socket)
            socket.soTimeout = DNS_TIMEOUT_MS
            socket.send(DatagramPacket(dns, dns.size, server, 53))
            val buffer = ByteArray(4096)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            buffer.copyOf(reply.length)
        }
    } catch (e: IOException) {
        null
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        try {
            synchronized(output) { output.write(packet) }
        } catch (e: IOException) {
            // The tunnel was closed while this answer was on its way; nothing to do.
        }
    }

    /** Stops everything. Safe to call more than once. Must run on the main thread. */
    private fun shutdown() {
        generation++
        VpnController.setRunning(false)
        tunnel?.active?.set(false) // the loop closes the tunnel within a second
        tunnel = null
        if (callbackRegistered) {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            } catch (_: IllegalArgumentException) {
            }
            callbackRegistered = false
        }
        scope.coroutineContext.cancelChildren()
        starting = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
