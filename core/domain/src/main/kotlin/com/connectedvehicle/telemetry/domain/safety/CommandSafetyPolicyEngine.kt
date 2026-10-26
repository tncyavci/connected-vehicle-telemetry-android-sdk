package com.connectedvehicle.telemetry.domain.safety

import com.connectedvehicle.telemetry.domain.model.GearPosition
import com.connectedvehicle.telemetry.domain.model.RemoteActuationType
import com.connectedvehicle.telemetry.domain.model.RemoteVehicleCommand
import com.connectedvehicle.telemetry.domain.model.VehicleTelemetryFrame

/**
 * ISO 26262 (ASIL-B) inspired safety interlock and command replay/TTL guard.
 *
 * Prevents dangerous remote actuations (e.g., unlocking doors or opening the trunk while
 * the vehicle is in motion, or starting HVAC when HV battery SoC is critically depleted)
 * and enforces strict command expiration & nonce replay protection.
 */
class CommandSafetyPolicyEngine(
    private val maxClockSkewMs: Long = 5_000L,
    private val maxCommandTtlMs: Long = 60_000L,
    private val minSocForHvacPercent: Double = 15.0,
) {
    private val executedNonces = LinkedHashSet<String>()

    sealed interface SafetyVerdict {
        data object Approved : SafetyVerdict
        data class Rejected(val reasonCode: RejectionReason, val message: String) : SafetyVerdict
    }

    enum class RejectionReason {
        COMMAND_EXPIRED,
        TTL_WINDOW_TOO_WIDE,
        REPLAY_NONCE_DETECTED,
        VIN_MISMATCH,
        VEHICLE_IN_MOTION,
        GEAR_NOT_IN_PARK,
        BATTERY_SOC_TOO_LOW,
        INVALID_HVAC_TARGET_TEMP,
    }

    @Synchronized
    fun validate(
        command: RemoteVehicleCommand,
        latestTelemetry: VehicleTelemetryFrame,
        nowEpochMs: Long,
    ): SafetyVerdict {
        if (command.vin != latestTelemetry.vin) {
            return SafetyVerdict.Rejected(
                RejectionReason.VIN_MISMATCH,
                "Command VIN (${command.vin}) does not match active vehicle VIN (${latestTelemetry.vin}).",
            )
        }

        if (nowEpochMs > command.expiresAtEpochMs || command.issuedAtEpochMs > nowEpochMs + maxClockSkewMs) {
            return SafetyVerdict.Rejected(
                RejectionReason.COMMAND_EXPIRED,
                "Command timestamp outside valid execution window.",
            )
        }

        val ttl = command.expiresAtEpochMs - command.issuedAtEpochMs
        if (ttl <= 0L || ttl > maxCommandTtlMs) {
            return SafetyVerdict.Rejected(
                RejectionReason.TTL_WINDOW_TOO_WIDE,
                "Command TTL ($ttl ms) exceeds maximum safety window ($maxCommandTtlMs ms).",
            )
        }

        if (command.nonceHex in executedNonces) {
            return SafetyVerdict.Rejected(
                RejectionReason.REPLAY_NONCE_DETECTED,
                "Duplicate command nonce (${command.nonceHex}) blocked by anti-replay guard.",
            )
        }

        val requiresStationaryVehicle = when (command.actuationType) {
            RemoteActuationType.UNLOCK_DOORS,
            RemoteActuationType.OPEN_TRUNK,
            -> true
            RemoteActuationType.LOCK_DOORS,
            RemoteActuationType.START_HVAC_PRECONDITIONING,
            RemoteActuationType.STOP_HVAC_PRECONDITIONING,
            RemoteActuationType.START_CHARGE_LIMIT_80,
            RemoteActuationType.FLASH_HAZARD_LIGHTS,
            -> false
        }

        if (requiresStationaryVehicle) {
            if (latestTelemetry.speedKmh > 0.5) {
                return SafetyVerdict.Rejected(
                    RejectionReason.VEHICLE_IN_MOTION,
                    "Cannot execute ${command.actuationType} while vehicle speed is ${latestTelemetry.speedKmh} km/h.",
                )
            }
            if (latestTelemetry.gearPosition != GearPosition.PARK) {
                return SafetyVerdict.Rejected(
                    RejectionReason.GEAR_NOT_IN_PARK,
                    "Cannot execute ${command.actuationType} unless transmission is in PARK (current: ${latestTelemetry.gearPosition}).",
                )
            }
        }

        if (command.actuationType == RemoteActuationType.START_HVAC_PRECONDITIONING) {
            if (latestTelemetry.batterySocPercent < minSocForHvacPercent) {
                return SafetyVerdict.Rejected(
                    RejectionReason.BATTERY_SOC_TOO_LOW,
                    "HV battery SoC (${latestTelemetry.batterySocPercent}%) is below minimum ${minSocForHvacPercent}% required for remote HVAC.",
                )
            }
            val targetTemp = command.targetTemperatureCelsius
            if (targetTemp == null || targetTemp !in 16.0..28.0) {
                return SafetyVerdict.Rejected(
                    RejectionReason.INVALID_HVAC_TARGET_TEMP,
                    "HVAC target temperature ($targetTemp°C) must be within [16.0°C, 28.0°C].",
                )
            }
        }

        executedNonces.add(command.nonceHex)
        if (executedNonces.size > 1_024) {
            val eldest = executedNonces.first()
            executedNonces.remove(eldest)
        }

        return SafetyVerdict.Approved
    }
}
