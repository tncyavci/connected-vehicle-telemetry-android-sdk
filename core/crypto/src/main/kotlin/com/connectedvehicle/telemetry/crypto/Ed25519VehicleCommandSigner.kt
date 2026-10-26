package com.connectedvehicle.telemetry.crypto

import com.connectedvehicle.telemetry.domain.model.RemoteVehicleCommand
import com.connectedvehicle.telemetry.domain.model.SignedVehicleCommandEnvelope
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Ed25519 asymmetric cryptographic command signer and verifier backed by Google Tink.
 *
 * Ensures that remote actuation commands (door unlock, trunk release, HVAC pre-conditioning)
 * cannot be forged or altered in transit between the mobile device, cloud broker, and vehicle TCU.
 */
class Ed25519VehicleCommandSigner(
    private val privateKeysetHandle: KeysetHandle = generateEphemeralKeyset(),
    private val publicKeyId: String = "key-ed25519-tcu-primary",
) {
    private val publicKeysetHandle: KeysetHandle = privateKeysetHandle.publicKeysetHandle
    private val signer: PublicKeySign = privateKeysetHandle.getPrimitive(PublicKeySign::class.java)
    private val verifier: PublicKeyVerify = publicKeysetHandle.getPrimitive(PublicKeyVerify::class.java)

    /**
     * Signs [command] using its deterministic canonical representation and returns a
     * [SignedVehicleCommandEnvelope] containing the Base64-encoded Ed25519 signature.
     */
    fun sign(command: RemoteVehicleCommand): SignedVehicleCommandEnvelope {
        val payloadBytes = command.canonicalPayload().toByteArray(Charsets.UTF_8)
        val rawSignature = signer.sign(payloadBytes)
        val signatureB64 = Base64.getEncoder().encodeToString(rawSignature)
        return SignedVehicleCommandEnvelope(
            command = command,
            signatureBase64 = signatureB64,
            publicKeyId = publicKeyId,
        )
    }

    /**
     * Verifies the cryptographic integrity and authenticity of [envelope].
     * Returns `true` only if the signature is valid for the exact canonical command payload.
     */
    fun verify(envelope: SignedVehicleCommandEnvelope): Boolean {
        return try {
            val signatureBytes = Base64.getDecoder().decode(envelope.signatureBase64)
            val payloadBytes = envelope.command.canonicalPayload().toByteArray(Charsets.UTF_8)
            verifier.verify(signatureBytes, payloadBytes)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Exports a standalone [PublicKeyVerify] primitive for TCU / Gateway verification testing.
     */
    fun publicVerifier(): PublicKeyVerify = verifier

    companion object {
        init {
            SignatureConfig.register()
        }

        fun generateEphemeralKeyset(): KeysetHandle =
            KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
    }
}
