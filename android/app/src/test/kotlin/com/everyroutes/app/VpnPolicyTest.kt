package com.everyroutes.app

import com.everyroutes.app.settings.ConnectionMode
import com.everyroutes.app.settings.ConnectionSettings
import com.everyroutes.app.vpn.VpnLog
import com.everyroutes.app.vpn.VpnRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPolicyTest {
    @Test
    fun vpnIsDisabledByDefault() {
        val s = ConnectionSettings()
        assertEquals(ConnectionMode.DIRECT, s.mode)
        assertFalse(s.vpnEnabled)
        assertFalse(s.wantsVpn())
    }

    @Test
    fun vpnIsWantedOnlyWhenModeAndToggleAreBothOn() {
        assertFalse(ConnectionSettings(mode = ConnectionMode.APP_ONLY_WIREGUARD).wantsVpn())
        assertFalse(ConnectionSettings(vpnEnabled = true).wantsVpn())
        assertTrue(
            ConnectionSettings(
                mode = ConnectionMode.APP_ONLY_WIREGUARD,
                vpnEnabled = true,
            ).wantsVpn(),
        )
    }

    @Test
    fun describeNeverContainsToken() {
        val s = ConnectionSettings(bearerToken = "super-secret-token-value")
        assertFalse(s.describe().contains("super-secret-token-value"))
    }

    @Test
    fun retryStopsAfterMaxAttempts() {
        val p = VpnRetryPolicy()
        assertTrue(p.shouldRetry(0))
        assertTrue(p.shouldRetry(2))
        assertFalse(p.shouldRetry(3))
        assertFalse(p.shouldRetry(10))
        assertEquals(0L, p.delayBeforeNextMs(3))
        assertTrue(p.delayBeforeNextMs(0) > 0)
    }

    @Test
    fun logRedactsSecrets() {
        val log = VpnLog()
        log.add("TEST", "PrivateKey = yAnzE1v3TqK9mN0xJ8wP4rS7uV2aB5cD6eF8gH0iJkL=")
        log.add("TEST", "bare xT9v3QaB7cD2eF6gH1iJ5kL9mN3oP7qR2sT6uV0wX4y= leaked")
        log.add("TEST", "endpoint vpn.example.net:51820 reachable")
        val snap = log.snapshot()
        assertFalse(snap[0].message.contains("yAnzE1"))
        assertTrue(snap[0].message.contains("***"))
        assertFalse(snap[1].message.contains("xT9v3"))
        // 非秘密のホスト名は残る。
        assertTrue(snap[2].message.contains("vpn.example.net"))
    }

    @Test
    fun logIsBounded() {
        val log = VpnLog(capacity = 5)
        repeat(8) { log.add("TEST", "msg $it") }
        val snap = log.snapshot()
        assertEquals(5, snap.size)
        assertTrue(snap.last().message.contains("msg 7"))
    }
}
