package com.everyroutes.app.settings

/** サーバーへの接続方式。VPN は通信経路の差し替えであり API 層は意識しない。 */
enum class ConnectionMode {
    DIRECT,
    APP_ONLY_WIREGUARD,
}

/**
 * 接続設定。VPN 機能は [vpnEnabled] が既定 OFF であり、
 * OFF の間はモードが VPN でもトンネルを開始しない。
 */
@androidx.compose.runtime.Immutable
data class ConnectionSettings(
    val mode: ConnectionMode = ConnectionMode.DIRECT,
    val vpnEnabled: Boolean = false,
    val serverBaseUrl: String = "",
    val bearerToken: String = "",
    val trustSelfSigned: Boolean = false,
) {
    /** VPN 経路を使うべき設定状態か（権限・プロファイルの有無は含まない）。 */
    fun wantsVpn(): Boolean = mode == ConnectionMode.APP_ONLY_WIREGUARD && vpnEnabled

    fun baseUrlError(): String? {
        if (serverBaseUrl.isBlank()) return "blank"
        val v = serverBaseUrl.trim()
        if (!v.startsWith("http://") && !v.startsWith("https://")) return "scheme"
        return null
    }

    /** ログ・画面表示用。トークンを含めない。 */
    fun describe(): String =
        "mode=$mode, vpnEnabled=$vpnEnabled, url=${serverBaseUrl.trim().trimEnd('/')}"
}
