package com.everyroutes.app.vpn

/**
 * wg-quick テキストの検証と app-only 強制。
 *
 * 公式 tunnel ライブラリに依存しないテキスト処理に限定し、JVM 単体テストで検証する。
 * 実際のトンネル起動前には公式 `Config.parse` でも再検証される（二重検証）。
 */
object WireGuardSanitizer {
    const val ALLOWED_PACKAGE = "com.everyroutes.app"

    sealed interface SanitizeResult {
        data class Ok(
            val sanitizedConfig: String,
            val endpointHost: String?,
            val hasDefaultRoute: Boolean,
            val droppedAppFilter: Boolean,
        ) : SanitizeResult

        data class Err(
            val code: String,
            /** 非秘密の詳細（不足セクション名など）。鍵・トークンを含めない。 */
            val detail: String,
        ) : SanitizeResult
    }

    fun sanitize(raw: String): SanitizeResult {
        val lines = raw.lineSequence()
            .map { line ->
                val hash = line.indexOf('#')
                if (hash >= 0) line.substring(0, hash) else line
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) {
            return SanitizeResult.Err(VpnErrorCodes.WG_CONFIG_INVALID, "empty config")
        }

        var section: String? = null
        val interfaceLines = mutableListOf<String>()
        val peerBlocks = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (line.startsWith("[")) {
                section = when {
                    line.equals("[Interface]", ignoreCase = true) -> "Interface"
                    line.equals("[Peer]", ignoreCase = true) -> {
                        peerBlocks.add(mutableListOf())
                        "Peer"
                    }
                    else -> return SanitizeResult.Err(
                        VpnErrorCodes.WG_CONFIG_INVALID,
                        "unknown section: $line",
                    )
                }
            } else {
                when (section) {
                    "Interface" -> interfaceLines.add(line)
                    "Peer" -> peerBlocks.last().add(line)
                    else -> return SanitizeResult.Err(
                        VpnErrorCodes.WG_CONFIG_INVALID,
                        "content outside section",
                    )
                }
            }
        }
        if (interfaceLines.isEmpty()) {
            return SanitizeResult.Err(VpnErrorCodes.WG_CONFIG_INVALID, "missing [Interface]")
        }
        if (peerBlocks.isEmpty()) {
            return SanitizeResult.Err(VpnErrorCodes.WG_CONFIG_INVALID, "missing [Peer]")
        }

        val iface = attributesOf(interfaceLines)
        if (!iface.containsKey("privatekey")) {
            return SanitizeResult.Err(VpnErrorCodes.WG_CONFIG_INVALID, "missing PrivateKey")
        }
        if (!iface.containsKey("address")) {
            return SanitizeResult.Err(VpnErrorCodes.WG_CONFIG_INVALID, "missing Address")
        }
        for ((index, peer) in peerBlocks.withIndex()) {
            val attrs = attributesOf(peer)
            if (!attrs.containsKey("publickey")) {
                return SanitizeResult.Err(
                    VpnErrorCodes.WG_CONFIG_INVALID,
                    "peer[$index] missing PublicKey",
                )
            }
            if (!attrs.containsKey("endpoint")) {
                return SanitizeResult.Err(
                    VpnErrorCodes.WG_CONFIG_INVALID,
                    "peer[$index] missing Endpoint",
                )
            }
            if (!attrs.containsKey("allowedips")) {
                return SanitizeResult.Err(
                    VpnErrorCodes.WG_CONFIG_INVALID,
                    "peer[$index] missing AllowedIPs",
                )
            }
        }

        var droppedAppFilter = false
        val cleanedInterface = interfaceLines.filter { line ->
            val key = line.substringBefore('=').trim()
            val isFilter = key.equals("IncludedApplications", ignoreCase = true) ||
                key.equals("ExcludedApplications", ignoreCase = true)
            if (isFilter) droppedAppFilter = true
            !isFilter
        }

        val allowedIpsValues = peerBlocks.flatMap { peer ->
            attributesOf(peer)["allowedips"].orEmpty()
        }
        val hasDefaultRoute = allowedIpsValues.flatMap { it.split(',') }.any { token ->
            val t = token.trim()
            t == "0.0.0.0/0" || t == "::/0"
        }
        val endpointHost = peerBlocks.firstNotNullOfOrNull { peer ->
            attributesOf(peer)["endpoint"]?.firstOrNull()?.let(::hostOf)
        }

        val out = buildString {
            appendLine("[Interface]")
            for (line in cleanedInterface) appendLine(line)
            appendLine("IncludedApplications = $ALLOWED_PACKAGE")
            for (peer in peerBlocks) {
                appendLine()
                appendLine("[Peer]")
                for (line in peer) appendLine(line)
            }
        }
        return SanitizeResult.Ok(
            sanitizedConfig = out,
            endpointHost = endpointHost,
            hasDefaultRoute = hasDefaultRoute,
            droppedAppFilter = droppedAppFilter,
        )
    }

    private fun attributesOf(lines: List<String>): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            map.getOrPut(key) { mutableListOf() }.add(value)
        }
        return map
    }

    private fun hostOf(endpoint: String): String? {
        val v = endpoint.trim()
        if (v.isEmpty()) return null
        // "host:port" / "[v6]:port" のホスト部だけを抜く（ポートは非表示でもよい情報だが不要）。
        val withoutBrackets = if (v.startsWith("[")) {
            v.substringBefore(']')
        } else {
            v.substringBeforeLast(':')
        }
        return withoutBrackets.trimStart('[').takeIf { it.isNotEmpty() }
    }
}
