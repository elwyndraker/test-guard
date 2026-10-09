package com.diu.privacyguard.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diu.privacyguard.BuildConfigInfo
import com.diu.privacyguard.R
import com.diu.privacyguard.data.Blocklist
import com.diu.privacyguard.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Date

@Composable
fun FiltersScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val domainCount by Blocklist.size.collectAsStateWithLifecycle()
    val lastUpdated by Settings.lastUpdated(context).collectAsStateWithLifecycle(initialValue = 0L)
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }

    val doneText = stringResource(R.string.update_done)
    val failedText = stringResource(R.string.update_failed)

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.about_text))
                    Text(
                        stringResource(R.string.version, BuildConfigInfo.versionName(context)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = NumberFormat.getIntegerInstance().format(domainCount),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.domains_in_list))
                Spacer(Modifier.size(12.dp))
                Text(
                    text = if (lastUpdated == 0L) {
                        stringResource(R.string.built_in_list)
                    } else {
                        val date = Date(lastUpdated)
                        stringResource(
                            R.string.last_updated,
                            "${DateFormat.getMediumDateFormat(context).format(date)} " +
                                DateFormat.getTimeFormat(context).format(date)
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = {
                updating = true
                message = null
                scope.launch {
                    message = try {
                        Blocklist.update(context)
                        doneText
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failedText.format(e.message ?: e.javaClass.simpleName)
                    }
                    updating = false
                }
            },
            enabled = !updating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (updating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.updating))
                } else {
                    Text(stringResource(R.string.update_now))
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            text = stringResource(R.string.auto_update_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.about))
        }
        val policyUrl = stringResource(R.string.privacy_policy_url)
        OutlinedButton(
            onClick = { runCatching { uriHandler.openUri(policyUrl) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.privacy_policy))
        }
        val blocklistUrl = stringResource(R.string.blocklist_url)
        TextButton(onClick = { runCatching { uriHandler.openUri(blocklistUrl) } }) {
            Text(stringResource(R.string.blocklist_credit))
        }
    }
}
