package com.everyroutes.app

import android.app.Application
import com.everyroutes.app.vpn.EveryRoutesVpnManager

/**
 * アプリプロセス共有の VPN マネージャー保持。
 * 画面回転などの Activity 再生成でトンネル制御が多重化・リークしないようにする。
 */
class EveryRoutesApp : Application() {
    val vpnManager: EveryRoutesVpnManager by lazy { EveryRoutesVpnManager(this) }
}
