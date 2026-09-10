package com.everyroutes.app.vpn

/**
 * 保存済み WireGuard プロファイル。
 *
 * [sanitizedConfig] は app-only 強制済みの wg-quick テキスト（秘密鍵を含む）。
 * UI・ログには [displayName] と [endpointHost] のみを出し、
 * [sanitizedConfig] そのものを表示・記録してはならない。
 */
data class WireGuardProfile(
    val displayName: String,
    val sanitizedConfig: String,
    val endpointHost: String?,
    val hasDefaultRoute: Boolean,
    val updatedAtMs: Long,
) {
    /** ログ・画面表示用の非秘密サマリー。 */
    fun describe(): String = buildString {
        append("profile=").append(displayName)
        append(", peers=").append(peerCount(sanitizedConfig))
        endpointHost?.let { append(", endpoint=").append(it) }
        if (hasDefaultRoute) append(", default-route=yes")
    }

    companion object {
        private fun peerCount(config: String): Int =
            config.lineSequence().count { it.trim().equals("[Peer]", ignoreCase = true) }
    }
}
