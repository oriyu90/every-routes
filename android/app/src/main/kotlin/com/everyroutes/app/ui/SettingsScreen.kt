package com.everyroutes.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.everyroutes.app.R
import com.everyroutes.app.settings.ConnectionMode
import com.everyroutes.app.settings.ConnectionSettings
import com.everyroutes.app.vpn.VpnErrorCodes
import com.everyroutes.app.vpn.VpnState
import com.everyroutes.app.vpn.VpnStatus
import com.everyroutes.app.vpn.WireGuardProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 設定 Route（container）。launcher・effect 収集・ダイアログ表示を担い、
 * 業務状態は [SettingsViewModel] に置く。
 */
@Composable
fun SettingsRoute(
    vm: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    var messageRes by remember { mutableStateOf<Int?>(null) }
    var messageArg by remember { mutableStateOf<String?>(null) }
    val showManualDialog = remember { mutableStateOf(false) }
    val showDeleteConfirm = remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        vm.onEvent(SettingsEvent.PermissionResult(vm.permissionIntent() == null))
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = runCatching { resolver.readConfigText(uri) }.getOrNull()
            if (text == null) {
                vm.onEvent(SettingsEvent.ImportFailed("read failed"))
                return@launch
            }
            val name = runCatching { resolver.displayName(uri) }.getOrNull() ?: "wireguard"
            vm.onEvent(SettingsEvent.ImportReceived(name, text))
        }
    }

    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.ShowMessage -> {
                    messageRes = effect.resId
                    messageArg = effect.arg
                }
                SettingsEffect.OpenPermissionDialog -> {
                    vm.permissionIntent()?.let { permissionLauncher.launch(it) }
                }
                SettingsEffect.OpenImportPicker -> {
                    importLauncher.launch(arrayOf("*/*"))
                }
            }
        }
    }

    val message = messageRes?.let { res ->
        messageArg?.let { stringResource(res, it) } ?: stringResource(res)
    }
    SettingsScreen(
        state = state,
        message = message,
        showManualDialog = showManualDialog,
        showDeleteConfirm = showDeleteConfirm,
        onEvent = vm::onEvent,
        modifier = modifier,
    )
}

