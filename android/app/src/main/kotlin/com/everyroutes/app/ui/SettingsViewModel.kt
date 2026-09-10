package com.everyroutes.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.everyroutes.app.R
import com.everyroutes.app.settings.AppSettingsStore
import com.everyroutes.app.settings.ConnectionSettings
import com.everyroutes.app.vpn.EveryRoutesVpnManager
import com.everyroutes.app.vpn.ServerReachability
import com.everyroutes.app.vpn.VpnErrorCodes
import com.everyroutes.app.vpn.VpnState
import com.everyroutes.app.vpn.WireGuardProfile
import com.everyroutes.app.vpn.WireGuardProfileStore
import com.everyroutes.app.vpn.WireGuardSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 設定画面の ViewModel（MVI）。状態更新は `_state.update { it.copy(...) }` のみ。
 * 秘密値を含む処理はここに集約し、Composable は表示とイベント配送に徹する。
 */
class SettingsViewModel(
    private val settingsStore: AppSettingsStore,
    private val profileStore: WireGuardProfileStore,
    private val manager: EveryRoutesVpnManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        _state.update {
            it.copy(
                settings = settingsStore.load(),
                profile = profileStore.load(),
                logEntries = manager.vpnLog.snapshot(),
            )
        }
        // トンネル状態はホットな StateFlow なので、そのまま UiState へ反映する。
        manager.status
            .onEach { status -> _state.update { it.copy(status = status) } }
            .launchIn(viewModelScope)
    }

    /** 未許可時に OS 許可ダイアログを開くための Intent。null なら付与済み。 */
    fun permissionIntent(): Intent? = manager.permissionIntent()

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SettingsChanged -> {
                _state.update { it.copy(settings = event.settings) }
            }
            SettingsEvent.SaveSettings -> saveSettings()
            is SettingsEvent.ImportReceived -> saveProfile(event.displayName, event.configText)
            is SettingsEvent.ImportFailed -> {
                manager.vpnLog.add(VpnErrorCodes.WG_CONFIG_INVALID, "import read failed")
                refreshLog()
                emitMessage(R.string.vpn_profile_invalid, event.reason)
            }
            is SettingsEvent.ManualSave -> saveProfile(
                event.input.displayName.ifBlank { "wireguard" },
                event.input.toWgQuick(),
            )
            SettingsEvent.DeleteConfirmed -> deleteProfile()
            SettingsEvent.Connect -> connect(thenConnect = true)
            SettingsEvent.Reconnect -> connect(thenConnect = true)
            SettingsEvent.Disconnect -> disconnect()
            SettingsEvent.GrantPermission -> openPermission(thenConnect = false)
            SettingsEvent.ImportPickerRequested -> emitEffect(SettingsEffect.OpenImportPicker)
            is SettingsEvent.PermissionResult -> onPermissionResult(event.granted)
            SettingsEvent.TestConnection -> testConnection()
            SettingsEvent.Refresh -> {
                manager.refreshStats(_state.value.profile)
                refreshLog()
            }
            SettingsEvent.ClearLog -> {
                manager.vpnLog.clear()
                refreshLog()
            }
        }
    }

    private fun saveSettings() {
        settingsStore.save(_state.value.settings)
        if (!_state.value.settings.wantsVpn()) {
            manager.refreshStats(_state.value.profile)
        }
        refreshLog()
        emitMessage(R.string.settings_saved)
    }

    private fun saveProfile(displayName: String, rawConfig: String) {
        when (val r = WireGuardSanitizer.sanitize(rawConfig)) {
            is WireGuardSanitizer.SanitizeResult.Ok -> {
                profileStore.save(
                    WireGuardProfile(
                        displayName = displayName,
                        sanitizedConfig = r.sanitizedConfig,
                        endpointHost = r.endpointHost,
                        hasDefaultRoute = r.hasDefaultRoute,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
                manager.vpnLog.add(VpnErrorCodes.NONE, "profile saved: $displayName")
                _state.update { it.copy(profile = profileStore.load()) }
                manager.refreshStats(_state.value.profile)
                refreshLog()
                emitMessage(R.string.vpn_profile_saved)
            }
            is WireGuardSanitizer.SanitizeResult.Err -> {
                manager.vpnLog.add(r.code, "profile rejected: ${r.detail}")
                refreshLog()
                emitMessage(R.string.vpn_profile_invalid, "${r.code} ${r.detail}")
            }
        }
    }

    private fun deleteProfile() {
        manager.disconnect()
        profileStore.delete()
        manager.vpnLog.add(VpnErrorCodes.NONE, "profile deleted by user action")
        _state.update { it.copy(profile = profileStore.load()) }
        manager.refreshStats(_state.value.profile)
        refreshLog()
        emitMessage(R.string.vpn_profile_deleted)
    }

    private fun connect(thenConnect: Boolean) {
        val profile = _state.value.profile
        if (profile == null) {
            emitMessage(R.string.vpn_profile_invalid, VpnErrorCodes.CONFIG_MISSING)
            return
        }
        if (manager.isPermissionGranted()) {
            manager.connect(profile)
        } else {
            openPermission(thenConnect = thenConnect)
        }
    }

    private fun openPermission(thenConnect: Boolean) {
        if (manager.permissionIntent() == null) {
            if (thenConnect) {
                _state.value.profile?.let { manager.connect(it) }
            } else {
                manager.refreshStats(_state.value.profile)
            }
            return
        }
        _state.update { it.copy(pendingConnect = thenConnect) }
        emitEffect(SettingsEffect.OpenPermissionDialog)
    }

    private fun onPermissionResult(granted: Boolean) {
        val pending = _state.value.pendingConnect
        _state.update { it.copy(pendingConnect = false) }
        manager.refreshStats(_state.value.profile)
        if (granted && pending) {
            _state.value.profile?.let { manager.connect(it) }
        }
    }

    private fun disconnect() {
        manager.disconnect()
        refreshLog()
    }

    private fun testConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = _state.value
            val settings = snapshot.settings
            val profile = snapshot.profile
            val steps = mutableListOf<DiagStep>()
            steps.add(DiagStep(R.string.vpn_diag_profile, profile != null))
            emitDiag(steps)
            val permOk = manager.isPermissionGranted()
            steps.add(DiagStep(R.string.vpn_diag_permission, if (settings.wantsVpn()) permOk else true))
            emitDiag(steps)
            var tunnelOk: Boolean? = null
            var hsOk: Boolean? = null
            if (settings.wantsVpn() && profile != null) {
                tunnelOk = manager.ensureUpForSync(profile, settings)
                steps.add(DiagStep(R.string.vpn_diag_tunnel, tunnelOk))
                emitDiag(steps)
                manager.refreshStats(profile)
                delay(DIAG_REFRESH_WAIT_MS)
                hsOk = manager.status.value.handshakeAgeSec != null
                steps.add(DiagStep(R.string.vpn_diag_handshake, hsOk))
                emitDiag(steps)
            } else {
                steps.add(DiagStep(R.string.vpn_diag_tunnel, null))
                steps.add(DiagStep(R.string.vpn_diag_handshake, null))
                emitDiag(steps)
            }
            val urlOk = settings.baseUrlError() == null
            val reach = if (urlOk) {
                ServerReachability.checkHealth(settings.serverBaseUrl, settings.trustSelfSigned)
            } else {
                ServerReachability.CheckResult.BadUrl
            }
            val reachOk = reach == ServerReachability.CheckResult.Reachable
            steps.add(DiagStep(R.string.vpn_diag_reach, reachOk))
            emitDiag(steps)
            val auth = if (urlOk && settings.bearerToken.isNotBlank()) {
                ServerReachability.checkAuth(
                    settings.serverBaseUrl,
                    settings.bearerToken,
                    settings.trustSelfSigned,
                )
            } else {
                ServerReachability.CheckResult.HttpError(0)
            }
            val authOk = auth == ServerReachability.CheckResult.Reachable
            steps.add(DiagStep(R.string.vpn_diag_auth, authOk))
            emitDiag(steps)
            manager.noteServerCheck(reachOk, if (settings.bearerToken.isBlank()) null else authOk)
            val code = when {
                profile == null && settings.wantsVpn() -> VpnErrorCodes.CONFIG_MISSING
                settings.wantsVpn() && !permOk -> VpnErrorCodes.VPN_PERMISSION_REQUIRED
                tunnelOk == false -> VpnErrorCodes.WG_TUNNEL_FAILED
                hsOk == false -> VpnErrorCodes.WG_HANDSHAKE_TIMEOUT
                !reachOk -> VpnErrorCodes.SERVER_UNREACHABLE
                !authOk -> VpnErrorCodes.SERVER_AUTH_FAILED
                else -> VpnErrorCodes.NONE
            }
            manager.vpnLog.add(code, "connection test finished: $code")
            refreshLog()
        }
    }

    private fun emitDiag(steps: List<DiagStep>) {
        _state.update { it.copy(diag = steps.toList()) }
    }

    private fun refreshLog() {
        _state.update { it.copy(logEntries = manager.vpnLog.snapshot()) }
    }

    private fun emitMessage(resId: Int, arg: String? = null) {
        viewModelScope.launch {
            _effects.send(SettingsEffect.ShowMessage(resId, arg))
        }
    }

    private fun emitEffect(effect: SettingsEffect) {
        viewModelScope.launch { _effects.send(effect) }
    }

    companion object {
        private const val DIAG_REFRESH_WAIT_MS = 600L
    }
}
