package com.connectedvehicle.telemetry.crypto

import com.connectedvehicle.telemetry.domain.model.RemoteActuationType
import com.connectedvehicle.telemetry.domain.model.RemoteVehicleCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519VehicleCommandSignerTest {

    private fun sampleCommand() = RemoteVehicleCommand(
        commandId = "cmd-ed25519-01",
        vin = "TR-TOFAS-CONNECT-2026-001",
        actuationType = RemoteActuationType.START_HVAC_PRECONDITIONING,
        targetTemperatureCelsius = 22.0,
        issuedAtEpochMs = 1_760_000_000_000L,
        expiresAtEpochMs = 1_760_000_030_000L,
        nonceHex = "99aabbccddeeff00",
    )

    @Test
    fun `Ed25519 signature round-trip succeeds for authentic command envelope`() {
        val signer = Ed25519VehicleCommandSigner()
        val envelope = signer.sign(sampleCommand())

        assertEquals("key-ed25519-tcu-primary", envelope.publicKeyId)
        assertTrue(signer.verify(envelope))
    }

    @Test
    fun `Ed25519 verification fails when command actuation type or VIN is tampered`() {
        val signer = Ed25519VehicleCommandSigner()
        val originalEnvelope = signer.sign(sampleCommand())

        val tamperedCommand = originalEnvelope.command.copy(
            actuationType = RemoteActuationType.UNLOCK_DOORS,
        )
        val tamperedEnvelope = originalEnvelope.copy(command = tamperedCommand)

        assertFalse(signer.verify(tamperedEnvelope))
    }

    @Test
    fun `Ed25519 verification fails when verified against a different vehicle keyset`() {
        val signerA = Ed25519VehicleCommandSigner()
        val signerB = Ed25519VehicleCommandSigner()

        val envelopeFromA = signerA.sign(sampleCommand())
        assertFalse(signerB.verify(envelopeFromA))
    }
}
