package com.everyroutes.app.vpn

import android.annotation.SuppressLint
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * サーバー到達性の多段診断。`GET /health`（公開）で到達性、
 * `GET /info`（認証付き）で Bearer の正否を切り分ける。
 */
object ServerReachability {
    const val TIMEOUT_MS = 8_000

    sealed interface CheckResult {
        data object Reachable : CheckResult
        data class HttpError(val code: Int) : CheckResult
        data object NetworkError : CheckResult
        data object BadUrl : CheckResult
    }

    fun checkHealth(baseUrl: String, trustSelfSigned: Boolean): CheckResult =
        get(baseUrl, "/api/v1/health", bearer = null, trustSelfSigned)

    fun checkAuth(baseUrl: String, bearer: String, trustSelfSigned: Boolean): CheckResult =
        get(baseUrl, "/api/v1/info", bearer, trustSelfSigned)

    private fun get(
        baseUrl: String,
        path: String,
        bearer: String?,
        trustSelfSigned: Boolean,
    ): CheckResult {
        val url = runCatching {
            URL(baseUrl.trim().trimEnd('/') + path)
        }.getOrNull() ?: return CheckResult.BadUrl
        if (url.protocol != "http" && url.protocol != "https") return CheckResult.BadUrl
        return runCatching {
            val conn = url.openConnection() as HttpURLConnection
            try {
                if (conn is HttpsURLConnection && trustSelfSigned) {
                    conn.sslSocketFactory = permissiveContext().socketFactory
                    conn.hostnameVerifier = permissiveVerifier()
                }
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/json")
                if (!bearer.isNullOrEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $bearer")
                }
                conn.connect()
                val code = conn.responseCode
                if (code in 200..299) CheckResult.Reachable else CheckResult.HttpError(code)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(CheckResult.NetworkError)
    }

    /**
     * 自己署名証明書の受け入れ（ユーザー明示の opt-in、LAN 内利用想定）。
     * 既定 OFF。ON の場合のみこの経路を使う。
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun permissiveContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    private fun permissiveVerifier(): HostnameVerifier = HostnameVerifier { _, _ -> true }
}
