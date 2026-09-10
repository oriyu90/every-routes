package com.everyroutes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.everyroutes.app.R
import com.everyroutes.app.settings.ConnectionMode
import com.everyroutes.app.settings.ConnectionSettings
import com.everyroutes.app.vpn.VpnErrorCodes
import com.everyroutes.app.vpn.VpnLog
import com.everyroutes.app.vpn.VpnState
import com.everyroutes.app.vpn.VpnStatus
import com.everyroutes.app.vpn.WireGuardProfile

/** 接続テストの1段。pass=null は未実施。 */
data class DiagStep(val labelRes: Int, val pass: Boolean?)

/**
 * サーバー接続・VPN 設定画面。秘密値（トークン・鍵）はマスク表示し、
 * ログにも素通ししない（[VpnLog] が記録時にマスクする）。
 */
@Composable
fun SettingsScreen(
    settings: ConnectionSettings,
    onSettingsChange: (ConnectionSettings) -> Unit,
    profile: WireGuardProfile?,
    status: VpnStatus,
    logEntries: List<VpnLog.Entry>,
    diag: List<DiagStep>,
    message: String?,
    showManualDialog: MutableState<Boolean>,
    showDeleteConfirm: MutableState<Boolean>,
    onImportClick: () -> Unit,
    onManualSave: (ManualProfileInput) -> Unit,
    onDeleteConfirm: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onGrantPermission: () -> Unit,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
    onClearLog: () -> Unit,
    onSaveSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        }
        if (message != null) {
            item { Text(message, color = MaterialTheme.colorScheme.primary) }
        }
        item {
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
        }
        item {
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
            Spacer(Modifier.height(4.dp))
            Button(onClick = onSaveSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_save))
            }
        }
        item {
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
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.vpn_handshake_ago,
                            age,
                            it,
                        )
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
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImportClick, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.vpn_import))
                    }
                    Button(
                        onClick = { showManualDialog.value = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.vpn_manual))
                    }
                }
                if (profile != null) {
                    if (status.state == VpnState.PERMISSION_REQUIRED) {
                        Button(onClick = onGrantPermission, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.vpn_grant_permission))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConnect, modifier = Modifier.weight(1f)) {
                            Text(
                                if (status.state == VpnState.ERROR) {
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
                    Button(
                        onClick = { showDeleteConfirm.value = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.vpn_delete))
                    }
                }
            }
        }
        if (diag.isNotEmpty()) {
            item {
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
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.vpn_log_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearLog) {
                    Text(stringResource(R.string.vpn_log_clear))
                }
            }
            if (logEntries.isEmpty()) {
                Text(stringResource(R.string.vpn_log_empty))
            }
        }
        items(logEntries.takeLast(20)) { entry ->
            Text(
                "${entry.code}: ${entry.message}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showManualDialog.value) {
        ManualProfileDialog(
            onDismiss = { showManualDialog.value = false },
            onSave = {
                showManualDialog.value = false
                onManualSave(it)
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
                        onDeleteConfirm()
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

@Composable
private fun ModeRow(selected: Boolean, labelRes: Int, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(stringResource(labelRes))
    }
}

@Composable
private fun ProfileRow(labelRes: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
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
) {
    val name = androidx.compose.runtime.remember { mutableStateOf("") }
    val privateKey = androidx.compose.runtime.remember { mutableStateOf("") }
    val address = androidx.compose.runtime.remember { mutableStateOf("") }
    val dns = androidx.compose.runtime.remember { mutableStateOf("") }
    val peerKey = androidx.compose.runtime.remember { mutableStateOf("") }
    val psk = androidx.compose.runtime.remember { mutableStateOf("") }
    val endpoint = androidx.compose.runtime.remember { mutableStateOf("") }
    val allowedIps = androidx.compose.runtime.remember { mutableStateOf("") }
    val keepalive = androidx.compose.runtime.remember { mutableStateOf("") }
    AlertDialog(
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
) {
    OutlinedTextField(
        value = state.value,
        onValueChange = { state.value = it },
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
