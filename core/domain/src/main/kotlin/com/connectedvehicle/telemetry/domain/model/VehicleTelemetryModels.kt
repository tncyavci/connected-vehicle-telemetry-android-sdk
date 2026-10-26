package com.connectedvehicle.telemetry.domain.model

import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of real-time CAN-Bus / OBD-II / e-Mobility vehicle telemetry.
 */
@Serializable
data class VehicleTelemetryFrame(
    val vin: String,
    val sequenceNumber: Long,
    val timestampEpochMs: Long,
    val speedKmh: Double,
    val rpm: Int,
    val gearPosition: GearPosition,
    val batterySocPercent: Double,
    val estimatedRangeKm: Int,
    val hvBatteryTempCelsius: Double,
    val cabinTempCelsius: Double,
    val hvacTargetTempCelsius: Double,
    val hvacActive: Boolean,
    val doorsLocked: Boolean,
    val trunkOpen: Boolean,
    val chargingState: ChargingState,
    val tirePressuresBar: TirePressureSnapshot,
    val latitude: Double,
    val longitude: Double,
    val activeDtcCodes: List<String> = emptyList(),
)

@Serializable
enum class GearPosition {
    PARK, REVERSE, NEUTRAL, DRIVE, REGEN_BRAKE
}

@Serializable
enum class ChargingState {
    DISCONNECTED, AC_CHARGING, DC_FAST_CHARGING, COMPLETED, FAULT
}

@Serializable
data class TirePressureSnapshot(
    val frontLeftBar: Double,
    val frontRightBar: Double,
    val rearLeftBar: Double,
    val rearRightBar: Double,
) {
    fun minimumBar(): Double = minOf(frontLeftBar, frontRightBar, rearLeftBar, rearRightBar)
}

/**
 * ISO 15031-6 / SAE J2012 Diagnostic Trouble Code (DTC) classification.
 */
@Serializable
enum class DtcSubsystem(val prefix: Char, val description: String) {
    POWERTRAIN('P', "Powertrain (Engine, Transmission & HV Inverter)"),
    CHASSIS('C', "Chassis (ABS, ESC, Steering & Suspension)"),
    BODY('B', "Body (Airbags, HVAC, Lighting & Immobilizer)"),
    NETWORK('U', "UART / CAN-Bus Communication Network"),
}

@Serializable
enum class DtcSeverity {
    INFO, WARNING, CRITICAL_STOP_VEHICLE
}

@Serializable
data class DiagnosticTroubleCode(
    val code: String,
    val subsystem: DtcSubsystem,
    val isOemSpecific: Boolean,
    val severity: DtcSeverity,
    val summary: String,
    val recommendedAction: String,
)

/**
 * Remote vehicle actuation command types issued by the mobile client.
 */
@Serializable
enum class RemoteActuationType {
    LOCK_DOORS,
    UNLOCK_DOORS,
    START_HVAC_PRECONDITIONING,
    STOP_HVAC_PRECONDITIONING,
    OPEN_TRUNK,
    START_CHARGE_LIMIT_80,
    FLASH_HAZARD_LIGHTS,
}

/**
 * Unsigned command intent before Ed25519 cryptographic signing.
 */
@Serializable
data class RemoteVehicleCommand(
    val commandId: String,
    val vin: String,
    val actuationType: RemoteActuationType,
    val targetTemperatureCelsius: Double? = null,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val nonceHex: String,
) {
    /**
     * Canonical deterministic payload string for Ed25519 signing and verification.
     */
    fun canonicalPayload(): String = buildString {
        append("v1|")
        append(commandId).append('|')
        append(vin).append('|')
        append(actuationType.name).append('|')
        append(targetTemperatureCelsius?.let { "%.1f".format(java.util.Locale.US, it) } ?: "NONE").append('|')
        append(issuedAtEpochMs).append('|')
        append(expiresAtEpochMs).append('|')
        append(nonceHex)
    }
}

/**
 * Cryptographically signed command envelope ready for cellular/offline-queue dispatch.
 */
@Serializable
data class SignedVehicleCommandEnvelope(
    val command: RemoteVehicleCommand,
    val signatureBase64: String,
    val publicKeyId: String,
)
