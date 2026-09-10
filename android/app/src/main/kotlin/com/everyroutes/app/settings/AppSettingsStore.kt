package com.everyroutes.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * 接続設定の保存。非秘密（モード・トグル・URL）は SharedPreferences、
 * Bearer トークンのみ EncryptedFile（Android Keystore 律束 AES-GCM）へ保存する。
 */
class AppSettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ConnectionSettings = ConnectionSettings(
        mode = runCatching {
            ConnectionMode.valueOf(
                prefs.getString(KEY_MODE, ConnectionMode.DIRECT.name)
                    ?: ConnectionMode.DIRECT.name,
            )
        }.getOrDefault(ConnectionMode.DIRECT),
        vpnEnabled = prefs.getBoolean(KEY_VPN_ENABLED, false),
        serverBaseUrl = prefs.getString(KEY_URL, "").orEmpty(),
        bearerToken = readToken(),
        trustSelfSigned = prefs.getBoolean(KEY_TRUST_SELF_SIGNED, false),
    )

    fun save(settings: ConnectionSettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putBoolean(KEY_VPN_ENABLED, settings.vpnEnabled)
            .putString(KEY_URL, settings.serverBaseUrl)
            .putBoolean(KEY_TRUST_SELF_SIGNED, settings.trustSelfSigned)
            .apply()
        writeToken(settings.bearerToken)
    }

    private fun tokenFile(): File = File(context.noBackupFilesDir, TOKEN_FILE)

    private fun masterKey(): MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private fun readToken(): String = runCatching {
        val f = tokenFile()
        if (!f.exists()) return ""
        EncryptedFile.Builder(
            context,
            f,
            masterKey(),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build().openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrDefault("")

    private fun writeToken(token: String) {
        runCatching {
            EncryptedFile.Builder(
                context,
                tokenFile(),
                masterKey(),
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build().openFileOutput().use { it.write(token.toByteArray(Charsets.UTF_8)) }
        }
    }

    companion object {
        private const val PREFS = "connection_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_VPN_ENABLED = "vpn_enabled"
        private const val KEY_URL = "server_base_url"
        private const val KEY_TRUST_SELF_SIGNED = "trust_self_signed"
        private const val TOKEN_FILE = "bearer_token.bin"
    }
}
