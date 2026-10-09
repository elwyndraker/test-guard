package com.diu.privacyguard.vpn

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Live numbers shown on the Home screen so problems can be reported precisely. */
data class VpnStats(
    val answered: Int = 0,
    val failed: Int = 0,
    val route: String = "",
)

/** Starts and stops the VPN. The switch on screen follows [isRunning], which only the service sets. */
object VpnController {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _stats = MutableStateFlow(VpnStats())
    val stats: StateFlow<VpnStats> = _stats

    /** Why protection last stopped by itself, or null. Cleared when the user turns it on. */
    private val _stopReason = MutableStateFlow<String?>(null)
    val stopReason: StateFlow<String?> = _stopReason

    internal fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    internal fun resetStats() {
        _stats.value = VpnStats()
    }

    // Called from many lookups at once; update {} retries so no count is lost.
    internal fun recordAnswer(route: String) {
        _stats.update { it.copy(answered = it.answered + 1, route = route) }
    }

    internal fun recordFailure() {
        _stats.update { it.copy(failed = it.failed + 1) }
    }

    internal fun setStopReason(reason: String?) {
        _stopReason.value = reason
    }

    /** Call only after VpnService.prepare() returned null or the user accepted Android's popup. */
    fun start(context: Context) {
        _stopReason.value = null
        context.startForegroundService(Intent(context, PrivacyVpnService::class.java))
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, PrivacyVpnService::class.java).setAction(PrivacyVpnService.ACTION_STOP)
        )
    }
}
