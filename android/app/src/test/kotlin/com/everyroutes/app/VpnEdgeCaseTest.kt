package com.everyroutes.app

import com.everyroutes.app.settings.ConnectionSettings
import com.everyroutes.app.vpn.VpnLog
import com.everyroutes.app.vpn.VpnRetryPolicy
import com.everyroutes.app.vpn.WireGuardSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEdgeCaseTest {
    private fun conf(vararg lines: String) = lines.joinToString("\n")

    private val base = arrayOf(
        "[Interface]",
        "PrivateKey = yAnzE1v3TqK9mN0xJ8wP4rS7uV2aB5cD6eF8gH0iJkL=",
        "Address = 10.8.0.2/32",
        "[Peer]",
        "PublicKey = xT9v3QaB7cD2eF6gH1iJ5kL9mN3oP7qR2sT6uV0wX4y=",
        "Endpoint = vpn.example.net:51820",
        "AllowedIPs = 10.8.0.0/24",
    )

    @Test
    fun crlfIsAccepted() {
        val r = WireGuardSanitizer.sanitize(base.joinToString("\r\n"))
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val r = WireGuardSanitizer.sanitize(
            conf("# leading comment", "", *base, "", "# trailing"),
        )
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
    }

    @Test
    fun attributeNamesAreCaseInsensitive() {
        val mixed = base.map {
            when {
                it.startsWith("PrivateKey") -> "privatekey = yAnzE1v3TqK9mN0xJ8wP4rS7uV2aB5cD6eF8gH0iJkL="
                it.startsWith("AllowedIPs") -> "ALLOWEDIPS = 10.8.0.0/24"
                else -> it
            }
        }.toTypedArray()
        val r = WireGuardSanitizer.sanitize(conf(*mixed))
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
    }

    @Test
    fun ipv6EndpointHostIsExtracted() {
        val v6 = base.map {
            if (it.startsWith("Endpoint")) "Endpoint = [fd00::1]:51820" else it
        }.toTypedArray()
        val r = WireGuardSanitizer.sanitize(conf(*v6))
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
        assertEquals("fd00::1", (r as WireGuardSanitizer.SanitizeResult.Ok).endpointHost)
    }

    @Test
    fun unknownSectionIsRejected() {
        val r = WireGuardSanitizer.sanitize(conf(*base, "[Wizard]", "Spell = 1"))
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Err)
    }

    @Test
    fun emptyPeerBlockIsRejected() {
        val r = WireGuardSanitizer.sanitize(conf("[Interface]", base[1], base[2], "[Peer]"))
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Err)
    }

    @Test
    fun splitInterfaceSectionsAreCombined() {
        val r = WireGuardSanitizer.sanitize(
            conf("[Interface]", base[1], "[Interface]", base[2], *base.sliceArray(3..6)),
        )
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
    }

    @Test
    fun defaultRouteWithSpacesIsDetected() {
        val v = base.map {
            if (it.startsWith("AllowedIPs")) "AllowedIPs = 10.8.0.0/24, 0.0.0.0/0 , ::/0" else it
        }.toTypedArray()
        val r = WireGuardSanitizer.sanitize(conf(*v))
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
        assertTrue((r as WireGuardSanitizer.SanitizeResult.Ok).hasDefaultRoute)
    }

    @Test
    fun baseUrlValidation() {
        assertEquals("blank", ConnectionSettings(serverBaseUrl = "   ").baseUrlError())
        assertEquals("blank", ConnectionSettings().baseUrlError())
        assertEquals("scheme", ConnectionSettings(serverBaseUrl = "10.8.0.1:8787").baseUrlError())
        assertNull(ConnectionSettings(serverBaseUrl = "http://10.8.0.1:8787").baseUrlError())
        assertNull(ConnectionSettings(serverBaseUrl = "  https://x:8787/ ").baseUrlError())
    }

    @Test
    fun customRetryPolicy() {
        val p = VpnRetryPolicy(maxAttempts = 1, backoffMs = listOf(500L))
        assertTrue(p.shouldRetry(0))
        assertFalse(p.shouldRetry(1))
        assertEquals(500L, p.delayBeforeNextMs(0))
        assertEquals(0L, p.delayBeforeNextMs(1))
        // index超過は末尾値を使う。
        val p2 = VpnRetryPolicy(maxAttempts = 9, backoffMs = listOf(100L))
        assertEquals(100L, p2.delayBeforeNextMs(7))
    }

    @Test
    fun logClear() {
        val log = VpnLog()
        log.add("A", "one")
        log.clear()
        assertTrue(log.snapshot().isEmpty())
        log.add("B", "two")
        assertEquals(1, log.snapshot().size)
    }
}
