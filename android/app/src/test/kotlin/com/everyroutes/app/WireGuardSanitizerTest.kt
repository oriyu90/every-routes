package com.everyroutes.app

import com.everyroutes.app.vpn.VpnErrorCodes
import com.everyroutes.app.vpn.WireGuardSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardSanitizerTest {
    private val validConf = """
        [Interface]
        PrivateKey = yAnzE1v3TqK9mN0xJ8wP4rS7uV2aB5cD6eF8gH0iJkL=
        Address = 10.8.0.2/32
        DNS = 10.8.0.1

        [Peer]
        PublicKey = xT9v3QaB7cD2eF6gH1iJ5kL9mN3oP7qR2sT6uV0wX4y=
        Endpoint = vpn.example.net:51820
        AllowedIPs = 10.8.0.0/24
    """.trimIndent()

    @Test
    fun validConfigIsAcceptedWithAppOnlyEnforced() {
        val r = WireGuardSanitizer.sanitize(validConf)
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
        r as WireGuardSanitizer.SanitizeResult.Ok
        assertTrue(r.sanitizedConfig.contains("IncludedApplications = com.everyroutes.app"))
        assertFalse(r.sanitizedConfig.contains("ExcludedApplications"))
        assertEquals("vpn.example.net", r.endpointHost)
        assertFalse(r.hasDefaultRoute)
        assertFalse(r.droppedAppFilter)
    }

    @Test
    fun foreignAppFiltersAreDroppedAndReported() {
        val conf = validConf.replace(
            "Address = 10.8.0.2/32",
            "Address = 10.8.0.2/32\nIncludedApplications = com.evil.other\nExcludedApplications = com.other.app",
        )
        val r = WireGuardSanitizer.sanitize(conf)
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
        r as WireGuardSanitizer.SanitizeResult.Ok
        assertTrue(r.droppedAppFilter)
        assertFalse(r.sanitizedConfig.contains("com.evil.other"))
        assertFalse(r.sanitizedConfig.contains("com.other.app"))
        // 自パッケージのみが残る。
        val count = r.sanitizedConfig.lineSequence()
            .count { it.trim().startsWith("IncludedApplications") }
        assertEquals(1, count)
    }

    @Test
    fun defaultRouteIsDetected() {
        val conf = validConf.replace("AllowedIPs = 10.8.0.0/24", "AllowedIPs = 0.0.0.0/0, ::/0")
        val r = WireGuardSanitizer.sanitize(conf)
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Ok)
        assertTrue((r as WireGuardSanitizer.SanitizeResult.Ok).hasDefaultRoute)
    }

    @Test
    fun missingPrivateKeyIsRejected() {
        val conf = validConf.lines().filterNot { it.contains("PrivateKey") }.joinToString("\n")
        val r = WireGuardSanitizer.sanitize(conf)
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Err)
        assertEquals(VpnErrorCodes.WG_CONFIG_INVALID, (r as WireGuardSanitizer.SanitizeResult.Err).code)
    }

    @Test
    fun missingPeerIsRejected() {
        val conf = validConf.substringBefore("[Peer]")
        val r = WireGuardSanitizer.sanitize(conf)
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Err)
    }

    @Test
    fun emptyConfigIsRejected() {
        val r = WireGuardSanitizer.sanitize("  \n # nothing\n")
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Err)
    }

    @Test
    fun errorDetailNeverContainsSecrets() {
        val conf = validConf.replace("Endpoint = vpn.example.net:51820", "")
        val r = WireGuardSanitizer.sanitize(conf)
        assertTrue(r is WireGuardSanitizer.SanitizeResult.Err)
        val detail = (r as WireGuardSanitizer.SanitizeResult.Err).detail
        assertFalse(detail.contains("yAnzE1"))
        assertFalse(detail.contains("xT9v3Q"))
    }
}
