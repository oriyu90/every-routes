package com.everyroutes.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.everyroutes.app.model.BlockEntry
import com.everyroutes.app.model.TaskEntry
import com.everyroutes.app.model.buildTodayView
import com.everyroutes.app.settings.AppSettingsStore
import com.everyroutes.app.settings.ConnectionSettings
import com.everyroutes.app.ui.DiagStep
import com.everyroutes.app.ui.ManualProfileInput
import com.everyroutes.app.ui.SettingsScreen
import com.everyroutes.app.vpn.EveryRoutesVpnManager
import com.everyroutes.app.vpn.ServerReachability
import com.everyroutes.app.vpn.VpnErrorCodes
import com.everyroutes.app.vpn.VpnState
import com.everyroutes.app.vpn.WireGuardProfile
import com.everyroutes.app.vpn.WireGuardProfileStore
import com.everyroutes.app.vpn.WireGuardSanitizer
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore = AppSettingsStore(this)
        val profileStore = WireGuardProfileStore(this)
        val manager = (application as EveryRoutesApp).vpnManager
        setContent {
            MaterialTheme {
                AppRoot(settingsStore, profileStore, manager)
            }
        }
    }
}

@Composable
private fun AppRoot(
    settingsStore: AppSettingsStore,
    profileStore: WireGuardProfileStore,
    manager: EveryRoutesVpnManager,
) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp)) {
            Button(onClick = { tab = 0 }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tab_today))
            }
            Button(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tab_settings))
            }
        }
        if (tab == 0) {
            TodayScreen()
        } else {
            SettingsRoute(settingsStore, profileStore, manager)
        }
    }
}

