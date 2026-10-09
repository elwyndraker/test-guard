package com.diu.privacyguard.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diu.privacyguard.R
import com.diu.privacyguard.data.BlockedLog
import com.diu.privacyguard.data.Settings
import com.diu.privacyguard.vpn.VpnController
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by VpnController.isRunning.collectAsStateWithLifecycle()
    val blockedToday by BlockedLog.todayCount.collectAsStateWithLifecycle()
    val disclosureAccepted by Settings.disclosureAccepted(context)
        .collectAsStateWithLifecycle(initialValue = false)
    var showDisclosure by remember { mutableStateOf(false) }

    // Android's own "Connection request" popup. RESULT_OK means the user allowed the VPN.
    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) VpnController.start(context)
    }

    fun askForVpn() {
        val intent = VpnService.prepare(context) // null = already allowed
        if (intent == null) {
            VpnController.start(context)
        } else {
            try {
                vpnPermission.launch(intent)
            } catch (e: ActivityNotFoundException) {
                // Some modified Android builds have no VPN popup; nothing we can do.
            }
        }
    }

    // On Android 13+, ask once for the notification permission. The VPN works either way.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { askForVpn() }

    fun turnOn() {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askForVpn()
        }
    }

    fun onSwitchChange(on: Boolean) {
        when {
            !on -> VpnController.stop(context)
            disclosureAccepted -> turnOn()
            else -> showDisclosure = true
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text(stringResource(R.string.disclosure_title)) },
            text = { Text(stringResource(R.string.disclosure_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    scope.launch { Settings.setDisclosureAccepted(context) }
                    turnOn()
                }) { Text(stringResource(R.string.disclosure_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) {
                    Text(stringResource(R.string.disclosure_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            painter = painterResource(R.drawable.ic_shield),
            contentDescription = null,
            tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(96.dp),
        )
        Text(
            text = stringResource(if (running) R.string.status_protected else R.string.status_not_protected),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(if (running) R.string.status_on_detail else R.string.status_off_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val switchLabel = stringResource(R.string.protection_switch)
        Switch(
            checked = running,
            onCheckedChange = ::onSwitchChange,
            modifier = Modifier
                .padding(vertical = 16.dp)
                .scale(1.8f)
                .semantics { contentDescription = switchLabel },
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = blockedToday.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.blocked_today),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.private_dns_tip_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.private_dns_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        context.startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
                    }
                }) { Text(stringResource(R.string.open_settings)) }
            }
        }
    }
}
