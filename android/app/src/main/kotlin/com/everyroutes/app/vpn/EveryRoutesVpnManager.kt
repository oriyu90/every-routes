package com.everyroutes.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.everyroutes.app.settings.ConnectionSettings
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-only WireGuard トンネルの制御。
 *
 * - UI スレッドを塞がない（ブロッキング処理は IO dispatcher）。
 * - 自動再接続は [VpnRetryPolicy] の上限で打ち切り、再開は UI 操作のみ。
 * - バックグラウンドからの VPN 許可 UI 起動はしない。
 */
class EveryRoutesVpnManager(private val appContext: Context) {
    val vpnLog = VpnLog()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backend: GoBackend? = runCatching { GoBackend(appContext) }.getOrNull()

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    private val _status = MutableStateFlow(VpnStatus(state = VpnState.DISABLED))
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private var connectJob: Job? = null
    private var peerKeys: List<Key> = emptyList()
    private var lastHandshakeMs: Long = 0L

    init {
        if (backend == null) {
            vpnLog.add(VpnErrorCodes.WG_TUNNEL_FAILED, "wireguard native backend unavailable")
        }
    }

    // -- 権限・競合 ----------------------------------------------------------

    /** OS の VPN 許可が付与済みか。 */
    fun isPermissionGranted(): Boolean = VpnService.prepare(appContext) == null

    /** 未許可時に OS 許可ダイアログを開くための Intent。null なら付与済み。 */
    fun permissionIntent(): Intent? = VpnService.prepare(appContext)

