package com.diu.privacyguard.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.diu.privacyguard.R
import com.diu.privacyguard.data.Blocklist
import com.diu.privacyguard.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

private enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Home(R.string.tab_home, R.drawable.ic_shield),
    Blocked(R.string.tab_blocked, R.drawable.ic_list),
    Filters(R.string.tab_filters, R.drawable.ic_filter),
}

@Composable
fun PrivacyGuardApp() {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        Blocklist.load(context)
        // Automatic weekly update, and right away when the list of sources has changed
        // (e.g. after an app update). Failure is fine: the current list keeps working.
        val lastUpdated = Settings.lastUpdated(context).first()
        val sourcesChanged = Settings.sourcesHash(context).first() != Blocklist.SOURCES.hashCode()
        if (sourcesChanged || System.currentTimeMillis() - lastUpdated > WEEK_MS) {
            try {
                Blocklist.update(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No internet right now; try again next time the app opens.
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppUpdateBanner()
            Box(Modifier.weight(1f)) {
                when (Tab.entries[selected]) {
                    Tab.Home -> HomeScreen()
                    Tab.Blocked -> BlockedScreen()
                    Tab.Filters -> FiltersScreen()
                }
            }
        }
    }
}