@Composable
private fun SettingsRoute(
    settingsStore: AppSettingsStore,
    profileStore: WireGuardProfileStore,
    manager: EveryRoutesVpnManager,
) {
    val scope = rememberCoroutineScope()
    val appContentResolver = LocalContext.current.contentResolver
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var profile by remember { mutableStateOf(profileStore.load()) }
    var status by remember { mutableStateOf(manager.status.value) }
    var diag by remember { mutableStateOf(emptyList<DiagStep>()) }
    var messageRes by remember { mutableStateOf<Int?>(null) }
    var messageArg by remember { mutableStateOf<String?>(null) }
    val showManualDialog = remember { mutableStateOf(false) }
    val showDeleteConfirm = remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf(false) }

    LaunchedEffect(manager) {
        manager.status.collect { status = it }
    }

    fun say(res: Int, arg: String? = null) {
        messageRes = res
        messageArg = arg
    }

    fun reloadProfile() {
        profile = profileStore.load()
        manager.refreshStats(profile)
    }

    fun saveProfile(name: String, rawConfig: String) {
        when (val r = WireGuardSanitizer.sanitize(rawConfig)) {
            is WireGuardSanitizer.SanitizeResult.Ok -> {
                profileStore.save(
                    WireGuardProfile(
                        displayName = name.ifBlank { "wireguard" },
                        sanitizedConfig = r.sanitizedConfig,
                        endpointHost = r.endpointHost,
                        hasDefaultRoute = r.hasDefaultRoute,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
                manager.vpnLog.add(VpnErrorCodes.NONE, "profile saved: $name")
                reloadProfile()
                say(R.string.vpn_profile_saved)
            }
            is WireGuardSanitizer.SanitizeResult.Err -> {
                manager.vpnLog.add(r.code, "profile rejected: ${r.detail}")
                say(R.string.vpn_profile_invalid, "${r.code} ${r.detail}")
            }
        }
    }

    // ActivityResultLauncher は Composition 内で remember する。
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                appContentResolver.readConfigText(uri)
            }.getOrNull()
            if (text == null) {
                withContext(Dispatchers.Main) { say(R.string.vpn_profile_invalid, "read failed") }
                return@launch
            }
            val name = runCatching { appContentResolver.displayName(uri) }.getOrNull()
                ?: "wireguard"
            withContext(Dispatchers.Main) { saveProfile(name, text) }
        }
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        manager.refreshStats(profile)
        if (manager.isPermissionGranted() && pendingConnect) {
            profile?.let { manager.connect(it) }
        }
        pendingConnect = false
    }

    fun requestPermission(thenConnect: Boolean) {
        val intent = manager.permissionIntent()
        if (intent == null) {
            if (thenConnect) {
                profile?.let { manager.connect(it) }
            } else {
                manager.refreshStats(profile)
            }
            return
        }
        pendingConnect = thenConnect
        permissionLauncher.launch(intent)
    }

    fun runConnectionTest() {
        scope.launch(Dispatchers.IO) {
            val steps = mutableListOf<DiagStep>()
            val s = settings
            val p = profile
            suspend fun emit() {
                withContext(Dispatchers.Main) { diag = steps.toList() }
            }
            // 1. 設定
            steps.add(DiagStep(R.string.vpn_diag_profile, p != null))
            emit()
            // 2. 権限（VPN を使う設定のときのみ必須）
            val permOk = manager.isPermissionGranted()
            steps.add(DiagStep(R.string.vpn_diag_permission, if (s.wantsVpn()) permOk else true))
            emit()
            // 3-4. トンネル + handshake
            var tunnelOk: Boolean? = null
            var hsOk: Boolean? = null
            if (s.wantsVpn() && p != null) {
                tunnelOk = manager.ensureUpForSync(p, s)
                steps.add(DiagStep(R.string.vpn_diag_tunnel, tunnelOk))
                emit()
                manager.refreshStats(p)
                // refresh は非同期のため少し待って状態を読む。
                kotlinx.coroutines.delay(600)
                hsOk = manager.status.value.handshakeAgeSec != null
                steps.add(DiagStep(R.string.vpn_diag_handshake, hsOk))
                emit()
            } else {
                steps.add(DiagStep(R.string.vpn_diag_tunnel, null))
                steps.add(DiagStep(R.string.vpn_diag_handshake, null))
                emit()
            }
            // 5-6. 到達性 + 認証
            val urlOk = s.baseUrlError() == null
            val reach = if (urlOk) {
                ServerReachability.checkHealth(s.serverBaseUrl, s.trustSelfSigned)
            } else {
                ServerReachability.CheckResult.BadUrl
            }
            val reachOk = reach == ServerReachability.CheckResult.Reachable
            steps.add(DiagStep(R.string.vpn_diag_reach, reachOk))
            emit()
            val auth = if (urlOk && s.bearerToken.isNotBlank()) {
                ServerReachability.checkAuth(s.serverBaseUrl, s.bearerToken, s.trustSelfSigned)
            } else {
                ServerReachability.CheckResult.HttpError(0)
            }
            val authOk = auth == ServerReachability.CheckResult.Reachable
            steps.add(DiagStep(R.string.vpn_diag_auth, authOk))
            emit()
            manager.noteServerCheck(reachOk, if (s.bearerToken.isBlank()) null else authOk)
            val code = when {
                p == null && s.wantsVpn() -> VpnErrorCodes.CONFIG_MISSING
                s.wantsVpn() && !permOk -> VpnErrorCodes.VPN_PERMISSION_REQUIRED
                tunnelOk == false -> VpnErrorCodes.WG_TUNNEL_FAILED
                hsOk == false -> VpnErrorCodes.WG_HANDSHAKE_TIMEOUT
                !reachOk -> VpnErrorCodes.SERVER_UNREACHABLE
                !authOk -> VpnErrorCodes.SERVER_AUTH_FAILED
                else -> VpnErrorCodes.NONE
            }
            manager.vpnLog.add(code, "connection test finished: $code")
        }
    }

    val msg = messageRes?.let { res ->
        messageArg?.let { stringResource(res, it) } ?: stringResource(res)
    }
    SettingsScreen(
        settings = settings,
        onSettingsChange = { settings = it },
        profile = profile,
        status = status,
        logEntries = manager.vpnLog.snapshot(),
        diag = diag,
        message = msg,
        showManualDialog = showManualDialog,
        showDeleteConfirm = showDeleteConfirm,
        onImportClick = { importLauncher.launch(arrayOf("*/*")) },
        onManualSave = { input: ManualProfileInput ->
            scope.launch(Dispatchers.IO) {
                val text = input.toWgQuick()
                withContext(Dispatchers.Main) { saveProfile(input.displayName, text) }
            }
        },
        onDeleteConfirm = {
            manager.disconnect()
            profileStore.delete()
            manager.vpnLog.add(VpnErrorCodes.NONE, "profile deleted by user action")
            reloadProfile()
            say(R.string.vpn_profile_deleted)
        },
        onConnect = {
            val p = profile ?: run {
                say(R.string.vpn_profile_invalid, VpnErrorCodes.CONFIG_MISSING)
                return@SettingsScreen
            }
            if (!manager.isPermissionGranted()) {
                requestPermission(thenConnect = true)
            } else {
                manager.connect(p)
            }
        },
        onDisconnect = { manager.disconnect() },
        onReconnect = {
            val p = profile ?: run {
                say(R.string.vpn_profile_invalid, VpnErrorCodes.CONFIG_MISSING)
                return@SettingsScreen
            }
            if (!manager.isPermissionGranted()) {
                requestPermission(thenConnect = true)
            } else {
                manager.connect(p)
            }
        },
        onGrantPermission = { requestPermission(thenConnect = false) },
        onTest = { runConnectionTest() },
        onRefresh = { manager.refreshStats(profile) },
        onClearLog = { manager.vpnLog.clear() },
        onSaveSettings = {
            settingsStore.save(settings)
            // VPN を使わない設定なら状態表示を無効へ寄せる。
            if (!settings.wantsVpn() && status.state != VpnState.DISABLED) {
                manager.refreshStats(profile)
            }
            say(R.string.settings_saved)
        },
    )
}

private fun android.content.ContentResolver.readConfigText(uri: Uri): String {
    openInputStream(uri)?.use { stream ->
        val buf = ByteArray(MAX_CONFIG_BYTES + 1)
        var total = 0
        while (total <= MAX_CONFIG_BYTES) {
            val n = stream.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return buf.copyOf(minOf(total, MAX_CONFIG_BYTES)).toString(Charsets.UTF_8)
    } ?: throw IllegalArgumentException("cannot open")
}

private fun android.content.ContentResolver.displayName(uri: Uri): String? {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            return c.getString(0)?.substringBeforeLast('.')
        }
    }
    return null
}

private const val MAX_CONFIG_BYTES = 64 * 1024

@Composable
fun TodayScreen() {
    val view = androidx.compose.runtime.remember {
        buildTodayView(LocalDate.now(), emptyList(), emptyList())
    }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.today_title), style = MaterialTheme.typography.headlineSmall)
        if (view.entries.isEmpty()) {
            Text(stringResource(R.string.empty_today), Modifier.padding(top = 12.dp))
        } else {
            LazyColumn {
                items(view.entries) { entry ->
                    when (entry) {
                        is BlockEntry -> Text("${entry.block.start}–${entry.block.end} ${entry.block.title}")
                        is TaskEntry -> Text("${entry.task.at} ${entry.task.title}")
                    }
                }
            }
        }
    }
}