    /** 他 VPN がアクティブか（同一スロット競合の注意表示用）。 */
    fun otherVpnActive(): Boolean = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !isTunnelUp()
    }.getOrDefault(false)

    fun isTunnelUp(): Boolean = backend?.getRunningTunnelNames()?.contains(TUNNEL_NAME) == true

    // -- 接続 ---------------------------------------------------------------

    /**
     * ユーザー明示操作の接続。失敗時は [VpnRetryPolicy] に従い再試行し、
     * 上限到達で停止する（自動で再開しない）。
     */
    fun connect(profile: WireGuardProfile, policy: VpnRetryPolicy = VpnRetryPolicy()) {
        connectJob?.cancel()
        connectJob = scope.launch {
            updateStatus { it.copy(state = VpnState.CONNECTING, retryCount = 0) }
            var failed = 0
            while (true) {
                val code = tryConnectOnce(profile)
                if (code == null) {
                    updateStatus { it.copy(retryCount = failed) }
                    return@launch
                }
                failed++
                updateStatus { it.copy(retryCount = failed, lastError = code) }
                vpnLog.add(code, "connect attempt $failed failed: $code")
                if (!policy.shouldRetry(failed)) {
                    vpnLog.add(
                        VpnErrorCodes.RETRIES_EXHAUSTED,
                        "gave up after $failed attempts; waiting for user action",
                    )
                    updateStatus { it.copy(state = VpnState.ERROR, lastError = VpnErrorCodes.RETRIES_EXHAUSTED) }
                    return@launch
                }
                delay(policy.delayBeforeNextMs(failed - 1))
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        scope.launch {
            runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
            lastHandshakeMs = 0L
            peerKeys = emptyList()
            updateStatus {
                it.copy(
                    state = VpnState.DISCONNECTED,
                    tunnelUp = false,
                    handshakeAgeSec = null,
                    rxBytes = 0L,
                    txBytes = 0L,
                    serverReachable = null,
                    apiAuthorized = null,
                    lastError = VpnErrorCodes.NONE,
                    retryCount = 0,
                )
            }
            vpnLog.add(VpnErrorCodes.NONE, "tunnel down by user action")
        }
    }

    /**
     * サーバー接続時の自動起動。許可 UI は出さない。
     * @return トンネルが利用可能なら true。
     */
    suspend fun ensureUpForSync(
        profile: WireGuardProfile?,
        settings: ConnectionSettings,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!settings.wantsVpn()) return@withContext false
        if (profile == null) {
            vpnLog.add(VpnErrorCodes.CONFIG_MISSING, "vpn mode but no profile saved")
            updateStatus { it.copy(state = VpnState.CONFIG_MISSING) }
            return@withContext false
        }
        if (isTunnelUp()) {
            refreshStats(profile)
            return@withContext true
        }
        if (!isPermissionGranted()) {
            vpnLog.add(VpnErrorCodes.VPN_PERMISSION_REQUIRED, "auto-start skipped: permission not granted")
            updateStatus { it.copy(state = VpnState.PERMISSION_REQUIRED) }
            return@withContext false
        }
        // 同期時は単発試行のみ（再試行ループは UI 操作の接続に任せる）。
        val code = tryConnectOnce(profile)
        if (code != null) {
            vpnLog.add(code, "auto-start failed: $code (sync continues offline)")
            return@withContext false
        }
        true
    }

    /** 統計・handshake の再取得。UI の更新ボタン・接続直後に呼ぶ。 */
    fun refreshStats(profile: WireGuardProfile? = null) {
        scope.launch {
            val up = isTunnelUp()
            val stats = runCatching { backend?.getStatistics(tunnel) }.getOrNull()
            val rx = stats?.totalRx() ?: 0L
            val tx = stats?.totalTx() ?: 0L
            var latest = 0L
            stats?.let { s ->
                val keys = peerKeys.ifEmpty { s.peers().toList() }
                for (k in keys) {
                    val h = s.peer(k)?.latestHandshakeEpochMillis ?: 0L
                    if (h > latest) latest = h
                }
            }
            if (latest > 0L) lastHandshakeMs = latest
            val ageSec = if (lastHandshakeMs > 0L) {
                ((System.currentTimeMillis() - lastHandshakeMs) / 1000L).coerceAtLeast(0L)
            } else {
                null
            }
            updateStatus {
                val base = it.copy(
                    tunnelUp = up,
                    rxBytes = rx,
                    txBytes = tx,
                    handshakeAgeSec = ageSec,
                    permissionGranted = isPermissionGranted(),
                    otherVpnActive = otherVpnActive(),
                )
                if (profile != null) {
                    base.copy(
                        profileName = profile.displayName,
                        endpointHost = profile.endpointHost,
                        hasDefaultRoute = profile.hasDefaultRoute,
                    )
                } else {
                    base
                }
            }
            // handshake が古い接続は DEGRADED 扱い。
            updateStatus {
                if (it.state == VpnState.CONNECTED && ageSec != null && ageSec > DEGRADED_AFTER_SEC) {
                    it.copy(state = VpnState.DEGRADED)
                } else {
                    it
                }
            }
        }
    }

    fun noteServerCheck(reachable: Boolean?, authorized: Boolean?) {
        scope.launch {
            updateStatus { it.copy(serverReachable = reachable, apiAuthorized = authorized) }
        }
    }

    fun close() {
        scope.coroutineContext.cancelChildren()
    }

    // -- 内部 ---------------------------------------------------------------

    /**
     * 1 回の接続試行。成功で null、失敗でエラーコードを返す。
     */
    private suspend fun tryConnectOnce(profile: WireGuardProfile): String? =
        withContext(Dispatchers.IO) {
            val b = backend ?: return@withContext VpnErrorCodes.WG_TUNNEL_FAILED
            val config = runCatching {
                Config.parse(ByteArrayInputStream(profile.sanitizedConfig.toByteArray(Charsets.UTF_8)))
            }.getOrNull() ?: run {
                updateStatus { it.copy(state = VpnState.ERROR) }
                vpnLog.add(VpnErrorCodes.WG_CONFIG_INVALID, "saved profile failed to parse")
                return@withContext VpnErrorCodes.WG_CONFIG_INVALID
            }
            if (!isPermissionGranted()) {
                updateStatus { it.copy(state = VpnState.PERMISSION_REQUIRED) }
                return@withContext VpnErrorCodes.VPN_PERMISSION_REQUIRED
            }
            updateStatus {
                it.copy(
                    state = VpnState.CONNECTING,
                    profileName = profile.displayName,
                    endpointHost = profile.endpointHost,
                    hasDefaultRoute = profile.hasDefaultRoute,
                    permissionGranted = true,
                    otherVpnActive = otherVpnActive(),
                )
            }
            val upError = runCatching {
                b.setState(tunnel, Tunnel.State.UP, config)
            }.exceptionOrNull()
            if (upError != null) {
                val code = mapBackendError(upError)
                updateStatus { it.copy(state = VpnState.CONNECTING, lastError = code) }
                runCatching { b.setState(tunnel, Tunnel.State.DOWN, null) }
                return@withContext code
            }
            peerKeys = config.peers.map { it.publicKey }
            // handshake 確認（最大 HANDSHAKE_WAIT_MS）。
            val deadline = System.currentTimeMillis() + HANDSHAKE_WAIT_MS
            var handshook = false
            while (System.currentTimeMillis() < deadline) {
                val stats = runCatching { b.getStatistics(tunnel) }.getOrNull()
                val latest = stats?.let { s ->
                    s.peers().maxOfOrNull { k -> s.peer(k)?.latestHandshakeEpochMillis ?: 0L } ?: 0L
                } ?: 0L
                if (latest > 0L) {
                    lastHandshakeMs = latest
                    handshook = true
                    break
                }
                delay(HANDSHAKE_POLL_MS)
            }
            if (!handshook) {
                runCatching { b.setState(tunnel, Tunnel.State.DOWN, null) }
                updateStatus { it.copy(state = VpnState.CONNECTING) }
                return@withContext VpnErrorCodes.WG_HANDSHAKE_TIMEOUT
            }
            updateStatus {
                it.copy(
                    state = VpnState.CONNECTED,
                    tunnelUp = true,
                    lastError = VpnErrorCodes.NONE,
                    handshakeAgeSec = 0L,
                )
            }
            vpnLog.add(VpnErrorCodes.NONE, "tunnel up: ${profile.describe()}")
            refreshStats(profile)
            null
        }

    private fun mapBackendError(e: Throwable): String {
        val reason = (e as? BackendException)?.reason
        vpnLog.add(
            VpnErrorCodes.WG_TUNNEL_FAILED,
            "backend error: ${reason ?: e.javaClass.simpleName}",
        )
        return when (reason) {
            BackendException.Reason.VPN_NOT_AUTHORIZED -> VpnErrorCodes.VPN_PERMISSION_REQUIRED
            BackendException.Reason.DNS_RESOLUTION_FAILURE -> VpnErrorCodes.WG_DNS_FAILED
            BackendException.Reason.UNABLE_TO_START_VPN,
            BackendException.Reason.TUN_CREATION_ERROR,
            BackendException.Reason.GO_ACTIVATION_ERROR_CODE,
            -> VpnErrorCodes.WG_TUNNEL_FAILED
            else -> VpnErrorCodes.WG_TUNNEL_FAILED
        }
    }

    private suspend fun updateStatus(transform: (VpnStatus) -> VpnStatus) {
        // StateFlow の更新はスレッドセーフだが、他 VPN 検出など UI 側参照の一貫性のため集約。
        _status.value = transform(_status.value)
    }

    companion object {
        const val TUNNEL_NAME = "everyroutes"
        private const val HANDSHAKE_WAIT_MS = 15_000L
        private const val HANDSHAKE_POLL_MS = 500L
        private const val DEGRADED_AFTER_SEC = 180L
    }
}
