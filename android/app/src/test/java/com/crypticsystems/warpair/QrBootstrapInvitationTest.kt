package com.crypticsystems.warpair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QrBootstrapInvitationTest {
    @Test
    fun invitationRoundTripsRoutesAndLanEndpoints() {
        val now = 1_800_000_000L
        val invitation = QrBootstrap.createInvitation(
            deviceName = "WarPair Living Room",
            routes = setOf("lan", "wifi_direct", "bluetooth"),
            lanEndpoints = listOf(
                QrBootstrap.Endpoint("192.168.1.12", 19730),
                QrBootstrap.Endpoint("192.168.1.13", 19730),
            ),
            bluetoothServiceUuid = "a8c2f4d0-6e5b-4d91-9a3f-575250414952",
            bluetoothDeviceName = "Moses Phone",
            nowEpochSeconds = now,
        )

        val decoded = QrBootstrap.parseInvitation(
            QrBootstrap.encodeInvitation(invitation),
            nowEpochSeconds = now,
        )

        assertEquals(invitation.version, decoded.version)
        assertEquals(invitation.sessionId, decoded.sessionId)
        assertEquals(invitation.expiresAtEpochSeconds, decoded.expiresAtEpochSeconds)
        assertEquals(invitation.deviceName, decoded.deviceName)
        assertEquals(invitation.routes, decoded.routes)
        assertEquals(invitation.lanEndpoints, decoded.lanEndpoints)
        assertEquals(invitation.bluetoothServiceUuid, decoded.bluetoothServiceUuid)
        assertEquals(invitation.bluetoothDeviceName, decoded.bluetoothDeviceName)
    }

    @Test
    fun invitationsUseDifferentRandomSessionIdsAndExpire() {
        val now = 1_800_000_000L
        val first = QrBootstrap.createInvitation("Phone A", setOf("wifi_direct"), nowEpochSeconds = now)
        val second = QrBootstrap.createInvitation("Phone A", setOf("wifi_direct"), nowEpochSeconds = now)
        assertNotEquals(first.sessionId, second.sessionId)
        assertThrows(IllegalArgumentException::class.java) {
            QrBootstrap.parseInvitation(QrBootstrap.encodeInvitation(first), nowEpochSeconds = first.expiresAtEpochSeconds)
        }
    }

    @Test
    fun rejectsMissingLanEndpointAndUnsupportedRoutes() {
        val now = 1_800_000_000L
        assertThrows(IllegalArgumentException::class.java) {
            QrBootstrap.createInvitation("Phone", setOf("lan"), nowEpochSeconds = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrBootstrap.createInvitation("Phone", setOf("infrared"), nowEpochSeconds = now)
        }
    }

    @Test
    fun legacyLanPayloadRemainsReadable() {
        val old = QrBootstrap.encodeUri(
            QrBootstrap.Payload("192.168.1.4", 19730, deviceName = "Old phone"),
        )
        val parsed = QrBootstrap.parseUri(old)
        assertEquals("192.168.1.4", parsed.host)
        assertEquals(19730, parsed.port)
        assertEquals("Old phone", parsed.deviceName)
    }
}