/**
 * 設定画面（stateless）。表示とイベント配送のみ。
 *
 * 秘密値（トークン・鍵）はマスク表示し、ログにも素通ししない。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    message: String?,
    showManualDialog: MutableState<Boolean>,
    showDeleteConfirm: MutableState<Boolean>,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        }
        if (message != null) {
            item { Text(message, color = MaterialTheme.colorScheme.primary) }
        }
        item { LanguageSection() }
        item {
            ServerSection(
                settings = state.settings,
                onSettingsChange = { onEvent(SettingsEvent.SettingsChanged(it)) },
                onSave = { onEvent(SettingsEvent.SaveSettings) },
            )
        }
        item {
            ModeSection(
                settings = state.settings,
                onSettingsChange = { onEvent(SettingsEvent.SettingsChanged(it)) },
                onSave = { onEvent(SettingsEvent.SaveSettings) },
            )
        }
        item {
            VpnStatusSection(status = state.status, profile = state.profile)
        }
        item {
            VpnActionsSection(
                hasProfile = state.profile != null,
                state = state.status.state,
                needsPermission = state.status.state == VpnState.PERMISSION_REQUIRED,
                onImport = { onEvent(SettingsEvent.ImportPickerRequested) },
                onManual = { showManualDialog.value = true },
                onConnect = { onEvent(SettingsEvent.Connect) },
                onDisconnect = { onEvent(SettingsEvent.Disconnect) },
                onReconnect = { onEvent(SettingsEvent.Reconnect) },
                onGrantPermission = { onEvent(SettingsEvent.GrantPermission) },
                onTest = { onEvent(SettingsEvent.TestConnection) },
                onRefresh = { onEvent(SettingsEvent.Refresh) },
                onDelete = { showDeleteConfirm.value = true },
            )
        }
        if (state.diag.isNotEmpty()) {
            item { DiagSection(diag = state.diag) }
        }
        item {
            LogHeader(onClear = { onEvent(SettingsEvent.ClearLog) })
            if (state.logEntries.isEmpty()) {
                Text(stringResource(R.string.vpn_log_empty))
            }
        }
        items(
            items = state.logEntries.takeLast(LOG_VISIBLE_COUNT),
            key = { "${it.atMs}-${it.code}-${it.message.hashCode()}" },
            contentType = { "log" },
        ) { entry ->
            Text("${entry.code}: ${entry.message}", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showManualDialog.value) {
        ManualProfileDialog(
            onDismiss = { showManualDialog.value = false },
            onSave = {
                showManualDialog.value = false
                onEvent(SettingsEvent.ManualSave(it))
            },
        )
    }
    if (showDeleteConfirm.value) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm.value = false },
            text = { Text(stringResource(R.string.vpn_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm.value = false
                        onEvent(SettingsEvent.DeleteConfirmed)
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm.value = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private const val LOG_VISIBLE_COUNT = 20

@Composable
private fun LanguageSection(modifier: Modifier = Modifier) {
    val selected = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.language_section), style = MaterialTheme.typography.titleMedium)
        LanguageRow(selected.isBlank(), R.string.language_system) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        LanguageRow(selected.startsWith("ja"), R.string.language_japanese) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ja"))
        }
        LanguageRow(selected.startsWith("en"), R.string.language_english) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
    }
}

@Composable
private fun LanguageRow(selected: Boolean, labelRes: Int, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        TextButton(onClick = onSelect) { Text(stringResource(labelRes)) }
    }
}

@Composable
private fun ServerSection(
    settings: ConnectionSettings,
    onSettingsChange: (ConnectionSettings) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.server_section), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = settings.serverBaseUrl,
            onValueChange = { onSettingsChange(settings.copy(serverBaseUrl = it)) },
            label = { Text(stringResource(R.string.server_url_label)) },
            placeholder = { Text(stringResource(R.string.server_url_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = settings.bearerToken,
            onValueChange = { onSettingsChange(settings.copy(bearerToken = it)) },
            label = { Text(stringResource(R.string.bearer_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.trustSelfSigned,
                onCheckedChange = { onSettingsChange(settings.copy(trustSelfSigned = it)) },
            )
            Text(
                stringResource(R.string.trust_self_signed),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun ModeSection(
    settings: ConnectionSettings,
    onSettingsChange: (ConnectionSettings) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.connection_mode_label), style = MaterialTheme.typography.titleMedium)
        ModeRow(
            selected = settings.mode == ConnectionMode.DIRECT,
            labelRes = R.string.mode_direct,
            onSelect = { onSettingsChange(settings.copy(mode = ConnectionMode.DIRECT)) },
        )
        ModeRow(
            selected = settings.mode == ConnectionMode.APP_ONLY_WIREGUARD,
            labelRes = R.string.mode_wireguard,
            onSelect = { onSettingsChange(settings.copy(mode = ConnectionMode.APP_ONLY_WIREGUARD)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.vpnEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(vpnEnabled = it)) },
            )
            Text(
                stringResource(R.string.vpn_enable),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            stringResource(R.string.vpn_enable_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun VpnStatusSection(
    status: VpnStatus,
    profile: WireGuardProfile?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.vpn_profile_section), style = MaterialTheme.typography.titleMedium)
        if (profile == null) {
            Text(stringResource(R.string.vpn_no_profile))
        } else {
            ProfileRow(labelRes = R.string.vpn_profile_name_label, value = profile.displayName)
            ProfileRow(
                labelRes = R.string.vpn_endpoint_label,
                value = profile.endpointHost ?: stringResource(R.string.vpn_unknown),
            )
        }
        ProfileRow(labelRes = R.string.vpn_status_label, value = stateName(status.state))
        if (status.tunnelUp) {
            ProfileRow(
                labelRes = R.string.vpn_handshake_label,
                value = status.handshakeAgeSec?.let {
                    val age = it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
                    pluralStringResource(R.plurals.vpn_handshake_ago, age, it)
                } ?: stringResource(R.string.vpn_handshake_never),
            )
            ProfileRow(
                labelRes = R.string.vpn_traffic_label,
                value = stringResource(
                    R.string.vpn_traffic_value,
                    formatBytes(status.txBytes),
                    formatBytes(status.rxBytes),
                ),
            )
        }
        if (status.lastError != VpnErrorCodes.NONE) {
            ProfileRow(labelRes = R.string.vpn_last_error_label, value = status.lastError)
        }
        if (status.retryCount > 0) {
            ProfileRow(
                labelRes = R.string.vpn_retry_count_label,
                value = status.retryCount.toString(),
            )
        }
        ProfileRow(
            labelRes = R.string.vpn_server_label,
            value = triName(status.serverReachable),
        )
        ProfileRow(
            labelRes = R.string.vpn_auth_label,
            value = triName(status.apiAuthorized),
        )
        if (status.hasDefaultRoute) {
            Text(
                stringResource(R.string.vpn_warning_default_route),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.vpn_note_scope),
            style = MaterialTheme.typography.bodySmall,
        )
        if (status.otherVpnActive) {
            Text(
                stringResource(R.string.vpn_note_other_vpn),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VpnActionsSection(
    hasProfile: Boolean,
    state: VpnState,
    needsPermission: Boolean,
    onImport: () -> Unit,
    onManual: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onGrantPermission: () -> Unit,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vpn_import))
            }
            Button(onClick = onManual, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vpn_manual))
            }
        }
        if (hasProfile) {
            if (needsPermission) {
                Button(onClick = onGrantPermission, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vpn_grant_permission))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, modifier = Modifier.weight(1f)) {
                    Text(
                        if (state == VpnState.ERROR) {
                            stringResource(R.string.vpn_reconnect)
                        } else {
                            stringResource(R.string.vpn_connect)
                        },
                    )
                }
                Button(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.vpn_disconnect))
                }
            }
            Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vpn_reconnect))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTest, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.vpn_test))
                }
                Button(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.vpn_refresh))
                }
            }
            Button(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vpn_delete))
            }
        }
    }
}

@Composable
private fun DiagSection(
    diag: List<DiagStep>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.vpn_test), style = MaterialTheme.typography.titleMedium)
        diag.forEach { step ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(step.labelRes), modifier = Modifier.weight(1f))
                Text(
                    when (step.pass) {
                        true -> stringResource(R.string.vpn_diag_pass)
                        false -> stringResource(R.string.vpn_diag_fail)
                        null -> stringResource(R.string.vpn_unknown)
                    },
                )
            }
        }
    }
}

@Composable
private fun LogHeader(
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.vpn_log_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.vpn_log_clear))
        }
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    labelRes: Int,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(stringResource(labelRes))
    }
}

@Composable
private fun ProfileRow(
    labelRes: Int,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f))
        Text(value)
    }
}

@Composable
private fun stateName(state: VpnState): String = stringResource(
    when (state) {
        VpnState.DISABLED -> R.string.vpn_state_disabled
        VpnState.CONFIG_MISSING -> R.string.vpn_state_config_missing
        VpnState.PERMISSION_REQUIRED -> R.string.vpn_state_permission_required
        VpnState.CONNECTING -> R.string.vpn_state_connecting
        VpnState.CONNECTED -> R.string.vpn_state_connected
        VpnState.DEGRADED -> R.string.vpn_state_degraded
        VpnState.DISCONNECTED -> R.string.vpn_state_disconnected
        VpnState.ERROR -> R.string.vpn_state_error
    },
)

@Composable
private fun triName(v: Boolean?): String = stringResource(
    when (v) {
        true -> R.string.vpn_yes
        false -> R.string.vpn_no
        null -> R.string.vpn_unknown
    },
)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> "${bytes / (1024L * 1024L)}MB"
}

/** 手動入力ダイアログの値。wg-quick テキスト化してから sanitize する。 */
data class ManualProfileInput(
    val displayName: String,
    val privateKey: String,
    val address: String,
    val dns: String,
    val peerPublicKey: String,
    val presharedKey: String,
    val endpoint: String,
    val allowedIps: String,
    val keepalive: String,
) {
    fun toWgQuick(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${privateKey.trim()}")
        appendLine("Address = ${address.trim()}")
        if (dns.isNotBlank()) appendLine("DNS = ${dns.trim()}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${peerPublicKey.trim()}")
        if (presharedKey.isNotBlank()) appendLine("PresharedKey = ${presharedKey.trim()}")
        appendLine("Endpoint = ${endpoint.trim()}")
        appendLine("AllowedIPs = ${allowedIps.trim()}")
        if (keepalive.isNotBlank()) appendLine("PersistentKeepalive = ${keepalive.trim()}")
    }
}

