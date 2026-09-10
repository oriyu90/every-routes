package com.everyroutes.app.vpn

/**
 * 自動再接続ポリシー。失敗時はバックオフで再試行し、上限到達で停止する。
 * 再開はユーザー操作のみ（ポリシーは「続けるか」の判定だけを持ち、起動は行わない）。
 */
data class VpnRetryPolicy(
    val maxAttempts: Int = MAX_ATTEMPTS,
    val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
) {
    /** [failedAttempts] 回失敗済みの状態で、もう一度試してよいか。 */
    fun shouldRetry(failedAttempts: Int): Boolean = failedAttempts < maxAttempts

    /** [failedAttempts] 回失敗済みの次に待つミリ秒。上限超過時は 0。 */
    fun delayBeforeNextMs(failedAttempts: Int): Long =
        if (shouldRetry(failedAttempts)) {
            backoffMs.getOrElse(failedAttempts) { backoffMs.last() }
        } else {
            0L
        }

    companion object {
        const val MAX_ATTEMPTS = 3
        val DEFAULT_BACKOFF_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L)
    }
}
