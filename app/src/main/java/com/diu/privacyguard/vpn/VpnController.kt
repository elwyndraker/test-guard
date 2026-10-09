package com.diu.privacyguard.vpn

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Starts and stops the VPN. The switch on screen follows [isRunning], which only the service sets. */
object VpnController {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    internal fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    /** Call only after VpnService.prepare() returned null or the user accepted Android's popup. */
    fun start(context: Context) {
        context.startForegroundService(Intent(context, PrivacyVpnService::class.java))
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, PrivacyVpnService::class.java).setAction(PrivacyVpnService.ACTION_STOP)
        )
    }
}
