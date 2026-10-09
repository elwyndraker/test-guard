package com.diu.privacyguard.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diu.privacyguard.R
import com.diu.privacyguard.data.BlockedLog
import java.util.Date

@Composable
fun BlockedScreen() {
    val context = LocalContext.current
    val entries by BlockedLog.entries.collectAsStateWithLifecycle()
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.blocked_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { BlockedLog.clear() }, enabled = entries.isNotEmpty()) {
                Text(stringResource(R.string.clear))
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.blocked_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(entry.domain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = if (entry.count > 1) {
                            { Text(stringResource(R.string.blocked_times, entry.count)) }
                        } else {
                            null
                        },
                        trailingContent = { Text(timeFormat.format(Date(entry.time))) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
