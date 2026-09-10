package com.everyroutes.app.vpn

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * WireGuard プロファイル（秘密鍵含む）の暗号化保存。
 * VPN トグルの ON/OFF と独立に保持され、削除は明示操作でのみ行う。
 */
class WireGuardProfileStore(private val context: Context) {
    fun load(): WireGuardProfile? = runCatching {
        val f = profileFile()
        if (!f.exists()) return null
        val text = encryptedFile(f).openFileInput().use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val parts = text.split('\n', limit = 5)
        if (parts.size != 5) return null
        val config = String(Base64.decode(parts[4], Base64.NO_WRAP), Charsets.UTF_8)
        WireGuardProfile(
            displayName = parts[0],
            sanitizedConfig = config,
            endpointHost = parts[1].ifEmpty { null },
            hasDefaultRoute = parts[2] == "1",
            updatedAtMs = parts[3].toLongOrNull() ?: 0L,
        )
    }.getOrNull()

    fun save(profile: WireGuardProfile) {
        val encoded = Base64.encodeToString(
            profile.sanitizedConfig.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val text = listOf(
            profile.displayName.replace("\n", " "),
            profile.endpointHost.orEmpty(),
            if (profile.hasDefaultRoute) "1" else "0",
            profile.updatedAtMs.toString(),
            encoded,
        ).joinToString("\n")
        runCatching {
            encryptedFile(profileFile()).openFileOutput().use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** 明示操作でのみ呼ぶ。VPN OFF では削除しない。 */
    fun delete() {
        runCatching { profileFile().delete() }
    }

    private fun profileFile(): File = File(context.noBackupFilesDir, PROFILE_FILE)

    private fun encryptedFile(f: File): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context,
            f,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    companion object {
        private const val PROFILE_FILE = "wireguard_profile.bin"
    }
}
