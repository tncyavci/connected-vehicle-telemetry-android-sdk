package com.connectedvehicle.telemetry.domain.simulator

import com.connectedvehicle.telemetry.domain.model.ChargingState
import com.connectedvehicle.telemetry.domain.model.GearPosition
import com.connectedvehicle.telemetry.domain.model.RemoteActuationType
import com.connectedvehicle.telemetry.domain.model.RemoteVehicleCommand
import com.connectedvehicle.telemetry.domain.model.TirePressureSnapshot
import com.connectedvehicle.telemetry.domain.model.VehicleTelemetryFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Deterministic CAN-Bus / e-Mobility vehicle simulator for offline/demo mode and integration tests.
 */
class DeterministicCanBusSimulator(
    initialVin: String = "TR-TOFAS-CONNECT-2026-001",
    initialClockMs: Long = 1_760_000_000_000L,
) {
    private val _currentFrame = MutableStateFlow(
        VehicleTelemetryFrame(
            vin = initialVin,
            sequenceNumber = 1L,
            timestampEpochMs = initialClockMs,
            speedKmh = 0.0,
            rpm = 0,
            gearPosition = GearPosition.PARK,
            batterySocPercent = 78.5,
            estimatedRangeKm = 342,
            hvBatteryTempCelsius = 27.4,
            cabinTempCelsius = 24.5,
            hvacTargetTempCelsius = 21.0,
            hvacActive = false,
            doorsLocked = true,
            trunkOpen = false,
            chargingState = ChargingState.DISCONNECTED,
            tirePressuresBar = TirePressureSnapshot(
                frontLeftBar = 2.35,
                frontRightBar = 2.34,
                rearLeftBar = 2.32,
                rearRightBar = 2.33,
            ),
            latitude = 41.0082,
            longitude = 28.9784,
            activeDtcCodes = emptyList(),
        )
    )

    val currentFrame: StateFlow<VehicleTelemetryFrame> = _currentFrame.asStateFlow()

    /**
     * Advances the simulated vehicle state by one tick (e.g. 500ms).
     */
    fun stepTick(driveModeActive: Boolean = false, injectFaultCode: String? = null): VehicleTelemetryFrame {
        val prev = _currentFrame.value
        val nextSeq = prev.sequenceNumber + 1
        val nextTime = prev.timestampEpochMs + 1_000L

        val newSpeed = if (driveModeActive) {
            (62.0 + 18.0 * sin(nextSeq * 0.25)).coerceAtLeast(5.0)
        } else {
            0.0
        }

        val newGear = if (driveModeActive) GearPosition.DRIVE else GearPosition.PARK
        val newRpm = if (driveModeActive) (newSpeed * 68).toInt() else 0
        val socDelta = when {
            prev.chargingState == ChargingState.DC_FAST_CHARGING -> +0.4
            driveModeActive -> -0.08
            prev.hvacActive -> -0.02
            else -> 0.0
        }
        val newSoc = (prev.batterySocPercent + socDelta).coerceIn(5.0, 100.0)
        val newRange = (newSoc * 4.35).toInt()

        val newCabinTemp = if (prev.hvacActive) {
            val diff = prev.hvacTargetTempCelsius - prev.cabinTempCelsius
            prev.cabinTempCelsius + diff * 0.25
        } else {
            prev.cabinTempCelsius
        }

        val dtcs = if (injectFaultCode != null) {
            (prev.activeDtcCodes + injectFaultCode).distinct()
        } else {
            prev.activeDtcCodes
        }

        val updated = prev.copy(
            sequenceNumber = nextSeq,
            timestampEpochMs = nextTime,
            speedKmh = "%.1f".format(java.util.Locale.US, newSpeed).toDouble(),
            rpm = newRpm,
            gearPosition = newGear,
            batterySocPercent = "%.1f".format(java.util.Locale.US, newSoc).toDouble(),
            estimatedRangeKm = newRange,
            cabinTempCelsius = "%.1f".format(java.util.Locale.US, newCabinTemp).toDouble(),
            latitude = prev.latitude + if (driveModeActive) 0.00015 else 0.0,
            longitude = prev.longitude + if (driveModeActive) 0.00022 else 0.0,
            activeDtcCodes = dtcs,
        )
        _currentFrame.value = updated
        return updated
    }

    /**
     * Applies an approved [RemoteVehicleCommand] to the simulated vehicle ECU state.
     */
    fun applyCommand(command: RemoteVehicleCommand): VehicleTelemetryFrame {
        val prev = _currentFrame.value
        val updated = when (command.actuationType) {
            RemoteActuationType.LOCK_DOORS -> prev.copy(doorsLocked = true)
            RemoteActuationType.UNLOCK_DOORS -> prev.copy(doorsLocked = false)
            RemoteActuationType.START_HVAC_PRECONDITIONING -> prev.copy(
                hvacActive = true,
                hvacTargetTempCelsius = command.targetTemperatureCelsius ?: prev.hvacTargetTempCelsius,
            )
            RemoteActuationType.STOP_HVAC_PRECONDITIONING -> prev.copy(hvacActive = false)
            RemoteActuationType.OPEN_TRUNK -> prev.copy(trunkOpen = true)
            RemoteActuationType.START_CHARGE_LIMIT_80 -> prev.copy(
                chargingState = if (prev.batterySocPercent < 80.0) ChargingState.DC_FAST_CHARGING else ChargingState.COMPLETED,
            )
            RemoteActuationType.FLASH_HAZARD_LIGHTS -> prev
        }.copy(
            sequenceNumber = prev.sequenceNumber + 1,
            timestampEpochMs = max(prev.timestampEpochMs + 100L, command.issuedAtEpochMs),
        )
        _currentFrame.value = updated
        return updated
    }

    fun clearFaultCodes(): VehicleTelemetryFrame {
        val updated = _currentFrame.value.copy(activeDtcCodes = emptyList())
        _currentFrame.value = updated
        return updated
    }

    fun observePeriodicStream(intervalMs: Long = 1_000L, driveModeActive: Boolean = false): Flow<VehicleTelemetryFrame> = flow {
        emit(_currentFrame.value)
        while (true) {
            delay(intervalMs)
            emit(stepTick(driveModeActive = driveModeActive))
        }
    }
}