@Composable
private fun ManualProfileDialog(
    onDismiss: () -> Unit,
    onSave: (ManualProfileInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = remember { mutableStateOf("") }
    val privateKey = remember { mutableStateOf("") }
    val address = remember { mutableStateOf("") }
    val dns = remember { mutableStateOf("") }
    val peerKey = remember { mutableStateOf("") }
    val psk = remember { mutableStateOf("") }
    val endpoint = remember { mutableStateOf("") }
    val allowedIps = remember { mutableStateOf("") }
    val keepalive = remember { mutableStateOf("") }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vpn_manual_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ManualField(name, R.string.field_profile_name, secret = false)
                    ManualField(privateKey, R.string.field_private_key, secret = true)
                    ManualField(address, R.string.field_address, secret = false)
                    ManualField(dns, R.string.field_dns, secret = false)
                    ManualField(peerKey, R.string.field_peer_public_key, secret = true)
                    ManualField(psk, R.string.field_preshared_key, secret = true)
                    ManualField(endpoint, R.string.field_endpoint, secret = false)
                    ManualField(allowedIps, R.string.field_allowed_ips, secret = false)
                    ManualField(keepalive, R.string.field_keepalive, secret = false)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ManualProfileInput(
                            displayName = name.value.trim().ifEmpty { "wireguard" },
                            privateKey = privateKey.value,
                            address = address.value,
                            dns = dns.value,
                            peerPublicKey = peerKey.value,
                            presharedKey = psk.value,
                            endpoint = endpoint.value,
                            allowedIps = allowedIps.value,
                            keepalive = keepalive.value,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ManualField(
    state: MutableState<String>,
    labelRes: Int,
    secret: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.value,
        onValueChange = { state.value = it },
        label = { Text(stringResource(labelRes)) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
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
