package com.everyroutes.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.everyroutes.app.settings.ConnectionSettings
import com.everyroutes.app.vpn.VpnLog
import com.everyroutes.app.vpn.VpnState
import com.everyroutes.app.vpn.VpnStatus
import com.everyroutes.app.vpn.WireGuardProfile

/** 接続テストの1段。pass=null は未実施。 */
@Immutable
data class DiagStep(val labelRes: Int, val pass: Boolean?)

/** 設定画面の単一 UiState。更新は ViewModel の `_state.update { it.copy(...) }` のみ。 */
@Immutable
data class SettingsUiState(
    val settings: ConnectionSettings = ConnectionSettings(),
    val profile: WireGuardProfile? = null,
    val status: VpnStatus = VpnStatus(state = VpnState.DISABLED),
    val logEntries: List<VpnLog.Entry> = emptyList(),
    val diag: List<DiagStep> = emptyList(),
    val pendingConnect: Boolean = false,
)

/** 設定画面への入力イベント。 */
sealed interface SettingsEvent {
    data class SettingsChanged(val settings: ConnectionSettings) : SettingsEvent
    data object SaveSettings : SettingsEvent
    data class ImportReceived(val displayName: String, val configText: String) : SettingsEvent
    data class ImportFailed(val reason: String) : SettingsEvent
    data class ManualSave(val input: ManualProfileInput) : SettingsEvent
    data object DeleteConfirmed : SettingsEvent
    data object Connect : SettingsEvent
    data object Disconnect : SettingsEvent
    data object Reconnect : SettingsEvent
    data object GrantPermission : SettingsEvent
    data object ImportPickerRequested : SettingsEvent
    data class PermissionResult(val granted: Boolean) : SettingsEvent
    data object TestConnection : SettingsEvent
    data object Refresh : SettingsEvent
    data object ClearLog : SettingsEvent
}

/** 一 shot 通知（スナックバー相当のメッセージ表示など）。 */
sealed interface SettingsEffect {
    data class ShowMessage(@StringRes val resId: Int, val arg: String? = null) : SettingsEffect
    /** OS の VPN 許可ダイアログを開く。 */
    data object OpenPermissionDialog : SettingsEffect
    /** SAF のファイル選択を開く。 */
    data object OpenImportPicker : SettingsEffect
}
