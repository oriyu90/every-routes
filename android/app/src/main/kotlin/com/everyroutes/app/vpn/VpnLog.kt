package com.everyroutes.app.vpn

/**
 * VPN 診断ログ。直近 [capacity] 件のリングバッファ。
 * 記録時に秘密情報（PrivateKey / PresharedKey / 44文字鍵 / Bearer らしき値）をマスクする。
 */
class VpnLog(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Entry(val atMs: Long, val code: String, val message: String)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(code: String, rawMessage: String, atMs: Long = System.currentTimeMillis()) {
        entries.addLast(Entry(atMs, code, redact(rawMessage)))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 100
        const val MASK = "***"

        private val KEY_VALUE = Regex(
            """(?i)\b(privatekey|presharedkey|private_key|preshared_key|bearer|token|authorization)\b\s*[:=]\s*\S+""",
        )

        /** WireGuard 鍵（44文字 Base64、末尾 `=`）の素通しをマスクする。 */
        private val BARE_KEY = Regex("""\b[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])""")

        fun redact(raw: String): String {
            var out = KEY_VALUE.replace(raw) { m ->
                val key = m.value.substringBefore(':').substringBefore('=')
                "$key = $MASK"
            }
            out = BARE_KEY.replace(out, MASK)
            return out
        }
    }
}
