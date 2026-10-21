package com.diu.privacyguard.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.diu.privacyguard.R
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Google Play's flexible in-app update: when Play has a newer version, a card offers it.
 * Play shows its own update screen, downloads in the background while the app keeps
 * working, and the card then offers a restart to install. Shows nothing when the app
 * wasn't installed from Play (sideloaded APK, emulator) or Play has no newer version.
 */
@Composable
fun AppUpdateBanner() {
    val context = LocalContext.current
    val manager = remember { AppUpdateManagerFactory.create(context.applicationContext) }
    var available by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloaded by remember { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }

    val updateFlow = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // RESULT_OK means the download started; otherwise the user said no, so don't nag again.
        if (result.resultCode != Activity.RESULT_OK) dismissed = true
    }

    DisposableEffect(manager) {
        val listener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) downloaded = true
        }
        manager.registerListener(listener)
        onDispose { manager.unregisterListener(listener) }
    }

    // Check again on every resume: an update may finish downloading while the app is closed.
    LifecycleResumeEffect(manager) {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                downloaded = info.installStatus() == InstallStatus.DOWNLOADED
                val idle = info.installStatus() !in setOf(
                    InstallStatus.PENDING, InstallStatus.DOWNLOADING,
                    InstallStatus.DOWNLOADED, InstallStatus.INSTALLING,
                )
                available = info.takeIf {
                    idle && it.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        it.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                }
            }
            .addOnFailureListener {
                // Not installed from Play, or Play is unavailable: no banner.
                available = null
            }
        onPauseOrDispose {}
    }

    val info = available
    when {
        downloaded -> UpdateCard(
            text = stringResource(R.string.app_update_downloaded),
            action = stringResource(R.string.app_update_restart),
            onAction = { manager.completeUpdate() },
        )
        info != null && !dismissed -> UpdateCard(
            text = stringResource(R.string.app_update_available),
            action = stringResource(R.string.app_update_now),
            onAction = {
                available = null // an AppUpdateInfo can start the flow only once
                manager.startUpdateFlowForResult(
                    info, updateFlow, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE)
                )
            },
            onLater = { dismissed = true },
        )
    }
}

@Composable
private fun UpdateCard(text: String, action: String, onAction: () -> Unit, onLater: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (onLater != null) {
                    TextButton(onClick = onLater) { Text(stringResource(R.string.app_update_later)) }
                }
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}
