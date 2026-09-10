package com.everyroutes.app.vpn

/** VPN トンネルの状態。UI 表示と再試行判定の共通語彙。 */
enum class VpnState {
    DISABLED,
    CONFIG_MISSING,
    PERMISSION_REQUIRED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
    DISCONNECTED,
    ERROR,
}

/** 接続テスト・エラー表示で使う診断コード。秘密情報を含まない。 */
object VpnErrorCodes {
    const val NONE = "OK"
    const val VPN_DISABLED = "VPN_DISABLED"
    const val CONFIG_MISSING = "CONFIG_MISSING"
    const val WG_CONFIG_INVALID = "WG_CONFIG_INVALID"
    const val VPN_PERMISSION_REQUIRED = "VPN_PERMISSION_REQUIRED"
    const val VPN_CONFLICT = "VPN_CONFLICT"
    const val WG_DNS_FAILED = "WG_DNS_FAILED"
    const val WG_HANDSHAKE_TIMEOUT = "WG_HANDSHAKE_TIMEOUT"
    const val WG_TUNNEL_FAILED = "WG_TUNNEL_FAILED"
    const val SERVER_UNREACHABLE = "SERVER_UNREACHABLE"
    const val SERVER_AUTH_FAILED = "SERVER_AUTH_FAILED"
    const val RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED"
}

/**
 * VPN 状態のスナップショット。endpointHost にはホスト名のみを入れ、
 * PrivateKey / PresharedKey / Bearer トークンは絶対に含めない。
 */
data class VpnStatus(
    val state: VpnState,
    val profileName: String? = null,
    val endpointHost: String? = null,
    val tunnelUp: Boolean = false,
    val handshakeAgeSec: Long? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val serverReachable: Boolean? = null,
    val apiAuthorized: Boolean? = null,
    val lastError: String = VpnErrorCodes.NONE,
    val retryCount: Int = 0,
    val permissionGranted: Boolean = false,
    val otherVpnActive: Boolean = false,
    val hasDefaultRoute: Boolean = false,
)
