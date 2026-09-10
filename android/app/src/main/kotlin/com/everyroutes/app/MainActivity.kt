package com.everyroutes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.everyroutes.app.model.BlockEntry
import com.everyroutes.app.model.TaskEntry
import com.everyroutes.app.model.buildTodayView
import com.everyroutes.app.settings.AppSettingsStore
import com.everyroutes.app.ui.SettingsRoute
import com.everyroutes.app.ui.SettingsViewModel
import com.everyroutes.app.ui.theme.EveryRoutesTheme
import com.everyroutes.app.vpn.EveryRoutesVpnManager
import com.everyroutes.app.vpn.WireGuardProfileStore
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val manager = (application as EveryRoutesApp).vpnManager
        val settingsStore = AppSettingsStore(this)
        val profileStore = WireGuardProfileStore(this)
        val vm = ViewModelProvider(
            this,
            SettingsViewModelFactory(settingsStore, profileStore, manager),
        )[SettingsViewModel::class.java]
        setContent {
            EveryRoutesTheme {
                AppRoot(vm)
            }
        }
    }
}

private class SettingsViewModelFactory(
    private val settingsStore: AppSettingsStore,
    private val profileStore: WireGuardProfileStore,
    private val manager: EveryRoutesVpnManager,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(settingsStore, profileStore, manager) as T
}

@Composable
private fun AppRoot(
    vm: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_today)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { innerPadding ->
        if (tab == 0) {
            TodayScreen(modifier = Modifier.padding(innerPadding))
        } else {
            SettingsRoute(vm = vm, modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    val view = remember {
        buildTodayView(LocalDate.now(), emptyList(), emptyList())
    }
    LazyColumn(modifier = modifier.padding(16.dp)) {
        item(contentType = "header") {
            Text(stringResource(R.string.today_title), style = MaterialTheme.typography.headlineSmall)
        }
        if (view.entries.isEmpty()) {
            item(contentType = "empty") {
                Text(stringResource(R.string.empty_today), Modifier.padding(top = 12.dp))
            }
        } else {
            items(
                items = view.entries,
                key = { entry ->
                    when (entry) {
                        is BlockEntry -> "block-${entry.block.id}"
                        is TaskEntry -> "task-${entry.task.id}"
                    }
                },
                contentType = { entry ->
                    when (entry) {
                        is BlockEntry -> "block"
                        is TaskEntry -> "task"
                    }
                },
            ) { entry ->
                when (entry) {
                    is BlockEntry -> Text("${entry.block.start}–${entry.block.end} ${entry.block.title}")
                    is TaskEntry -> Text("${entry.task.at} ${entry.task.title}")
                }
            }
        }
    }
}
